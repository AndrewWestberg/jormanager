package com.swiftmako.jormanager.tracing

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TracingMetricDecoderTest {
    private val decoder = TracingMetricDecoder()

    @Test
    fun decodeBuildsTypedMetricFamiliesFromRawCapture() {
        val snapshot = decoder.decode(fullMetricMap())

        assertThat(snapshot.chain)
            .isEqualTo(
                TracingChainMetrics(
                    blockNum = 7_403_221L,
                    slotNum = 7_403_221L,
                    slotInEpoch = 321L,
                    epoch = 490L,
                    density = 0.55,
                    tipBlock = "hash-123",
                )
            )
        assertThat(snapshot.kes)
            .isEqualTo(
                TracingKesMetrics(
                    operationalCertificateStartKesPeriod = 10L,
                    operationalCertificateExpiryKesPeriod = 72L,
                    currentKesPeriod = 36L,
                    remainingKesPeriods = 36L,
                )
            )
        assertThat(snapshot.forge)
            .isEqualTo(
                TracingForgeMetrics(
                    forgingEnabled = 1L,
                    aboutToLead = 2L,
                    nodeNotLeader = 3L,
                    nodeIsLeader = 4L,
                    forgedSlotLast = 7_403_221L,
                    forged = 5L,
                    adopted = 6L,
                )
            )
        assertThat(snapshot.mempool)
            .isEqualTo(
                TracingMempoolMetrics(
                    txsInMempool = 8L,
                    mempoolBytes = 9L,
                    txsProcessedNum = 123_456L,
                    txsSyncDuration = 10L,
                    txsSyncDurationTotal = 11L,
                    txsMempoolTimeoutSoft = 12L,
                )
            )
    }

    @Test
    fun toNodeStateMetricsReturnsNullWhenCompleteDashboardSubsetIsMissing() {
        val snapshot =
            decoder.decode(
                fullMetricMap().filterKeys {
                    it != TracingMetricDecoder.TXS_PROCESSED_NUM
                }
            )

        assertThat(snapshot.toNodeStateMetrics(peers = 2, incomingPeers = 3)).isNull()
    }

    @Test
    fun decodeIgnoresWrongRawValueTagsAndKeepsOptionalLabelBackedFieldsSafe() {
        val snapshot =
            decoder.decode(
                fullMetricMap() +
                    mapOf(
                        TracingMetricDecoder.DENSITY to TracingRawMetricValue.Counter(99),
                        TracingMetricDecoder.TIP_BLOCK to TracingRawMetricValue.IntGauge(1),
                        TracingMetricDecoder.FORGE_FORGED to TracingRawMetricValue.IntGauge(7),
                    )
            )

        assertThat(snapshot.chain?.density).isNull()
        assertThat(snapshot.chain?.tipBlock).isNull()
        assertThat(snapshot.forge.forged).isNull()
        assertThat(snapshot.toNodeStateMetrics(peers = 2, incomingPeers = 3))
            .isEqualTo(
                NodeStateMetrics(
                    peers = 2,
                    incomingPeers = 3,
                    blockHeight = 7_403_221L,
                    remainingKESPeriods = 36,
                    epoch = 490L,
                    slot = 7_403_221L,
                    slotInEpoch = 321L,
                    txsProcessed = 123_456L,
                )
            )
    }

    private fun fullMetricMap(): Map<String, TracingRawMetricValue> =
        mapOf(
            TracingMetricDecoder.BLOCK_NUM to TracingRawMetricValue.IntGauge(7_403_221L),
            TracingMetricDecoder.SLOT_NUM to TracingRawMetricValue.IntGauge(7_403_221L),
            TracingMetricDecoder.SLOT_IN_EPOCH to TracingRawMetricValue.IntGauge(321L),
            TracingMetricDecoder.EPOCH to TracingRawMetricValue.IntGauge(490L),
            TracingMetricDecoder.DENSITY to TracingRawMetricValue.Label("0.55"),
            TracingMetricDecoder.TIP_BLOCK to TracingRawMetricValue.Label("hash-123"),
            TracingMetricDecoder.FORGING_ENABLED to TracingRawMetricValue.IntGauge(1L),
            TracingMetricDecoder.FORGE_ABOUT_TO_LEAD to TracingRawMetricValue.Counter(2L),
            TracingMetricDecoder.FORGE_NODE_NOT_LEADER to TracingRawMetricValue.Counter(3L),
            TracingMetricDecoder.FORGE_NODE_IS_LEADER to TracingRawMetricValue.Counter(4L),
            TracingMetricDecoder.FORGED_SLOT_LAST to TracingRawMetricValue.IntGauge(7_403_221L),
            TracingMetricDecoder.FORGE_FORGED to TracingRawMetricValue.Counter(5L),
            TracingMetricDecoder.FORGE_ADOPTED to TracingRawMetricValue.Counter(6L),
            TracingMetricDecoder.OPERATIONAL_CERTIFICATE_START_KES_PERIOD to TracingRawMetricValue.IntGauge(10L),
            TracingMetricDecoder.OPERATIONAL_CERTIFICATE_EXPIRY_KES_PERIOD to TracingRawMetricValue.IntGauge(72L),
            TracingMetricDecoder.CURRENT_KES_PERIOD to TracingRawMetricValue.IntGauge(36L),
            TracingMetricDecoder.REMAINING_KES_PERIODS to TracingRawMetricValue.IntGauge(36L),
            TracingMetricDecoder.TXS_IN_MEMPOOL to TracingRawMetricValue.IntGauge(8L),
            TracingMetricDecoder.MEMPOOL_BYTES to TracingRawMetricValue.IntGauge(9L),
            TracingMetricDecoder.TXS_PROCESSED_NUM to TracingRawMetricValue.Counter(123_456L),
            TracingMetricDecoder.TXS_SYNC_DURATION to TracingRawMetricValue.IntGauge(10L),
            TracingMetricDecoder.TXS_SYNC_DURATION_TOTAL to TracingRawMetricValue.Counter(11L),
            TracingMetricDecoder.TXS_MEMPOOL_TIMEOUT_SOFT to TracingRawMetricValue.Counter(12L),
        )
}
