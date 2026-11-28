package com.swiftmako.jormanager.nodeclient.protocols.blockfetch

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.ChainPoint
import com.swiftmako.jormanager.nodeclient.protocols.NetworkChainSession
import com.swiftmako.jormanager.nodeclient.protocols.fixtures.CatchupFixtures as F
import com.swiftmako.jormanager.nodeclient.protocols.mux.Mux
import com.swiftmako.jormanager.nodeclient.protocols.mux.muxByteBufferPool
import com.swiftmako.jormanager.services.ChainIngestionCoordinator
import com.swiftmako.jormanager.utils.CborSpans
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.connection
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.withSettings
import org.slf4j.LoggerFactory

class BlockFetchPipelineTest {
    private val blocks = F.conway(70, 1).blocks

    private fun point(index: Int) = ChainPoint((index + 1).toString(), F.blockHash(blocks[index]).toHexString())

    private fun coordinator(
        adopt: (ChainPoint) -> Unit = {},
        failure: () -> Unit = {}
    ): ChainIngestionCoordinator =
        mock(
            ChainIngestionCoordinator::class.java,
            withSettings().defaultAnswer { call ->
                when (call.method.name) {
                    "adopt" -> {
                        adopt(call.arguments[4] as ChainPoint)
                        null
                    }
                    "rollback" -> 2L
                    "boundaryFailure" -> {
                        failure()
                        null
                    }
                    else -> null
                }
            }
        )

    private class Harness(
        coordinator: ChainIngestionCoordinator,
        maxBatchSize: Int = BlockFetchProtocol.MAX_BATCH_SIZE,
    ) {
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> errors.add(e) })
        val session = NetworkChainSession("pipeline", coordinator, false, 1, 21600)
        val leios = LeiosFetchProtocol({ it == session.generation })
        val fetch = BlockFetchProtocol(session, leios, scope, maxBatchSize = maxBatchSize)

        suspend fun enqueue(point: ChainPoint) {
            session.reserve()
            session.enqueue(point)
        }

        suspend fun request(): ByteArray {
            val buffer = fetch.sendData()
            return try {
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            } finally {
                muxByteBufferPool.recycle(buffer)
            }
        }

        fun receive(bytes: ByteArray) = fetch.receiveData(ByteBuffer.wrap(bytes))

        fun block(
            bytes: ByteArray,
            era: Int = 6,
        ) {
            batch(listOf(bytes), era)
        }

        fun batch(
            items: List<ByteArray>,
            era: Int = 6,
        ) {
            receive(F.startBatch())
            items.forEach { receive(F.blockReply(era, it)) }
            receive(F.batchDone())
        }

        suspend fun close() {
            fetch.shutdown()
            leios.shutdown()
            session.close()
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    private suspend fun entered(latch: CountDownLatch) =
        withContext(Dispatchers.IO) {
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue()
        }

    private suspend fun allPermits(session: NetworkChainSession) {
        withTimeout(3000) { repeat(128) { session.reserve() } }
        assertThat(
            withTimeoutOrNull(100) {
                session.reserve()
                true
            }
        ).isNull()
        repeat(128) { session.release() }
    }

    private fun assertRange(
        bytes: ByteArray,
        first: ChainPoint,
        last: ChainPoint = first,
        firstHash: ByteArray = F.blockHash(blocks[first.slot.toInt() - 1]),
        lastHash: ByteArray = F.blockHash(blocks[last.slot.toInt() - 1]),
    ) {
        val request = CborSpans.parse(bytes).array()
        assertThat(request[0].integer().toInt()).isEqualTo(0)
        assertThat(request[1].bytes()).isEqualTo(F.point(first.slot.toLong(), firstHash))
        assertThat(request[2].bytes()).isEqualTo(F.point(last.slot.toLong(), lastHash))
    }

    @Test
    fun prefetchIsBoundedAndAdoptionsStayOrdered() =
        runBlocking {
            withTimeout(10000) {
                val started = CountDownLatch(1)
                val release = CountDownLatch(1)
                val finished = CountDownLatch(3)
                val adopted = Collections.synchronizedList(mutableListOf<ChainPoint>())
                val h = Harness(
                    coordinator({ p ->
                        if (p == point(0)) {
                            started.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                        adopted.add(p)
                        finished.countDown()
                    })
                )
                try {
                    h.enqueue(point(0))
                    assertRange(h.request(), point(0))
                    h.block(blocks[0])
                    entered(started)
                    h.enqueue(point(1))
                    assertRange(h.request(), point(1))
                    h.block(blocks[1])
                    h.enqueue(point(2))
                    val third = async(Dispatchers.IO) { h.request() }
                    assertThat(withTimeoutOrNull(150) { third.await() }).isNull()
                    release.countDown()
                    assertRange(third.await(), point(2))
                    h.block(blocks[2])
                    entered(finished)
                    assertThat(adopted.toList()).containsExactly(point(0), point(1), point(2)).inOrder()
                    allPermits(h.session)
                    assertThat(h.errors).isEmpty()
                } finally {
                    release.countDown()
                    h.close()
                }
            }
        }

    @Test
    fun drainsUpTo64ContiguousHeadersPerRangeAndAdoptsInExactOrder() =
        runBlocking {
            withTimeout(10000) {
                val finished = CountDownLatch(70)
                val adopted = Collections.synchronizedList(mutableListOf<ChainPoint>())
                val h = Harness(
                    coordinator({ p ->
                        adopted.add(p)
                        finished.countDown()
                    })
                )
                try {
                    repeat(70) { h.enqueue(point(it)) }
                    assertRange(h.request(), point(0), point(63))
                    h.batch(blocks.subList(0, 64))
                    assertRange(h.request(), point(64), point(69))
                    h.batch(blocks.subList(64, 70))
                    entered(finished)
                    assertThat(adopted.toList()).containsExactlyElementsIn((0 until 70).map(::point)).inOrder()
                    allPermits(h.session)
                    assertThat(h.errors).isEmpty()
                } finally {
                    h.close()
                }
            }
        }

    @Test
    fun multiBlockBatchPreservesLeiosClosureResolutionAndSequentialAdopt() =
        runBlocking {
            withTimeout(10000) {
                kotlinx.coroutines.supervisorScope {
                    val leiosFixture = F.leios(2)
                    val firstPoint = ChainPoint("1", F.blockHash(leiosFixture.blocks[0]).toHexString())
                    val secondPoint = ChainPoint("2", F.blockHash(leiosFixture.blocks[1]).toHexString())
                    val endorsement = requireNotNull(leiosFixture.endorsement)
                    val endorsementHash = com.swiftmako.jormanager.utils.Blake2b
                        .hash256(endorsement)
                    val adopted = Collections.synchronizedList(mutableListOf<Pair<ChainPoint, Boolean>>())
                    val finished = CountDownLatch(2)
                    var firstAdopted = false

                    val coordinator =
                        mock(
                            ChainIngestionCoordinator::class.java,
                            withSettings().defaultAnswer { call ->
                                when (call.method.name) {
                                    "announcedClosure" -> {
                                        check(firstAdopted) { "Predecessor must be adopted before resolving Leios closure" }
                                        ChainIngestionCoordinator.AnnouncedClosure(1L, endorsementHash, endorsement.size.toLong())
                                    }
                                    "adopt" -> {
                                        val p = call.arguments[4] as ChainPoint
                                        val closure = call.arguments[5]
                                        if (p == firstPoint) firstAdopted = true
                                        adopted.add(p to (closure != null))
                                        finished.countDown()
                                        null
                                    }
                                    else -> null
                                }
                            }
                        )

                    val selector = ActorSelectorManager(Dispatchers.IO)
                    ServerSocket(0).use { server ->
                        val socket = aSocket(selector).tcp().connect(InetSocketAddress("127.0.0.1", server.localPort))
                        val peer = withContext(Dispatchers.IO) { server.accept().apply { soTimeout = 3000 } }
                        val mux = Mux(socket.connection())
                        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                        val session = NetworkChainSession("leios-batch", coordinator, false, 1, 21600)
                        val leios = LeiosFetchProtocol({ it == session.generation })
                        val fetch = BlockFetchProtocol(session, leios, scope)
                        session.reserve()
                        session.enqueue(firstPoint)
                        session.reserve()
                        session.enqueue(secondPoint)
                        val running = async(Dispatchers.IO) { runCatching { mux.execute(fetch, leios) } }
                        try {
                            withContext(Dispatchers.IO) {
                                val input = DataInputStream(peer.getInputStream())
                                val output = DataOutputStream(peer.getOutputStream())

                                input.readInt()
                                assertThat(input.readUnsignedShort()).isEqualTo(3)
                                val rangeBytes = ByteArray(input.readUnsignedShort()).also(input::readFully)
                                assertRange(
                                    rangeBytes,
                                    firstPoint,
                                    secondPoint,
                                    F.blockHash(leiosFixture.blocks[0]),
                                    F.blockHash(leiosFixture.blocks[1]),
                                )

                                for (payload in listOf(
                                    F.startBatch(),
                                    F.blockReply(7, leiosFixture.blocks[0]),
                                    F.blockReply(7, leiosFixture.blocks[1]),
                                    F.batchDone(),
                                )) {
                                    output.writeInt(0)
                                    output.writeShort(0x8003)
                                    output.writeShort(payload.size)
                                    output.write(payload)
                                    output.flush()
                                }

                                // Serve Leios EB body (protocol 19, message 1 -> 2)
                                input.readInt()
                                assertThat(input.readUnsignedShort()).isEqualTo(19)
                                input.readFully(ByteArray(input.readUnsignedShort()))
                                val ebReply = F.array(F.integer(2), endorsement)
                                output.writeInt(0)
                                output.writeShort(0x8013)
                                output.writeShort(ebReply.size)
                                output.write(ebReply)
                                output.flush()

                                // Serve Leios transactions (protocol 19, message 3 -> 4)
                                input.readInt()
                                assertThat(input.readUnsignedShort()).isEqualTo(19)
                                input.readFully(ByteArray(input.readUnsignedShort()))
                                val txReply = F.array(F.integer(4), F.array(leiosFixture.transactions.map(F::tagged24)))
                                output.writeInt(0)
                                output.writeShort(0x8013)
                                output.writeShort(txReply.size)
                                output.write(txReply)
                                output.flush()
                            }
                            entered(finished)
                            assertThat(adopted.toList()).containsExactly(firstPoint to false, secondPoint to true).inOrder()
                            mux.shutdownGracefully()
                            running.cancelAndJoin()
                            allPermits(session)
                        } finally {
                            mux.shutdownGracefully()
                            peer.close()
                            socket.close()
                            fetch.shutdown()
                            leios.shutdown()
                            session.close()
                            scope.cancel()
                            selector.close()
                            running.cancelAndJoin()
                        }
                    }
                }
            }
        }

    @Test
    fun stalePrefetchIsDiscardedAndCurrentGenerationContinues() =
        runBlocking {
            withTimeout(10000) {
                val started = CountDownLatch(1)
                val release = CountDownLatch(1)
                val continued = CountDownLatch(2)
                val adopted = Collections.synchronizedList(mutableListOf<ChainPoint>())
                val h = Harness(
                    coordinator({ p ->
                        if (p == point(0)) {
                            started.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                        adopted.add(p)
                        if (p == point(4) || p == point(5)) continued.countDown()
                    })
                )
                try {
                    h.enqueue(point(0))
                    h.request()
                    h.block(blocks[0])
                    entered(started)
                    // Prefetch a 2-block batch on generation 1, plus 1 queued stale point in session
                    h.enqueue(point(1))
                    h.enqueue(point(2))
                    assertRange(h.request(), point(1), point(2))
                    h.batch(listOf(blocks[1], blocks[2]))
                    h.enqueue(point(3))
                    // Rollback advances generation and drains stale queued point(3)
                    h.session.rollback(null)
                    h.enqueue(point(4))
                    h.enqueue(point(5))
                    release.countDown()
                    assertRange(h.request(), point(4), point(5))
                    h.batch(listOf(blocks[4], blocks[5]))
                    entered(continued)
                    assertThat(adopted.toList()).containsExactly(point(0), point(4), point(5)).inOrder()
                    allPermits(h.session)
                    assertThat(h.errors).isEmpty()
                } finally {
                    release.countDown()
                    h.close()
                }
            }
        }

    @Test
    fun shutdownReleasesInflightAndQueuedOwnershipExactlyOnce() =
        runBlocking {
            withTimeout(10000) {
                val inflight = Harness(coordinator())
                try {
                    repeat(4) { inflight.enqueue(point(it)) }
                    assertRange(inflight.request(), point(0), point(3))
                    // Also leave 2 unconsumed headers in session queue before closing
                    inflight.enqueue(point(4))
                    inflight.enqueue(point(5))
                    inflight.close()
                    inflight.batch(blocks.subList(0, 4))
                    allPermits(inflight.session)
                } finally {
                    inflight.close()
                }
                val started = CountDownLatch(1)
                val release = CountDownLatch(1)
                val queued = Harness(
                    coordinator({ p ->
                        if (p == point(0)) {
                            started.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                    })
                )
                try {
                    queued.enqueue(point(0))
                    queued.enqueue(point(1))
                    queued.request()
                    queued.batch(listOf(blocks[0], blocks[1]))
                    entered(started)
                    queued.enqueue(point(2))
                    queued.enqueue(point(3))
                    queued.request()
                    queued.batch(listOf(blocks[2], blocks[3]))
                    queued.fetch.shutdown()
                    release.countDown()
                    queued.close()
                    allPermits(queued.session)
                    assertThat(queued.errors).isEmpty()
                } finally {
                    release.countDown()
                    queued.close()
                }
            }
        }

    @Test
    fun malformedTruncatedAndUnavailableRepliesNeverAdoptAndAlwaysConservePermits() =
        runBlocking {
            withTimeout(10000) {
                val logger = LoggerFactory.getLogger("LedgerDao") as Logger
                val logs = ListAppender<ILoggingEvent>().apply { start() }
                logger.addAppender(logs)
                try {
                    for (kind in listOf("unexpected", "duplicate", "empty", "truncated", "unavailable")) {
                        val adopted = Collections.synchronizedList(mutableListOf<ChainPoint>())
                        val failed = CountDownLatch(1)
                        val h = Harness(coordinator({ adopted.add(it) }, { failed.countDown() }))
                        try {
                            val count = if (kind in setOf("truncated", "unavailable")) 3 else 1
                            repeat(count) { h.enqueue(point(it)) }
                            assertRange(h.request(), point(0), point(count - 1))
                            when (kind) {
                                "unexpected" -> assertThat(runCatching { h.receive(F.batchDone()) }.isFailure).isTrue()
                                "duplicate" -> {
                                    h.receive(F.startBatch())
                                    h.receive(F.blockReply(6, blocks[0]))
                                    assertThat(runCatching { h.receive(F.blockReply(6, blocks[0])) }.isFailure).isTrue()
                                }
                                "empty" -> {
                                    h.receive(F.startBatch())
                                    h.receive(F.batchDone())
                                    entered(failed)
                                }
                                "truncated" -> {
                                    h.receive(F.startBatch())
                                    h.receive(F.blockReply(6, blocks[0]))
                                    h.receive(F.blockReply(6, blocks[1]))
                                    h.receive(F.batchDone())
                                    entered(failed)
                                }
                                else -> {
                                    h.receive(F.array(F.integer(3)))
                                    entered(failed)
                                }
                            }
                            assertThat(adopted).isEmpty()
                        } finally {
                            h.close()
                            allPermits(h.session)
                        }
                    }
                    assertThat(logs.list.filter { it.formattedMessage.startsWith("BlckFetch: Saved block:") }).isEmpty()
                } finally {
                    logger.detachAppender(logs)
                    logs.stop()
                }
            }
        }

    @Test
    fun workerFailureCancelsActualMuxWithoutAnotherHeader() =
        runBlocking {
            withTimeout(10000) {
                val selector = ActorSelectorManager(Dispatchers.IO)
                ServerSocket(0).use { server ->
                    val socket = aSocket(selector).tcp().connect(InetSocketAddress("127.0.0.1", server.localPort))
                    val peer = withContext(Dispatchers.IO) { server.accept().apply { soTimeout = 3000 } }
                    val mux = Mux(socket.connection())
                    val session = NetworkChainSession("pipeline", coordinator({ throw java.sql.SQLException("adoption failed") }), false, 1, 21600)
                    val leios = LeiosFetchProtocol({ it == session.generation })
                    session.reserve()
                    session.enqueue(point(0))
                    val running = async(Dispatchers.IO) {
                        runCatching {
                            coroutineScope {
                                val fetch = BlockFetchProtocol(session, leios, this)
                                try {
                                    mux.execute(fetch)
                                } finally {
                                    fetch.shutdown()
                                }
                            }
                        }.exceptionOrNull()
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            val input = DataInputStream(peer.getInputStream())
                            input.readInt()
                            assertThat(input.readUnsignedShort()).isEqualTo(3)
                            val request = ByteArray(input.readUnsignedShort())
                            input.readFully(request)
                            assertRange(request, point(0))
                            val output = DataOutputStream(peer.getOutputStream())
                            for (payload in listOf(F.startBatch(), F.blockReply(6, blocks[0]), F.batchDone())) {
                                output.writeInt(0)
                                output.writeShort(0x8003)
                                output.writeShort(payload.size)
                                output.write(payload)
                                output.flush()
                            }
                        }
                        val failure = running.await()
                        assertThat(failure).isNotNull()
                        assertThat(generateSequence(failure) { it.cause }.any { it is java.sql.SQLException }).isTrue()
                        allPermits(session)
                    } finally {
                        mux.shutdownGracefully()
                        peer.close()
                        socket.close()
                        leios.shutdown()
                        session.close()
                        selector.close()
                        running.cancelAndJoin()
                    }
                }
            }
        }
}
