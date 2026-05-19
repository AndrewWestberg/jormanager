package com.swiftmako.jormanager.tracing

import com.google.common.truth.Truth.assertThat
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborReader
import com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixtures
import java.io.ByteArrayInputStream
import java.time.Instant
import org.junit.jupiter.api.Test

class TraceForwardProtocol3ExtractorTest {
    private val extractor = TraceForwardProtocol3Extractor()

    @Test
    fun decodesNodeStateMetricsAndStartupInfoFromSharedSnapshot() {
        val decoded =
            extractor.decode(
                TracingRawDataPointSnapshot(
                    nodeId = 1L,
                    capturedAt = Instant.now(),
                    dataPoints = TraceForwardFixtures.pinnedNodeStateWithStartupReply().toDataPointsReply().dataPoints,
                )
            )

        assertThat(decoded?.nodeStateMetrics).isEqualTo(
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
        assertThat(decoded?.startupInfo).isEqualTo(
            NodeStartupInfo(
                era = "Conway",
                epochLength = 432_000L,
                slotLength = 1L,
                slotsPerKESPeriod = 129_600L,
            )
        )
    }

    @Test
    fun malformedStartupInfoFailsSafelyWithoutBreakingNodeStateMetrics() {
        val decoded =
            extractor.decode(
                TraceForwardFixtures
                    .msgDataPointsReply(
                        *TraceForwardFixtures.fullNodeStateDataPoints().toTypedArray(),
                        TraceForwardFixtures.dataPoint(
                            NodeStateDataPointDecoder.KEY_NODE_STARTUP_INFO,
                            "{\"epochLength\":\"oops\"}",
                        ),
                    ).toDataPointsReply()
            )

        assertThat(decoded?.nodeStateMetrics?.blockHeight).isEqualTo(7_403_221L)
        assertThat(decoded?.startupInfo).isNull()
    }

    @Test
    fun startupInfoSupportsSuiPrefixedLiveKeys() {
        val decoded =
            extractor.decode(
                TraceForwardFixtures
                    .msgDataPointsReply(
                        TraceForwardFixtures.dataPoint(
                            NodeStateDataPointDecoder.KEY_NODE_STARTUP_INFO,
                            "{\"suiEra\":\"Dijkstra\",\"suiEpochLength\":3600,\"suiSlotLength\":1,\"suiSlotsPerKESPeriod\":129600}"
                        ),
                    ).toDataPointsReply()
            )

        assertThat(decoded?.startupInfo).isEqualTo(
            NodeStartupInfo(
                era = "Dijkstra",
                epochLength = 3_600L,
                slotLength = 1L,
                slotsPerKESPeriod = 129_600L,
            )
        )
    }

    private fun ByteArray.toDataPointsReply(): TraceForwardMessage.DataPointsReply =
        ByteArrayInputStream(this).use { input ->
            val payload = CborReader.createFromInputStream(input).readDataItem() as CborArray
            TraceForwardMessage.DataPointsReply(payload.elementAt(1) as CborArray)
        }
}
