package com.swiftmako.jormanager.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.swiftmako.jormanager.controllers.utils.BlockUtils
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.model.StakeFraction
import com.swiftmako.jormanager.utils.CardanoLeaderElection
import io.mockk.every
import io.mockk.spyk
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LeaderLogCalculatorConcurrencyTest {
    @Test
    fun `one worker budget is shared by simultaneous calculations`() = assertSharedBudget(2, 1)

    @Test
    fun `odd processor count rounds down to one shared worker`() = assertSharedBudget(3, 1)

    @Test
    fun `two workers really overlap but simultaneous calculations share their cap`() = assertSharedBudget(4, 2)

    private fun assertSharedBudget(visibleProcessors: Int, workers: Int) = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            supervisorScope {
                val admitted = CountDownLatch(workers)
                val release = CountDownLatch(1)
                val harness = harness(visibleProcessors, before = {
                    admitted.countDown()
                    release.awaitBounded("shared-budget evaluations released")
                })
                val firstChunks = mutableListOf<LeaderLogCalculator.ChunkResult>()
                val secondChunks = mutableListOf<LeaderLogCalculator.ChunkResult>()
                // UNDISPATCHED submits both requests' windows before the coordinator waits on the gate.
                val first = async(start = CoroutineStart.UNDISPATCHED) {
                    harness.calculate(FIRST_SLOT, CHUNK_SIZE * 3 + 1) { firstChunks.add(it) }
                }
                val secondFirstSlot = FIRST_SLOT + CHUNK_SIZE * 10
                val second = async(start = CoroutineStart.UNDISPATCHED) {
                    harness.calculate(secondFirstSlot, CHUNK_SIZE * 3 + 1) { secondChunks.add(it) }
                }
                try {
                    admitted.awaitSuspending("full worker budget admitted")
                    assertTrue(first.isActive)
                    assertTrue(second.isActive)
                    assertEquals(workers, harness.active.get())
                    assertEquals(workers, harness.maximumActive.get())
                    release.countDown()
                    first.await()
                    second.await()
                    assertAllSlots(firstChunks, FIRST_SLOT, CHUNK_SIZE * 3 + 1)
                    assertAllSlots(secondChunks, secondFirstSlot, CHUNK_SIZE * 3 + 1)
                    // This is checked over every evaluation in both complete requests, not just the gate snapshot.
                    assertEquals(workers, harness.maximumActive.get())
                    assertEquals(0, harness.active.get())
                    assertEquals(2 * (CHUNK_SIZE * 3 + 1), harness.completed.get())
                } finally {
                    release.countDown()
                    first.cancelAndJoin()
                    second.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun `blocked sink retains only initial window and one replacement chunk`() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            supervisorScope {
                val sinkEntered = CompletableDeferred<Unit>()
                val releaseSink = CompletableDeferred<Unit>()
                val pendingWindowFinished = CountDownLatch(2)
                val sinkBlocked = AtomicBoolean(true)
                val harness = harness(
                    4,
                    before = { slot ->
                        if (sinkBlocked.get()) {
                            assertTrue(slot < FIRST_SLOT + CHUNK_SIZE * 3, "work escaped the bounded CPU window: $slot")
                        }
                    },
                    after = { slot ->
                        if (slot == FIRST_SLOT + CHUNK_SIZE * 2 - 1 || slot == FIRST_SLOT + CHUNK_SIZE * 3 - 1) {
                            pendingWindowFinished.countDown()
                        }
                    },
                )
                val chunks = mutableListOf<LeaderLogCalculator.ChunkResult>()
                val calculation = async(start = CoroutineStart.UNDISPATCHED) {
                    harness.calculate(FIRST_SLOT, CHUNK_SIZE * 6 + 1) { chunk ->
                        chunks.add(chunk)
                        if (chunk.firstSlot == FIRST_SLOT) {
                            sinkEntered.complete(Unit)
                            releaseSink.await()
                        }
                    }
                }
                try {
                    sinkEntered.await()
                    pendingWindowFinished.awaitSuspending("initial window and replacement completed while sink remains blocked")
                    assertTrue(calculation.isActive)
                    assertEquals(listOf(FIRST_SLOT), chunks.map { it.firstSlot })
                    assertEquals(CHUNK_SIZE * 3, harness.completed.get())
                    assertEquals(CHUNK_SIZE * 3, harness.evaluatedSlots.size)
                    assertTrue(harness.evaluatedSlots.all { it < FIRST_SLOT + CHUNK_SIZE * 3 })
                    sinkBlocked.set(false)
                    releaseSink.complete(Unit)
                    calculation.await()
                    assertAllSlots(chunks, FIRST_SLOT, CHUNK_SIZE * 6 + 1)
                    assertEquals(0, harness.active.get())
                } finally {
                    sinkBlocked.set(false)
                    releaseSink.complete(Unit)
                    calculation.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun `slow first chunk cannot reorder callbacks or advance submitted window`() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            supervisorScope {
                val releaseFirst = CountDownLatch(1)
                val firstEntered = CountDownLatch(1)
                val secondFinished = CountDownLatch(1)
                val harness = harness(
                    4,
                    before = { slot ->
                        if (slot == FIRST_SLOT) {
                            firstEntered.countDown()
                            releaseFirst.awaitBounded("slow first chunk released")
                        }
                    },
                    after = { slot ->
                        if (slot == FIRST_SLOT + CHUNK_SIZE * 2 - 1) secondFinished.countDown()
                    },
                )
                val chunks = mutableListOf<LeaderLogCalculator.ChunkResult>()
                val calculation = async(start = CoroutineStart.UNDISPATCHED) {
                    harness.calculate(FIRST_SLOT, CHUNK_SIZE * 4 + 1) { chunks.add(it) }
                }
                try {
                    firstEntered.awaitSuspending("first chunk blocked in native wrapper")
                    secondFinished.awaitSuspending("second chunk completes before first")
                    assertTrue(chunks.isEmpty(), "a later completed chunk was emitted before the first")
                    assertEquals(CHUNK_SIZE + 1, harness.evaluatedSlots.size)
                    assertEquals(CHUNK_SIZE, harness.completed.get())
                    releaseFirst.countDown()
                    calculation.await()
                    assertAllSlots(chunks, FIRST_SLOT, CHUNK_SIZE * 4 + 1)
                    assertEquals(0, harness.active.get())
                } finally {
                    releaseFirst.countDown()
                    calculation.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun `native worker failure propagates original exception and joins active sibling without callbacks`() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            supervisorScope {
                val sentinel = SentinelFailure("controlled native-worker failure")
                val siblingEntered = CountDownLatch(1)
                val releaseSibling = CountDownLatch(1)
                val failureEntered = CompletableDeferred<Unit>()
                val harness = harness(4, before = { slot ->
                    when (slot) {
                        FIRST_SLOT -> {
                            siblingEntered.awaitBounded("sibling entered before worker failure")
                            failureEntered.complete(Unit)
                            throw sentinel
                        }
                        FIRST_SLOT + CHUNK_SIZE -> {
                            siblingEntered.countDown()
                            releaseSibling.awaitBounded("in-flight sibling native call released")
                        }
                    }
                })
                val chunks = mutableListOf<LeaderLogCalculator.ChunkResult>()
                val calculation = async(start = CoroutineStart.UNDISPATCHED) {
                    harness.calculate(FIRST_SLOT, CHUNK_SIZE * 5) { chunks.add(it) }
                }
                try {
                    failureEntered.await()
                    releaseSibling.countDown()
                    val failure = runCatching { calculation.await() }.exceptionOrNull()
                    assertSame(sentinel, failure)
                    assertTrue(chunks.isEmpty())
                    assertEquals(0, harness.active.get())
                    assertFalse(calculation.isActive)
                    assertTrue(harness.evaluatedSlots.all { it < FIRST_SLOT + CHUNK_SIZE * 2 })
                } finally {
                    releaseSibling.countDown()
                    calculation.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun `sink failure propagates original exception and cancels replacement work`() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            supervisorScope {
                val sentinel = SentinelFailure("controlled sink failure")
                val replacementEntered = CountDownLatch(1)
                val releaseReplacement = CountDownLatch(1)
                val sinkFailed = CompletableDeferred<Unit>()
                val harness = harness(4, before = { slot ->
                    if (slot == FIRST_SLOT + CHUNK_SIZE * 2) {
                        replacementEntered.countDown()
                        releaseReplacement.awaitBounded("replacement native call released after sink failure")
                    }
                })
                val chunks = mutableListOf<Long>()
                val calculation = async(start = CoroutineStart.UNDISPATCHED) {
                    harness.calculate(FIRST_SLOT, CHUNK_SIZE * 5) { chunk ->
                        chunks.add(chunk.firstSlot)
                        replacementEntered.awaitSuspending("replacement active before sink fails")
                        sinkFailed.complete(Unit)
                        throw sentinel
                    }
                }
                try {
                    sinkFailed.await()
                    releaseReplacement.countDown()
                    val failure = runCatching { calculation.await() }.exceptionOrNull()
                    assertSame(sentinel, failure)
                    assertEquals(listOf(FIRST_SLOT), chunks)
                    assertEquals(0, harness.active.get())
                    assertFalse(calculation.isActive)
                    assertTrue(harness.evaluatedSlots.all { it < FIRST_SLOT + CHUNK_SIZE * 3 })
                } finally {
                    releaseReplacement.countDown()
                    calculation.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun `cancelling suspended sink joins active native window with no later callbacks or evaluations`() = runBlocking {
        withTimeout(TIMEOUT_MILLIS) {
            supervisorScope {
                val activeWindow = CountDownLatch(2)
                val releaseWindow = CountDownLatch(1)
                val sinkEntered = CompletableDeferred<Unit>()
                val suspendedSink = CompletableDeferred<Unit>()
                val harness = harness(4, before = { slot ->
                    if (slot == FIRST_SLOT + CHUNK_SIZE || slot == FIRST_SLOT + CHUNK_SIZE * 2) {
                        activeWindow.countDown()
                        releaseWindow.awaitBounded("cancelled in-flight native window released")
                    }
                })
                val chunks = mutableListOf<Long>()
                val calculation = launch(start = CoroutineStart.UNDISPATCHED) {
                    harness.calculate(FIRST_SLOT, CHUNK_SIZE * 6, poolCount = 2) { chunk ->
                        chunks.add(chunk.firstSlot)
                        sinkEntered.complete(Unit)
                        suspendedSink.await()
                    }
                }
                try {
                    sinkEntered.await()
                    activeWindow.awaitSuspending("both CPU workers active while sink is suspended")
                    assertEquals(2, harness.active.get())
                    assertEquals(CHUNK_SIZE * 2 + 2, harness.evaluatedSlots.size)
                    calculation.cancel()
                    // Native code cannot be interrupted: allow the two entered calls to return, then join.
                    releaseWindow.countDown()
                    calculation.join()
                    assertTrue(calculation.isCancelled)
                    assertFalse(calculation.isActive)
                    assertEquals(listOf(FIRST_SLOT), chunks)
                    assertEquals(0, harness.active.get())
                    assertEquals(CHUNK_SIZE * 2 + 2, harness.completed.get())
                    assertEquals(CHUNK_SIZE * 2 + 2, harness.evaluatedSlots.size)
                    // Cancellation must also be checked between pools within the same slot.
                    assertEquals(1, harness.evaluatedSlots.count { it == FIRST_SLOT + CHUNK_SIZE })
                    assertEquals(1, harness.evaluatedSlots.count { it == FIRST_SLOT + CHUNK_SIZE * 2 })
                } finally {
                    calculation.cancel()
                    releaseWindow.countDown()
                    calculation.join()
                }
            }
        }
    }
    // Extra state prevents coroutine stack-trace recovery from copying away exception identity.
    private class SentinelFailure(message: String) : RuntimeException(message) {
        val marker = Any()
    }


    private fun harness(
        visibleProcessors: Int,
        before: (Long) -> Unit = {},
        after: (Long) -> Unit = {},
    ): Harness {
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val completed = AtomicInteger()
        val evaluatedSlots = ConcurrentLinkedQueue<Long>()
        val currentSlot = ThreadLocal<Long>()
        val utils = spyk(BlockUtils(AtomicReference(), "/usr/local/lib/libsodium.so"))
        // Record only the slot identity; both input hashing and certified native VRF calls remain real.
        every { utils.mkInputVRF(any(), any()) } answers {
            currentSlot.set(firstArg())
            callOriginal()
        }
        every { utils.vrfEvalCertified(any(), any()) } answers {
            val slot = requireNotNull(currentSlot.get())
            evaluatedSlots.add(slot)
            val entered = active.incrementAndGet()
            maximumActive.updateAndGet { maxOf(it, entered) }
            try {
                before(slot)
                val result = callOriginal()
                completed.incrementAndGet()
                after(slot)
                result
            } finally {
                active.decrementAndGet()
            }
        }
        return Harness(LeaderLogCalculator(utils, visibleProcessors), active, maximumActive, completed, evaluatedSlots)
    }

    private class Harness(
        private val calculator: LeaderLogCalculator,
        val active: AtomicInteger,
        val maximumActive: AtomicInteger,
        val completed: AtomicInteger,
        val evaluatedSlots: ConcurrentLinkedQueue<Long>,
    ) {
        suspend fun calculate(
            firstSlot: Long,
            slotCount: Int,
            poolCount: Int = 1,
            sink: suspend (LeaderLogCalculator.ChunkResult) -> Unit,
        ) {
            calculator.calculate(
                firstSlot = firstSlot,
                slotCount = slotCount,
                mode = LeaderLogCalculator.Mode.PRAOS,
                decentralization = BigDecimal.ZERO,
                epochNonce = nonce,
                pools = List(poolCount) { pool },
                onChunk = sink,
            )
        }
    }

    private fun assertAllSlots(chunks: List<LeaderLogCalculator.ChunkResult>, firstSlot: Long, slotCount: Int) {
        assertEquals((0 until slotCount step CHUNK_SIZE).map { firstSlot + it }, chunks.map { it.firstSlot })
        val coverage = mutableListOf<Long>()
        chunks.forEach { chunk ->
            assertEquals(minOf(CHUNK_SIZE, slotCount - (chunk.firstSlot - firstSlot).toInt()), chunk.slotCount)
            assertEquals(chunk.slotCount, chunk.electedBySlotAndPool.size)
            assertTrue(chunk.electedBySlotAndPool.all { it }, "real f=1 VRF inputs must elect every slot")
            repeat(chunk.slotCount) { coverage.add(chunk.firstSlot + it) }
        }
        assertEquals((0 until slotCount).map { firstSlot + it }, coverage)
    }

    private fun CountDownLatch.awaitBounded(description: String) {
        assertTrue(await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Timed out waiting for $description")
    }

    private suspend fun CountDownLatch.awaitSuspending(description: String) {
        withContext(Dispatchers.IO) { awaitBounded(description) }
    }

    private companion object {
        const val CHUNK_SIZE = 1024
        const val FIRST_SLOT = 50000L
        const val GATE_TIMEOUT_SECONDS = 20L
        const val TIMEOUT_MILLIS = 60000L

        // Committed, public synthetic key and nonce; no operator keys or mocked VRF eligibility.
        val fixture =
            requireNotNull(LeaderLogCalculatorConcurrencyTest::class.java.getResourceAsStream("/leaderlog/node-schedules.json")).use {
                ObjectMapper().readTree(it)["cases"].first { row -> row["mode"].asText() == "praos" }
            }
        val nonce: ByteArray = fixture["nonceHex"].asText().hexToByteArray()
        val pool = LeaderLogCalculator.PoolInput(
            threshold = CardanoLeaderElection.prepare(
                CardanoLeaderElection.prepareActiveSlotCoefficient(BigDecimal.ONE),
                StakeFraction(BigInteger.ONE, BigInteger.ONE),
                32,
            ),
            vrfSkey = fixture["vrfSkeyHex"].asText().hexToByteArray(),
        )
    }
}
