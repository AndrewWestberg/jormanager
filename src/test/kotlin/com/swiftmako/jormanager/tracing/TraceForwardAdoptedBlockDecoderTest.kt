package com.swiftmako.jormanager.tracing

import com.google.common.truth.Truth.assertThat
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborReader
import com.google.iot.cbor.CborObject
import com.google.iot.cbor.CborWriter
import com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixtures
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class TraceForwardAdoptedBlockDecoderTest {
    private val decoder = TraceForwardAdoptedBlockDecoder()

    @Test
    fun decodesValidForwardedAdoptedBlockTraceObject() {
        val event = decoder.decode(TraceForwardFixtures.adoptedBlockTraceObject().traceObjectJson)

        assertThat(event).isEqualTo(
            ForwardedBlockEvent(
                slot = 7_403_221L,
                blockHash = "6dc4f778bf6ff15f8f3c7c3d98e6c6c8321df6e3e97e2cb7f1f1d6ca0b5c4abc",
                timestamp = "2026-05-12T00:00:00Z",
                hostname = "core-node-1",
                status = "completed",
            )
        )
    }

    @Test
    fun decodesValidForwardedForgedBlockTraceObject() {
        val event = decoder.decode(TraceForwardFixtures.forgedBlockTraceObject().traceObjectJson)

        assertThat(event).isEqualTo(
            ForwardedBlockEvent(
                slot = 7_403_221L,
                blockHash = "6dc4f778bf6ff15f8f3c7c3d98e6c6c8321df6e3e97e2cb7f1f1d6ca0b5c4abc",
                timestamp = "2026-05-12T00:00:00Z",
                hostname = "core-node-1",
                status = "created",
            )
        )
    }

    @Test
    fun decodesLiveLoopNamespacedForwardedBlockTraceObjects() {
        val adopted = decoder.decode(TraceForwardFixtures.adoptedBlockTraceObject().traceObjectJson)
        val forged = decoder.decode(TraceForwardFixtures.forgedBlockTraceObject().traceObjectJson)

        assertThat(adopted?.status).isEqualTo("completed")
        assertThat(adopted?.blockHash).isEqualTo("6dc4f778bf6ff15f8f3c7c3d98e6c6c8321df6e3e97e2cb7f1f1d6ca0b5c4abc")
        assertThat(forged?.status).isEqualTo("created")
        assertThat(forged?.blockHash).isEqualTo("6dc4f778bf6ff15f8f3c7c3d98e6c6c8321df6e3e97e2cb7f1f1d6ca0b5c4abc")
    }

    @Test
    fun ignoresUnknownNamespace() {
        val event = decoder.decode(TraceForwardFixtures.unknownNamespaceTraceObject().traceObjectJson)

        assertThat(event).isNull()
    }

    @Test
    fun ignoresMalformedMachineJson() {
        val event = decoder.decode(TraceForwardFixtures.malformedMachineJsonTraceObject().traceObjectJson)

        assertThat(event).isNull()
    }

    @Test
    fun ignoresMissingRequiredFields() {
        val event = decoder.decode(TraceForwardFixtures.missingBlockHashTraceObject().traceObjectJson)

        assertThat(event).isNull()
    }

    @Test
    fun ignoresWrongMachineKind() {
        val event = decoder.decode(TraceForwardFixtures.wrongKindTraceObject().traceObjectJson)

        assertThat(event).isNull()
    }

    @Test
    fun ignoresNonNumericSlot() {
        val event = decoder.decode(TraceForwardFixtures.nonNumericSlotTraceObject().traceObjectJson)

        assertThat(event).isNull()
    }

    @Test
    fun ignoresWrapperOnlyBlockHash() {
        val event = decoder.decode(TraceForwardFixtures.wrapperOnlyBlockHashTraceObject().traceObjectJson)

        assertThat(event).isNull()
    }

    @Test
    fun ignoresForgedTraceObjectWithoutMachineBlockField() {
        val event = decoder.decode(TraceForwardFixtures.forgedBlockHashOnlyTraceObject().traceObjectJson)

        assertThat(event).isNull()
    }

    @Test
    fun ignoresOptionalMachineFieldsWhenDecoding() {
        val event = decoder.decode(TraceForwardFixtures.adoptedBlockTraceObject().traceObjectJson)

        assertThat(event?.slot).isEqualTo(7_403_221L)
        assertThat(event?.blockHash).isEqualTo("6dc4f778bf6ff15f8f3c7c3d98e6c6c8321df6e3e97e2cb7f1f1d6ca0b5c4abc")
    }

    @Test
    fun decodesForwardedAdoptedBlockFromNonEmptyTraceObjectsReply() {
        val replies =
            captureTraceObjectReplies(
                listOf(
                    TraceForwardFixtures.msgTraceObjectsReply(TraceForwardFixtures.adoptedBlockTraceObject()),
                    TraceForwardFixtures.msgDone(),
                )
            )

        val events = decoder.decode(replies.single())

        assertThat(events).containsExactly(
            ForwardedBlockEvent(
                slot = 7_403_221L,
                blockHash = "6dc4f778bf6ff15f8f3c7c3d98e6c6c8321df6e3e97e2cb7f1f1d6ca0b5c4abc",
                timestamp = "2026-05-12T00:00:00Z",
                hostname = "core-node-1",
                status = "completed",
            )
        )
    }

    @Test
    fun toleratesSocketCloseAfterTraceObjectsReplyWithoutDone() {
        val replies =
            captureTraceObjectReplies(
                listOf(
                    TraceForwardFixtures.msgTraceObjectsReply(TraceForwardFixtures.adoptedBlockTraceObject())
                )
            )

        val events = decoder.decode(replies.single())

        assertThat(events).containsExactly(
            ForwardedBlockEvent(
                slot = 7_403_221L,
                blockHash = "6dc4f778bf6ff15f8f3c7c3d98e6c6c8321df6e3e97e2cb7f1f1d6ca0b5c4abc",
                timestamp = "2026-05-12T00:00:00Z",
                hostname = "core-node-1",
                status = "completed",
            )
        )
    }

    @Test
    fun socketRunnerUsesOneConnectionWithHandshakeAndThreeConcurrentProtocols() =
        runBlocking {
            val observed = AtomicReference<List<ObservedMuxFrame>>(emptyList())
            val completion = CountDownLatch(1)

            ServerSocket(0).use { serverSocket ->
                val serverThread =
                    thread(start = true, isDaemon = true, name = "trace-forward-connection-runner-test-server") {
                        try {
                            serverSocket.accept().use { socket ->
                                observed.set(readObservedFrames(socket))
                                completion.countDown()
                            }
                        } catch (_: Throwable) {
                            completion.countDown()
                        }
                    }

                val failure =
                    runCatching {
                        SocketTraceForwardConnectionRunner().runConnection("127.0.0.1", serverSocket.localPort) { }
                    }.exceptionOrNull()

                check(completion.await(5, TimeUnit.SECONDS)) { "Timed out waiting for socket runner proof" }
                serverThread.join(1_000)
                assertThat(failure).isNotNull()
            }

            assertThat(observed.get()).hasSize(4)
            val frames = observed.get()
            assertThat(frames.map { it.protocolId }).containsExactly(0, 1, 2, 3).inOrder()

            val handshake = frames[0].payload as CborArray
            assertThat(handshake.toJsonString()).isEqualTo(expectedHandshakeProposal().toJsonString())

            val metricsRequest = frames[1].payload as CborArray
            assertThat((metricsRequest.elementAt(0) as CborInteger).longValue()).isEqualTo(0L)
            val metricsRequestBody = metricsRequest.elementAt(1) as CborArray
            assertThat((metricsRequestBody.elementAt(0) as CborInteger).longValue()).isEqualTo(1L)

            val traceRequest = frames[2].payload as CborArray
            assertThat(traceRequest.toJsonString()).isEqualTo(
                CborReader.createFromByteArray(TraceForwardFixtures.msgTraceObjectsRequest(blocking = true, count = 25)).readDataItem().toJsonString()
            )

            val dataPointsRequest = frames[3].payload as CborArray
            assertThat(dataPointsRequest.toJsonString()).isEqualTo(
                CborReader.createFromByteArray(TraceForwardFixtures.msgDataPointsRequest()).readDataItem().toJsonString()
            )
        }

    private fun captureTraceObjectReplies(serverResponses: List<ByteArray>): List<TraceForwardMessage.TraceObjectsReply> =
        runBlocking {
            val replies = mutableListOf<TraceForwardMessage.TraceObjectsReply>()
            val completion = CountDownLatch(1)

            ServerSocket(0).use { serverSocket ->
                val serverThread =
                    thread(start = true, isDaemon = true, name = "trace-forward-decoder-test-server") {
                        try {
                            serverSocket.accept().use { socket ->
                                handleTraceObjectReplyConnection(socket, serverResponses)
                                completion.countDown()
                            }
                        } catch (_: Throwable) {
                            completion.countDown()
                        }
                    }

                runCatching {
                    SocketTraceForwardConnectionRunner().runConnection("127.0.0.1", serverSocket.localPort) { message ->
                        if (message is TraceForwardMessage.TraceObjectsReply) {
                            replies += message
                        }
                    }
                }

                check(completion.await(5, TimeUnit.SECONDS)) { "Timed out waiting for decoder trace replies" }
                serverThread.join(1_000)
            }

            replies
        }

    private fun handleTraceObjectReplyConnection(socket: Socket, serverResponses: List<ByteArray>) {
        socket.soTimeout = 2_000
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        val handshakeHeader = input.readNBytes(8)
        check(handshakeHeader.size == 8) { "Expected handshake mux header" }
        val handshakePayloadLength = ByteBuffer.wrap(handshakeHeader).getShort(6).toInt() and 0xffff
        val handshakePayload = input.readNBytes(handshakePayloadLength)
        check(handshakePayload.size == handshakePayloadLength) { "Expected handshake payload bytes" }
        output.write(muxFrame(0, forwardingAcceptPayload()))
        output.flush()

        repeat(3) {
            val header = input.readNBytes(8)
            check(header.size == 8) { "Expected startup mux header" }
            val headerBuffer = ByteBuffer.wrap(header)
            headerBuffer.int
            val protocolId = headerBuffer.short.toInt() and 0xffff
            val payloadLength = headerBuffer.short.toInt() and 0xffff
            val payload = input.readNBytes(payloadLength)
            check(payload.size == payloadLength) { "Expected payload bytes for protocol $protocolId" }
            if (protocolId == 2) {
                serverResponses.forEach { response ->
                    output.write(muxFrame(0x0002, response))
                }
                output.flush()
            }
        }
    }

    private fun readObservedFrames(socket: Socket): List<ObservedMuxFrame> {
        socket.soTimeout = 2_000
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        val handshakeHeader = input.readNBytes(8)
        check(handshakeHeader.size == 8) { "Expected handshake mux header" }
        val handshakePayloadLength = ByteBuffer.wrap(handshakeHeader).getShort(6).toInt() and 0xffff
        val handshakePayload = input.readNBytes(handshakePayloadLength)
        check(handshakePayload.size == handshakePayloadLength) { "Expected handshake payload bytes" }
        output.write(muxFrame(0, forwardingAcceptPayload()))
        output.flush()

        val remainingFrames = mutableListOf<ObservedMuxFrame>()
        repeat(3) {
            val header = input.readNBytes(8)
            check(header.size == 8) { "Expected mux header for concurrent protocols" }
            val headerBuffer = ByteBuffer.wrap(header)
            headerBuffer.int
            val protocolId = headerBuffer.short.toInt() and 0xffff
            val payloadLength = headerBuffer.short.toInt() and 0xffff
            val payload = input.readNBytes(payloadLength)
            check(payload.size == payloadLength) { "Expected payload bytes for protocol $protocolId" }
            remainingFrames += ObservedMuxFrame(protocolId, CborReader.createFromByteArray(payload).readDataItem())
        }

        return listOf(
            ObservedMuxFrame(0, CborReader.createFromByteArray(handshakePayload).readDataItem())
        ) + remainingFrames
    }

    private fun muxFrame(protocolId: Int, payload: ByteArray): ByteArray {
        val frame = ByteBuffer.allocate(8 + payload.size)
        frame.putInt(0)
        frame.putShort((protocolId xor 0x8000).toShort())
        frame.putShort(payload.size.toShort())
        frame.put(payload)
        return frame.array()
    }

    private fun forwardingAcceptPayload(): ByteArray {
        val buffer = ByteBuffer.allocate(64)
        CborWriter.createFromByteBuffer(buffer).writeDataItem(
            CborArray.create().apply {
                add(CborInteger.create(1L))
                add(CborInteger.create(1L))
                add(CborInteger.create(141L))
            }
        )
        buffer.flip()
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun expectedHandshakeProposal(): CborObject =
        CborReader.createFromByteArray(
            ByteBuffer.allocate(64).apply {
                CborWriter.createFromByteBuffer(this).writeDataItem(
                    CborArray.create().apply {
                        add(CborInteger.create(0L))
                        add(
                            com.google.iot.cbor.CborMap.create(
                                mutableMapOf<CborObject, CborObject>(
                                    CborInteger.create(1L) to CborInteger.create(141L)
                                )
                            )
                        )
                    }
                )
                flip()
            }.let { buffer -> ByteArray(buffer.remaining()).also { buffer.get(it) } }
        ).readDataItem()

    private data class ObservedMuxFrame(
        val protocolId: Int,
        val payload: CborObject,
    )
}
