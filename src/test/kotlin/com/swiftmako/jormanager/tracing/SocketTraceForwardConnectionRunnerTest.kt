package com.swiftmako.jormanager.tracing

import com.google.common.truth.Truth.assertThat
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborTextString
import com.google.iot.cbor.CborWriter
import com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixtures
import com.swiftmako.jormanager.tracing.forwarding.ForwardingDataPointsProtocol
import com.swiftmako.jormanager.tracing.forwarding.ForwardingMetricsProtocol
import com.swiftmako.jormanager.tracing.forwarding.ForwardingTraceObjectsProtocol
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.selector.SelectorManager
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

class SocketTraceForwardConnectionRunnerTest {
    companion object {
        private const val NETWORK_MAGIC = 764_824_073L
    }

    @Test
    @Timeout(15)
    fun mergesMetricsDataPointsAndTraceObjectsIntoSingleReactiveStream() =
        runBlocking {
            val clientDisconnected = CountDownLatch(1)
            val selectorClosed = AtomicBoolean(false)

            ServerSocket(0).use { serverSocket ->
                val serverThread =
                    thread(start = true, isDaemon = true, name = "runner-merged-stream-server") {
                        serverSocket.accept().use { socket ->
                            socket.soTimeout = 5_000
                            acceptHandshake(socket)
                            val protocolIds = readStartupProtocolIds(socket, count = 3)
                            check(protocolIds == listOf(1, 2, 3)) { "Unexpected startup protocol ids: $protocolIds" }

                            val output = socket.getOutputStream()
                            output.write(muxFrame(0x0001, metricsReplyPayload("slotNum" to 42L)))
                            output.write(
                                muxFrame(
                                    0x0003,
                                    TraceForwardFixtures.msgDataPointsReply(
                                        TraceForwardFixtures.dataPoint(NodeStateDataPointDecoder.KEY_BLOCK_NUM, "100"),
                                    ),
                                ),
                            )
                            output.write(
                                muxFrame(
                                    0x0002,
                                    TraceForwardFixtures.msgTraceObjectsReply(TraceForwardFixtures.adoptedBlockTraceObject()),
                                ),
                            )
                            output.flush()

                            while (socket.getInputStream().read() != -1) {
                                // Wait for downstream take(3) cancellation to close client socket
                            }
                            clientDisconnected.countDown()
                        }
                    }

                val runner =
                    SocketTraceForwardConnectionRunner(
                        networkMagic = NETWORK_MAGIC,
                        selectorFactory = { trackingSelector(selectorClosed) },
                        createProtocolStreams = { defaultProtocolStreams(enableTraceObjects = true) },
                    )

                val messages =
                    runner
                        .runConnection("127.0.0.1", serverSocket.localPort)
                        .take(3)
                        .toList()

                assertThat(clientDisconnected.await(5, TimeUnit.SECONDS)).isTrue()
                serverThread.join(1_000)
                assertThat(selectorClosed.get()).isTrue()

                val metricsReply = messages.filterIsInstance<TraceForwardMessage.MetricsReply>().single()
                assertThat(metricsReply.metrics).containsEntry("slotNum", TracingRawMetricValue.IntGauge(42L))
                assertThat(messages.filterIsInstance<TraceForwardMessage.DataPointsReply>()).hasSize(1)
                assertThat(messages.filterIsInstance<TraceForwardMessage.TraceObjectsReply>()).hasSize(1)
            }
        }

    @Test
    @Timeout(15)
    fun disablingTraceObjectsRunsOnlyMetricsAndDataPointsProtocols() =
        runBlocking {
            val observedProtocols = AtomicReference<List<Int>>(emptyList())
            val clientDisconnected = CountDownLatch(1)

            ServerSocket(0).use { serverSocket ->
                val serverThread =
                    thread(start = true, isDaemon = true, name = "runner-relay-stream-server") {
                        serverSocket.accept().use { socket ->
                            socket.soTimeout = 5_000
                            acceptHandshake(socket)
                            val protocolIds = readStartupProtocolIds(socket, count = 2)
                            observedProtocols.set(protocolIds)

                            val output = socket.getOutputStream()
                            output.write(muxFrame(0x0001, metricsReplyPayload("peers" to 9L)))
                            output.write(
                                muxFrame(
                                    0x0003,
                                    TraceForwardFixtures.msgDataPointsReply(
                                        TraceForwardFixtures.dataPoint(NodeStateDataPointDecoder.KEY_EPOCH, "500"),
                                    ),
                                ),
                            )
                            output.flush()

                            while (socket.getInputStream().read() != -1) {
                                // Wait for client stream cancellation
                            }
                            clientDisconnected.countDown()
                        }
                    }

                val runner =
                    SocketTraceForwardConnectionRunnerFactory().create(
                        TracingTransportTarget(
                            networkMagic = NETWORK_MAGIC,
                            enableTraceObjects = false,
                        ),
                    )

                val messages =
                    runner
                        .runConnection("127.0.0.1", serverSocket.localPort)
                        .take(2)
                        .toList()

                assertThat(clientDisconnected.await(5, TimeUnit.SECONDS)).isTrue()
                serverThread.join(1_000)
                assertThat(observedProtocols.get()).containsExactly(1, 3).inOrder()
                assertThat(messages.filterIsInstance<TraceForwardMessage.MetricsReply>()).hasSize(1)
                assertThat(messages.filterIsInstance<TraceForwardMessage.DataPointsReply>()).hasSize(1)
            }
        }

    @Test
    @Timeout(15)
    fun cancellingStreamClosesSocketAndReleasesSelectorImmediately() =
        runBlocking {
            val startupReceived = CompletableDeferred<Unit>()
            val clientDisconnected = CountDownLatch(1)
            val selectorClosed = AtomicBoolean(false)

            ServerSocket(0).use { serverSocket ->
                val serverThread =
                    thread(start = true, isDaemon = true, name = "runner-cancel-server") {
                        serverSocket.accept().use { socket ->
                            socket.soTimeout = 5_000
                            acceptHandshake(socket)
                            readStartupProtocolIds(socket, count = 3)
                            startupReceived.complete(Unit)

                            while (socket.getInputStream().read() != -1) {
                                // Hold connection open until client stream cancels
                            }
                            clientDisconnected.countDown()
                        }
                    }

                val runner =
                    SocketTraceForwardConnectionRunner(
                        networkMagic = NETWORK_MAGIC,
                        selectorFactory = { trackingSelector(selectorClosed) },
                        createProtocolStreams = { defaultProtocolStreams(enableTraceObjects = true) },
                    )

                val collectionJob =
                    launch {
                        runner.runConnection("127.0.0.1", serverSocket.localPort).collect { }
                    }

                startupReceived.await()
                assertThat(selectorClosed.get()).isFalse()

                collectionJob.cancelAndJoin()

                assertThat(selectorClosed.get()).isTrue()
                assertThat(clientDisconnected.await(5, TimeUnit.SECONDS)).isTrue()
                serverThread.join(1_000)
            }
        }

    @Test
    @Timeout(15)
    fun handshakeExceptionTerminatesStreamPropagatesErrorAndReleasesResources() =
        runBlocking {
            val selectorClosed = AtomicBoolean(false)

            ServerSocket(0).use { serverSocket ->
                val serverThread =
                    thread(start = true, isDaemon = true, name = "runner-bad-handshake-server") {
                        serverSocket.accept().use { socket ->
                            socket.soTimeout = 5_000
                            acceptHandshake(socket, magic = NETWORK_MAGIC + 1L)
                        }
                    }

                val runner =
                    SocketTraceForwardConnectionRunner(
                        networkMagic = NETWORK_MAGIC,
                        selectorFactory = { trackingSelector(selectorClosed) },
                        createProtocolStreams = { defaultProtocolStreams(enableTraceObjects = true) },
                    )

                val error =
                    assertSuspendThrows<IOException> {
                        runner.runConnection("127.0.0.1", serverSocket.localPort).collect { }
                    }

                serverThread.join(1_000)
                assertThat(error.message).contains("networkMagic")
                assertThat(selectorClosed.get()).isTrue()
            }
        }

    @Test
    @Timeout(15)
    fun multiplexingProtocolExceptionTerminatesStreamAndPropagatesError() =
        runBlocking {
            val selectorClosed = AtomicBoolean(false)

            ServerSocket(0).use { serverSocket ->
                val serverThread =
                    thread(start = true, isDaemon = true, name = "runner-bad-mux-frame-server") {
                        serverSocket.accept().use { socket ->
                            socket.soTimeout = 5_000
                            acceptHandshake(socket)
                            readStartupProtocolIds(socket, count = 3)
                            val invalidDataPointMessage =
                                cborBytes(
                                    CborArray.create().apply {
                                        add(CborInteger.create(99L))
                                    },
                                )
                            socket.getOutputStream().write(muxFrame(0x0003, invalidDataPointMessage))
                            socket.getOutputStream().flush()
                        }
                    }

                val runner =
                    SocketTraceForwardConnectionRunner(
                        networkMagic = NETWORK_MAGIC,
                        selectorFactory = { trackingSelector(selectorClosed) },
                        createProtocolStreams = { defaultProtocolStreams(enableTraceObjects = true) },
                    )

                val error =
                    assertSuspendThrows<IOException> {
                        runner.runConnection("127.0.0.1", serverSocket.localPort).collect { }
                    }

                serverThread.join(1_000)
                assertThat(error.message).contains("Unexpected data-point message id: 99")
                assertThat(selectorClosed.get()).isTrue()
            }
        }

    @Test
    @Timeout(15)
    fun concurrentSubStreamExceptionCancelsMuxAndPropagatesToCollector() =
        runBlocking {
            val startupReceived = CompletableDeferred<Unit>()
            val clientDisconnected = CountDownLatch(1)
            val selectorClosed = AtomicBoolean(false)

            ServerSocket(0).use { serverSocket ->
                val serverThread =
                    thread(start = true, isDaemon = true, name = "runner-substream-error-server") {
                        serverSocket.accept().use { socket ->
                            socket.soTimeout = 5_000
                            acceptHandshake(socket)
                            readStartupProtocolIds(socket, count = 2)
                            startupReceived.complete(Unit)

                            while (socket.getInputStream().read() != -1) {
                                // Keep socket open until sub-stream error tears down client connection
                            }
                            clientDisconnected.countDown()
                        }
                    }

                val runner =
                    SocketTraceForwardConnectionRunner(
                        networkMagic = NETWORK_MAGIC,
                        selectorFactory = { trackingSelector(selectorClosed) },
                        createProtocolStreams = {
                            val metricsProtocol = ForwardingMetricsProtocol(singleReplyMode = false)
                            val dataPointsProtocol =
                                ForwardingDataPointsProtocol(
                                    requestedNames = NodeStateDataPointDecoder.REQUESTED_NAMES,
                                    singleReplyMode = false,
                                )
                            listOf(
                                ProtocolStream(
                                    protocol = metricsProtocol,
                                    messages = metricsProtocol.messages,
                                    closeMessages = metricsProtocol::closeMessages,
                                ),
                                ProtocolStream(
                                    protocol = dataPointsProtocol,
                                    messages =
                                        flow {
                                            startupReceived.await()
                                            throw IllegalStateException("sub-stream boom")
                                        },
                                    closeMessages = dataPointsProtocol::closeMessages,
                                ),
                            )
                        },
                    )

                val error =
                    assertSuspendThrows<IllegalStateException> {
                        runner.runConnection("127.0.0.1", serverSocket.localPort).collect { }
                    }

                assertThat(error.message).isEqualTo("sub-stream boom")
                assertThat(selectorClosed.get()).isTrue()
                assertThat(clientDisconnected.await(5, TimeUnit.SECONDS)).isTrue()
                serverThread.join(1_000)
            }
        }

    @Test
    @Timeout(15)
    fun recollectingColdStreamOpensFreshConnectionAfterFailure() =
        runBlocking {
            ServerSocket(0).use { serverSocket ->
                val serverThread =
                    thread(start = true, isDaemon = true, name = "runner-reconnect-cold-stream-server") {
                        // First session: drop socket right after handshake
                        serverSocket.accept().use { socket ->
                            socket.soTimeout = 5_000
                            acceptHandshake(socket)
                        }

                        // Second session: full handshake + metrics reply
                        serverSocket.accept().use { socket ->
                            socket.soTimeout = 5_000
                            acceptHandshake(socket)
                            readStartupProtocolIds(socket, count = 2)
                            socket.getOutputStream().write(muxFrame(0x0001, metricsReplyPayload("recovered" to 1L)))
                            socket.getOutputStream().flush()
                            while (socket.getInputStream().read() != -1) {
                                // Wait for take(1) cancellation
                            }
                        }
                    }

                val runner =
                    SocketTraceForwardConnectionRunner(
                        networkMagic = NETWORK_MAGIC,
                        enableTraceObjects = false,
                    )
                val stream = runner.runConnection("127.0.0.1", serverSocket.localPort)

                assertSuspendThrows<IOException> {
                    stream.collect { }
                }

                val recoveredMessage = stream.take(1).single()
                serverThread.join(1_000)

                assertThat(recoveredMessage).isInstanceOf(TraceForwardMessage.MetricsReply::class.java)
                val metricsReply = recoveredMessage as TraceForwardMessage.MetricsReply
                assertThat(metricsReply.metrics).containsEntry("recovered", TracingRawMetricValue.IntGauge(1L))
            }
        }

    private fun defaultProtocolStreams(enableTraceObjects: Boolean): List<ProtocolStream> {
        val metricsProtocol = ForwardingMetricsProtocol(singleReplyMode = false)
        val dataPointsProtocol =
            ForwardingDataPointsProtocol(
                requestedNames = NodeStateDataPointDecoder.REQUESTED_NAMES,
                singleReplyMode = false,
            )
        val traceObjectsProtocol =
            if (enableTraceObjects) {
                ForwardingTraceObjectsProtocol(singleReplyMode = false)
            } else {
                null
            }
        return buildList {
            add(ProtocolStream(metricsProtocol, metricsProtocol.messages, metricsProtocol::closeMessages))
            traceObjectsProtocol?.let { add(ProtocolStream(it, it.messages, it::closeMessages)) }
            add(ProtocolStream(dataPointsProtocol, dataPointsProtocol.messages, dataPointsProtocol::closeMessages))
        }
    }

    private suspend inline fun <reified T : Throwable> assertSuspendThrows(block: suspend () -> Unit): T {
        val thrown = runCatching { block() }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(T::class.java)
        return thrown as T
    }

    private fun trackingSelector(closedFlag: AtomicBoolean): SelectorManager {
        val delegate = ActorSelectorManager(Dispatchers.IO)
        return object : SelectorManager by delegate {
            override fun close() {
                closedFlag.set(true)
                delegate.close()
            }
        }
    }

    private fun acceptHandshake(
        socket: Socket,
        magic: Long = NETWORK_MAGIC,
    ) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val header = input.readNBytes(8)
        check(header.size == 8) { "Expected handshake mux header" }
        val payloadLength = ByteBuffer.wrap(header).getShort(6).toInt() and 0xffff
        val payload = input.readNBytes(payloadLength)
        check(payload.size == payloadLength) { "Expected handshake payload" }

        val acceptPayload =
            cborBytes(
                CborArray.create().apply {
                    add(CborInteger.create(1L))
                    add(CborInteger.create(1L))
                    add(CborInteger.create(magic))
                },
            )
        output.write(muxFrame(0x0000, acceptPayload))
        output.flush()
    }

    private fun readStartupProtocolIds(
        socket: Socket,
        count: Int,
    ): List<Int> {
        val input = socket.getInputStream()
        val ids = mutableListOf<Int>()
        repeat(count) {
            val header = input.readNBytes(8)
            check(header.size == 8) { "Expected startup mux header" }
            val headerBuffer = ByteBuffer.wrap(header)
            headerBuffer.int
            val protocolId = headerBuffer.short.toInt() and 0xffff
            val payloadLength = headerBuffer.short.toInt() and 0xffff
            val payload = input.readNBytes(payloadLength)
            check(payload.size == payloadLength) { "Expected startup mux payload for protocol $protocolId" }
            ids += protocolId
        }
        return ids
    }

    private fun metricsReplyPayload(vararg gauges: Pair<String, Long>): ByteArray =
        cborBytes(
            CborArray.create().apply {
                add(CborInteger.create(1L))
                add(
                    CborArray.create().apply {
                        add(CborInteger.create(0L))
                        add(
                            CborArray.create().apply {
                                gauges.forEach { (name, value) ->
                                    add(
                                        CborArray.create().apply {
                                            add(CborTextString.create(name))
                                            add(
                                                CborArray.create().apply {
                                                    add(CborInteger.create(1L))
                                                    add(CborInteger.create(value))
                                                },
                                            )
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            },
        )

    private fun cborBytes(item: com.google.iot.cbor.CborObject): ByteArray {
        val buffer = ByteBuffer.allocate(4096)
        CborWriter.createFromByteBuffer(buffer).writeDataItem(item)
        buffer.flip()
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun muxFrame(
        protocolId: Int,
        payload: ByteArray,
    ): ByteArray {
        val frame = ByteBuffer.allocate(8 + payload.size)
        frame.putInt(0)
        frame.putShort((protocolId xor 0x8000).toShort())
        frame.putShort(payload.size.toShort())
        frame.put(payload)
        return frame.array()
    }
}
