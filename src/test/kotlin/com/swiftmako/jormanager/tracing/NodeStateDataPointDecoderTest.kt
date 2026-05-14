package com.swiftmako.jormanager.tracing

import com.google.common.truth.Truth.assertThat
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborReader
import com.swiftmako.jormanager.tracing.fixtures.ScriptedTraceForwardServer
import com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixtures
import com.swiftmako.jormanager.tracing.fixtures.TraceForwardSessionScript
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class NodeStateDataPointDecoderTest {
    private val decoder = NodeStateDataPointDecoder()

    @Test
    fun decodesPinnedNodeStateReplyIntoMetrics() {
        val metrics = decoder.decode(fullReply())

        assertThat(metrics).isEqualTo(
            NodeStateMetrics(
                peers = 12,
                incomingPeers = 7,
                blockHeight = 7_403_221L,
                remainingKESPeriods = 36,
                epoch = 490L,
                slot = 7_403_221L,
                slotInEpoch = 321L,
                txsProcessed = 123_456L,
            )
        )
    }

    @Test
    fun pinnedNodeStateMetricsMapToCurrentNodeStatsContract() {
        val nodeStats =
            decoder.decode(fullReply())!!.toNodeStats(
                timestamp = 1_715_468_800_000L,
                nodeName = "core-a",
                color = "#123456",
                isDefault = false,
                epochLength = 432_000L,
            )

        assertThat(nodeStats).isEqualTo(
            com.swiftmako.jormanager.model.NodeStats(
                isDefault = false,
                timestamp = 1_715_468_800_000L,
                nodeName = "core-a",
                color = "#123456",
                peers = 12,
                incomingPeers = 7,
                blockHeight = 7_403_221L,
                remainingKESPeriods = 36,
                epoch = 490L,
                slot = 7_403_221L,
                slotInEpoch = 321L,
                txsProcessed = 123_456L,
                epochLength = 432_000L,
            )
        )
    }

    @Test
    fun missingRequiredKeyFailsSafely() {
        val reply = TraceForwardFixtures.missingNodeStateKeyReply().toDataPointsReply()

        assertThat(decoder.decode(reply)).isNull()
    }

    @Test
    fun nothingValueFailsSafely() {
        val reply = TraceForwardFixtures.nothingNodeStateValueReply().toDataPointsReply()

        assertThat(decoder.decode(reply)).isNull()
    }

    @Test
    fun malformedScalarFailsSafely() {
        val reply = TraceForwardFixtures.malformedNodeStateScalarReply().toDataPointsReply()

        assertThat(decoder.decode(reply)).isNull()
    }

    @Test
    fun overflowingIntFailsSafely() {
        val reply = TraceForwardFixtures.overflowingNodeStateReply().toDataPointsReply()

        assertThat(decoder.decode(reply)).isNull()
    }

    @Test
    fun chunkedByteStringValueDecodesSafely() {
        val reply = TraceForwardFixtures.chunkedNodeStateReply().toDataPointsReply()

        assertThat(decoder.decode(reply)?.slot).isEqualTo(7_403_221L)
    }

    @Test
    fun dataPointSessionRoundTripUsesPinnedRequestAndReply() =
        runBlocking {
            val replies = mutableListOf<TraceForwardMessage.DataPointsReply>()
            val receivedDone = AtomicReference<ByteArray?>(null)
            val failure = AtomicReference<Throwable?>(null)
            val sessionComplete = CountDownLatch(1)

            ServerSocket(0).use { serverSocket ->
                val serverThread =
                    Thread {
                        try {
                            serverSocket.accept().use { socket ->
                                val input = socket.getInputStream()
                                val output = socket.getOutputStream()

                                val expectedRequest = TraceForwardFixtures.pinnedNodeStateRequest()
                                val actualRequest = input.readNBytes(expectedRequest.size)
                                check(actualRequest.contentEquals(expectedRequest)) {
                                    "Unexpected data-point request bytes"
                                }

                                output.write(TraceForwardFixtures.pinnedNodeStateReply())
                                output.flush()

                                val expectedDone = TraceForwardFixtures.msgDone()
                                val actualDone = input.readNBytes(expectedDone.size)
                                receivedDone.set(actualDone)
                                check(actualDone.contentEquals(expectedDone)) {
                                    "Expected client MsgDone after data-point reply"
                                }
                            }
                        } catch (t: Throwable) {
                            failure.set(t)
                        } finally {
                            sessionComplete.countDown()
                        }
                    }
                serverThread.start()

                SocketDataPointSessionClient().runSession("127.0.0.1", serverSocket.localPort) { message ->
                    if (message is TraceForwardMessage.DataPointsReply) {
                        replies += message
                    }
                }

                check(sessionComplete.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting for data-point session server"
                }
                failure.get()?.let { throw AssertionError("Manual data-point session server failed", it) }
            }

            assertThat(decoder.decode(replies.single())?.slot).isEqualTo(7_403_221L)
            assertThat(decoder.decode(replies.single())?.txsProcessed).isEqualTo(123_456L)
            assertThat(receivedDone.get()).isEqualTo(TraceForwardFixtures.msgDone())
        }

    private fun fullReply(): TraceForwardMessage.DataPointsReply =
        TraceForwardFixtures.pinnedNodeStateReply().toDataPointsReply()

    private fun ByteArray.toDataPointsReply(): TraceForwardMessage.DataPointsReply =
        ByteArrayInputStream(this).use { input ->
            val payload = CborReader.createFromInputStream(input).readDataItem() as CborArray
            TraceForwardMessage.DataPointsReply(payload.elementAt(1) as CborArray)
        }
}
