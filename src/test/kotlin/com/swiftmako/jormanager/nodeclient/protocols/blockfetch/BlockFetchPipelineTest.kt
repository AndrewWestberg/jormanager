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
    private val blocks = F.conway(3, 1).blocks
    private fun point(index: Int) = ChainPoint((index + 1).toString(), F.blockHash(blocks[index]).toHexString())
    private fun coordinator(adopt: (ChainPoint) -> Unit = {}, failure: () -> Unit = {}): ChainIngestionCoordinator =
        mock(ChainIngestionCoordinator::class.java, withSettings().defaultAnswer { call ->
            when (call.method.name) {
                "adopt" -> { adopt(call.arguments[4] as ChainPoint); null }
                "rollback" -> 2L
                "boundaryFailure" -> { failure(); null }
                else -> null
            }
        })

    private class Harness(coordinator: ChainIngestionCoordinator) {
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> errors.add(e) })
        val session = NetworkChainSession("pipeline", coordinator, false, 1, 21600)
        val leios = LeiosFetchProtocol({ it == session.generation })
        val fetch = BlockFetchProtocol(session, leios, scope)
        suspend fun enqueue(point: ChainPoint) { session.reserve(); session.enqueue(point) }
        suspend fun request(): ByteArray {
            val buffer = fetch.sendData()
            return try { ByteArray(buffer.remaining()).also { buffer.get(it) } } finally { muxByteBufferPool.recycle(buffer) }
        }
        fun receive(bytes: ByteArray) = fetch.receiveData(ByteBuffer.wrap(bytes))
        fun block(bytes: ByteArray) { receive(F.startBatch()); receive(F.blockReply(6, bytes)); receive(F.batchDone()) }
        suspend fun close() {
            fetch.shutdown(); leios.shutdown(); session.close()
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    private suspend fun entered(latch: CountDownLatch) = withContext(Dispatchers.IO) {
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue()
    }

    private suspend fun allPermits(session: NetworkChainSession) {
        withTimeout(3000) { repeat(128) { session.reserve() } }
        assertThat(withTimeoutOrNull(100) { session.reserve(); true }).isNull()
        repeat(128) { session.release() }
    }

    private fun assertSingleton(bytes: ByteArray, point: ChainPoint) {
        val request = CborSpans.parse(bytes).array()
        assertThat(request[0].integer().toInt()).isEqualTo(0)
        assertThat(request[1].bytes()).isEqualTo(F.point(point.slot.toLong(), F.blockHash(blocks[point.slot.toInt() - 1])))
        assertThat(request[2].bytes()).isEqualTo(request[1].bytes())
    }

    @Test
    fun prefetchIsBoundedAndAdoptionsStayOrdered() = runBlocking {
        withTimeout(10000) {
            val started = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(3)
            val adopted = Collections.synchronizedList(mutableListOf<ChainPoint>())
            val h = Harness(coordinator({ p ->
                if (p == point(0)) { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                adopted.add(p); finished.countDown()
            }))
            try {
                repeat(3) { h.enqueue(point(it)) }
                assertSingleton(h.request(), point(0)); h.block(blocks[0]); entered(started)
                assertSingleton(h.request(), point(1)); h.block(blocks[1])
                val third = async(Dispatchers.IO) { h.request() }
                assertThat(withTimeoutOrNull(150) { third.await() }).isNull()
                release.countDown()
                assertSingleton(third.await(), point(2)); h.block(blocks[2]); entered(finished)
                assertThat(adopted.toList()).containsExactly(point(0), point(1), point(2)).inOrder()
                allPermits(h.session)
                assertThat(h.errors).isEmpty()
            } finally { release.countDown(); h.close() }
        }
    }

    @Test
    fun stalePrefetchIsDiscardedAndCurrentGenerationContinues() = runBlocking {
        withTimeout(10000) {
            val started = CountDownLatch(1); val release = CountDownLatch(1); val continued = CountDownLatch(1)
            val adopted = Collections.synchronizedList(mutableListOf<ChainPoint>())
            val h = Harness(coordinator({ p ->
                if (p == point(0)) { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                adopted.add(p); if (p == point(2)) continued.countDown()
            }))
            try {
                h.enqueue(point(0)); h.enqueue(point(1))
                h.request(); h.block(blocks[0]); entered(started)
                h.request(); h.block(F.array())
                h.session.rollback(null)
                h.enqueue(point(2)); release.countDown()
                assertSingleton(h.request(), point(2)); h.block(blocks[2]); entered(continued)
                assertThat(adopted.toList()).containsExactly(point(0), point(2)).inOrder()
                allPermits(h.session)
                assertThat(h.errors).isEmpty()
            } finally { release.countDown(); h.close() }
        }
    }

    @Test
    fun shutdownReleasesInflightAndQueuedOwnershipExactlyOnce() = runBlocking {
        withTimeout(10000) {
            val inflight = Harness(coordinator())
            try {
                inflight.enqueue(point(0)); inflight.request(); inflight.close()
                inflight.block(blocks[0])
                allPermits(inflight.session)
            } finally { inflight.close() }
            val started = CountDownLatch(1); val release = CountDownLatch(1)
            val queued = Harness(coordinator({ started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }))
            try {
                queued.enqueue(point(0)); queued.enqueue(point(1))
                queued.request(); queued.block(blocks[0]); entered(started)
                queued.request(); queued.block(blocks[1]); queued.fetch.shutdown()
                release.countDown(); queued.close(); allPermits(queued.session)
                assertThat(queued.errors).isEmpty()
            } finally { release.countDown(); queued.close() }
        }
    }

    @Test
    fun malformedAndUnavailableRepliesNeverAdoptOrLogSuccess() = runBlocking {
        withTimeout(10000) {
            val logger = LoggerFactory.getLogger("LedgerDao") as Logger
            val logs = ListAppender<ILoggingEvent>().apply { start() }; logger.addAppender(logs)
            try {
                for (kind in listOf("unexpected", "duplicate", "empty", "unavailable")) {
                    val adopted = Collections.synchronizedList(mutableListOf<ChainPoint>())
                    val failed = CountDownLatch(1)
                    val h = Harness(coordinator({ adopted.add(it) }, { failed.countDown() }))
                    try {
                        h.enqueue(point(0)); h.request()
                        when (kind) {
                            "unexpected" -> assertThat(runCatching { h.receive(F.batchDone()) }.isFailure).isTrue()
                            "duplicate" -> {
                                h.receive(F.startBatch()); h.receive(F.blockReply(6, blocks[0]))
                                assertThat(runCatching { h.receive(F.blockReply(6, blocks[0])) }.isFailure).isTrue()
                            }
                            "empty" -> { h.receive(F.startBatch()); h.receive(F.batchDone()); entered(failed) }
                            else -> { h.receive(F.array(F.integer(3))); entered(failed) }
                        }
                        assertThat(adopted).isEmpty()
                    } finally { h.close() }
                }
                assertThat(logs.list.filter { it.formattedMessage.startsWith("BlckFetch: Saved block:") }).isEmpty()
            } finally { logger.detachAppender(logs); logs.stop() }
        }
    }

    @Test
    fun workerFailureCancelsActualMuxWithoutAnotherHeader() = runBlocking {
        withTimeout(10000) {
            val selector = ActorSelectorManager(Dispatchers.IO)
            ServerSocket(0).use { server ->
                val socket = aSocket(selector).tcp().connect(InetSocketAddress("127.0.0.1", server.localPort))
                val peer = withContext(Dispatchers.IO) { server.accept().apply { soTimeout = 3000 } }
                val mux = Mux(socket.connection())
                val session = NetworkChainSession("pipeline", coordinator({ throw java.sql.SQLException("adoption failed") }), false, 1, 21600)
                val leios = LeiosFetchProtocol({ it == session.generation })
                session.reserve(); session.enqueue(point(0))
                val running = async(Dispatchers.IO) {
                    runCatching { coroutineScope {
                        val fetch = BlockFetchProtocol(session, leios, this)
                        try { mux.execute(fetch) } finally { fetch.shutdown() }
                    } }.exceptionOrNull()
                }
                try {
                    withContext(Dispatchers.IO) {
                        val input = DataInputStream(peer.getInputStream())
                        input.readInt(); assertThat(input.readUnsignedShort()).isEqualTo(3)
                        val request = ByteArray(input.readUnsignedShort()); input.readFully(request)
                        assertSingleton(request, point(0))
                        val output = DataOutputStream(peer.getOutputStream())
                        for (payload in listOf(F.startBatch(), F.blockReply(6, blocks[0]), F.batchDone())) {
                            output.writeInt(0); output.writeShort(0x8003); output.writeShort(payload.size); output.write(payload); output.flush()
                        }
                    }
                    val failure = running.await()
                    assertThat(failure).isNotNull()
                    assertThat(generateSequence(failure) { it.cause }.any { it is java.sql.SQLException }).isTrue()
                } finally {
                    mux.shutdownGracefully(); peer.close(); socket.close(); leios.shutdown(); session.close(); selector.close()
                    running.cancelAndJoin()
                }
            }
        }
    }
}
