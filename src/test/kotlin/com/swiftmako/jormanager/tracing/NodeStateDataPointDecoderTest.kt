package com.swiftmako.jormanager.tracing

import com.google.common.truth.Truth.assertThat
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborReader
import com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixtures
import java.io.ByteArrayInputStream
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

        assertThat(decoder.decode(reply)).isEqualTo(
            NodeStateMetrics(
                peers = 12,
                incomingPeers = 7,
                blockHeight = 7_403_221L,
                remainingKESPeriods = 36,
                epoch = 490L,
                slot = 7_403_221L,
                slotInEpoch = 321L,
                txsProcessed = 0L,
            )
        )
    }

    @Test
    fun nothingValueFailsSafely() {
        val reply = TraceForwardFixtures.nothingNodeStateValueReply().toDataPointsReply()

        assertThat(decoder.decode(reply)).isEqualTo(
            NodeStateMetrics(
                peers = 12,
                incomingPeers = 7,
                blockHeight = 7_403_221L,
                remainingKESPeriods = 36,
                epoch = 490L,
                slot = 0L,
                slotInEpoch = 321L,
                txsProcessed = 123_456L,
            )
        )
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
    fun legacyPrefixedReplyStillDecodesSafely() {
        val reply = TraceForwardFixtures.legacyPrefixedNodeStateReply().toDataPointsReply()

        assertThat(decoder.decode(reply)).isEqualTo(
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
    fun nodeAddBlockReplySuppliesFallbackStateWhenScalarMetricsAreAbsent() {
        val reply = TraceForwardFixtures.nodeAddBlockOnlyReply().toDataPointsReply()

        assertThat(decoder.decode(reply)).isNull()
    }

    private fun fullReply(): TraceForwardMessage.DataPointsReply = TraceForwardFixtures.pinnedNodeStateReply().toDataPointsReply()

    private fun ByteArray.toDataPointsReply(): TraceForwardMessage.DataPointsReply =
        ByteArrayInputStream(this).use { input ->
            val payload = CborReader.createFromInputStream(input).readDataItem() as CborArray
            TraceForwardMessage.DataPointsReply(payload.elementAt(1) as CborArray)
        }
}
