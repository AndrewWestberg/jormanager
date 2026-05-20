package com.swiftmako.jormanager.tracing

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixtures
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.Instant
import java.util.Optional
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class TracingDashboardSignalServiceTest {
    private val tracingRuntimeProfiler = TracingRuntimeProfiler()
    private val rawCapture =
        TracingRawCaptureService(
            tracingBlockMessageSink =
                TracingBlockMessageSink(
                    nodeRepository = mockk<NodeRepository>().apply {
                        every { findById(any()) } returns Optional.of(mockk())
                    },
                    hostRepository = mockk<HostRepository>().apply {
                        every { findById(any()) } returns Optional.of(mockk())
                    },
                    tracingBlockPersistenceService = mockk(relaxed = true),
                    tracingRuntimeProfiler = tracingRuntimeProfiler,
                ),
            tracingRuntimeProfiler = tracingRuntimeProfiler,
        )
    private val service = TracingDashboardSignalService(rawCapture, tracingRuntimeProfiler)

    @Test
    fun freshProtocol1MetricsWinForOverlappingFields() =
        runBlocking {
            rawCapture.recordMetricSnapshotForTest(nodeId = 1L, snapshot = metricSnapshot(nodeId = 1L, blockNum = 8_888_888L, slotNum = 8_888_889L, slotInEpoch = 222L, epoch = 500L, remainingKesPeriods = 40L, txsProcessedNum = 999_999L))
            rawCapture.onMessage(1L, TraceForwardFixtures.pinnedNodeStateReply().toDataPointsReply())

            val signals = service.loadSignals(1L, epochLength = 432_000L, rawSnapshotMaxAge = Duration.ofSeconds(15))

            assertThat(signals?.nodeStateMetrics).isEqualTo(
                NodeStateMetrics(
                    peers = 12,
                    incomingPeers = 7,
                    blockHeight = 8_888_888L,
                    remainingKESPeriods = 40,
                    epoch = 500L,
                    slot = 8_888_889L,
                    slotInEpoch = 222L,
                    txsProcessed = 999_999L,
                )
            )
        }

    @Test
    fun staleProtocol1MetricsRemainUsableWhenNoFresherSignalsExist() =
        runBlocking {
            rawCapture.recordMetricSnapshotForTest(
                nodeId = 1L,
                snapshot = metricSnapshot(nodeId = 1L, capturedAt = Instant.now().minusSeconds(60))
            )

            val signals = service.loadSignals(1L, epochLength = 432_000L, rawSnapshotMaxAge = Duration.ofSeconds(15))

            assertThat(signals?.nodeStateMetrics).isEqualTo(
                NodeStateMetrics(
                    peers = 0,
                    incomingPeers = 0,
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
    fun protocol1PeerMetricsRemainAvailableWhenNoProtocol2OrDatapointPeersExist() =
        runBlocking {
            rawCapture.recordMetricSnapshotForTest(
                nodeId = 1L,
                snapshot =
                    metricSnapshot(
                        nodeId = 1L,
                        metrics = fullProtocol1MetricMap(outboundConnections = 9L, inboundConnections = 4L),
                    ),
            )

            val signals = service.loadSignals(1L, epochLength = 432_000L, rawSnapshotMaxAge = Duration.ofSeconds(15))

            assertThat(signals?.nodeStateMetrics?.peers).isEqualTo(9)
            assertThat(signals?.nodeStateMetrics?.incomingPeers).isEqualTo(4)
        }

    @Test
    fun protocol1ClockworkPeerMetricsPopulateDashboardPeers() =
        runBlocking {
            rawCapture.recordMetricSnapshotForTest(
                nodeId = 1L,
                snapshot =
                    metricSnapshot(
                        nodeId = 1L,
                        metrics = fullProtocol1MetricMap(outboundConnections = 11L, inboundConnections = 3L),
                    ),
            )

            val signals = service.loadSignals(1L, epochLength = 432_000L, rawSnapshotMaxAge = Duration.ofSeconds(15))

            assertThat(signals?.nodeStateMetrics?.peers).isEqualTo(11)
            assertThat(signals?.nodeStateMetrics?.incomingPeers).isEqualTo(3)
        }

    @Test
    fun incompleteProtocol1MetricsStillSupplyUsableChainState() =
        runBlocking {
            rawCapture.recordMetricSnapshotForTest(
                1L,
                metricSnapshot(
                    nodeId = 1L,
                    metrics = fullProtocol1MetricMap(blockNum = 8_888_888L, txsProcessedNum = null),
                ),
            )
            rawCapture.onMessage(1L, TraceForwardFixtures.pinnedNodeStateReply().toDataPointsReply())

            val signals = service.loadSignals(1L, epochLength = 432_000L, rawSnapshotMaxAge = Duration.ofSeconds(15))

            assertThat(signals?.nodeStateMetrics).isEqualTo(
                NodeStateMetrics(
                    peers = 12,
                    incomingPeers = 7,
                    blockHeight = 8_888_888L,
                    remainingKESPeriods = 36,
                    epoch = 490L,
                    slot = 7_403_221L,
                    slotInEpoch = 321L,
                    txsProcessed = 0L,
                )
            )
        }

    @Test
    fun protocol3StartupInfoRemainsAvailableWithoutDashboardMetrics() =
        runBlocking {
            rawCapture.onMessage(
                1L,
                TraceForwardFixtures
                    .msgDataPointsReply(
                        TraceForwardFixtures.dataPoint(
                            NodeStateDataPointDecoder.KEY_NODE_STARTUP_INFO,
                            "{\"era\":\"Conway\",\"epochLength\":432000,\"slotLength\":1,\"slotsPerKESPeriod\":129600}",
                        ),
                    ).toDataPointsReply(),
            )

            val signals = service.loadSignals(1L, epochLength = 432_000L, rawSnapshotMaxAge = Duration.ofSeconds(15))

            assertThat(signals?.nodeStateMetrics).isNull()
            assertThat(signals?.startupInfo).isEqualTo(
                NodeStartupInfo(
                    era = "Conway",
                    epochLength = 432_000L,
                    slotLength = 1L,
                    slotsPerKESPeriod = 129_600L,
                )
            )
        }

    @Test
    fun recentProtocol3RepliesAreMergedBeforeDecoding() =
        runBlocking {
            rawCapture.onMessage(1L, TraceForwardFixtures.pinnedNodeStateReply().toDataPointsReply())
            rawCapture.onMessage(
                1L,
                TraceForwardFixtures
                    .msgDataPointsReply(
                        TraceForwardFixtures.dataPoint(
                            NodeStateDataPointDecoder.KEY_NODE_STARTUP_INFO,
                            "{\"era\":\"Conway\",\"epochLength\":432000,\"slotLength\":1,\"slotsPerKESPeriod\":129600}"
                        ),
                    ).toDataPointsReply(),
            )

            val signals = service.loadSignals(1L, epochLength = 432_000L, rawSnapshotMaxAge = Duration.ofSeconds(15))

            assertThat(signals?.nodeStateMetrics).isEqualTo(
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
            assertThat(signals?.startupInfo).isEqualTo(
                NodeStartupInfo(
                    era = "Conway",
                    epochLength = 432_000L,
                    slotLength = 1L,
                    slotsPerKESPeriod = 129_600L,
                )
            )
        }
}

private fun ByteArray.toDataPointsReply(): TraceForwardMessage.DataPointsReply =
    ByteArrayInputStream(this).use { input ->
        val payload = com.google.iot.cbor.CborReader
            .createFromInputStream(input)
            .readDataItem() as com.google.iot.cbor.CborArray
        TraceForwardMessage.DataPointsReply(payload.elementAt(1) as com.google.iot.cbor.CborArray)
    }

private fun ByteArray.toTraceObjectsReply(): TraceForwardMessage.TraceObjectsReply =
    ByteArrayInputStream(this).use { input ->
        val payload = com.google.iot.cbor.CborReader
            .createFromInputStream(input)
            .readDataItem() as com.google.iot.cbor.CborArray
        TraceForwardMessage.TraceObjectsReply(payload.elementAt(1) as com.google.iot.cbor.CborArray)
    }

private fun metricSnapshot(
    nodeId: Long,
    blockNum: Long = 7_403_221L,
    slotNum: Long = 7_403_221L,
    slotInEpoch: Long = 321L,
    epoch: Long = 490L,
    remainingKesPeriods: Long = 36L,
    txsProcessedNum: Long? = 123_456L,
    capturedAt: Instant = Instant.now(),
    metrics: Map<String, TracingRawMetricValue> =
        fullProtocol1MetricMap(
            blockNum = blockNum,
            slotNum = slotNum,
            slotInEpoch = slotInEpoch,
            epoch = epoch,
            remainingKesPeriods = remainingKesPeriods,
            txsProcessedNum = txsProcessedNum,
        ),
) = TracingRawMetricSnapshot(
    nodeId = nodeId,
    capturedAt = capturedAt,
    metrics = metrics,
    rawJson = "metrics",
)

private fun fullProtocol1MetricMap(
    blockNum: Long = 7_403_221L,
    slotNum: Long = 7_403_221L,
    slotInEpoch: Long = 321L,
    epoch: Long = 490L,
    remainingKesPeriods: Long = 36L,
    txsProcessedNum: Long? = 123_456L,
    outboundConnections: Long? = null,
    inboundConnections: Long? = null,
): Map<String, TracingRawMetricValue> =
    buildMap {
        put(TracingMetricDecoder.BLOCK_NUM, TracingRawMetricValue.IntGauge(blockNum))
        put(TracingMetricDecoder.SLOT_NUM, TracingRawMetricValue.IntGauge(slotNum))
        put(TracingMetricDecoder.SLOT_IN_EPOCH, TracingRawMetricValue.IntGauge(slotInEpoch))
        put(TracingMetricDecoder.EPOCH, TracingRawMetricValue.IntGauge(epoch))
        put(TracingMetricDecoder.DENSITY, TracingRawMetricValue.Label("0.55"))
        put(TracingMetricDecoder.TIP_BLOCK, TracingRawMetricValue.Label("hash-123"))
        put(TracingMetricDecoder.FORGING_ENABLED, TracingRawMetricValue.IntGauge(1L))
        put(TracingMetricDecoder.FORGE_ABOUT_TO_LEAD, TracingRawMetricValue.Counter(2L))
        put(TracingMetricDecoder.FORGE_NODE_NOT_LEADER, TracingRawMetricValue.Counter(3L))
        put(TracingMetricDecoder.FORGE_NODE_IS_LEADER, TracingRawMetricValue.Counter(4L))
        put(TracingMetricDecoder.FORGED_SLOT_LAST, TracingRawMetricValue.IntGauge(slotNum))
        put(TracingMetricDecoder.FORGE_FORGED, TracingRawMetricValue.Counter(5L))
        put(TracingMetricDecoder.FORGE_ADOPTED, TracingRawMetricValue.Counter(6L))
        put(TracingMetricDecoder.OPERATIONAL_CERTIFICATE_START_KES_PERIOD, TracingRawMetricValue.IntGauge(10L))
        put(TracingMetricDecoder.OPERATIONAL_CERTIFICATE_EXPIRY_KES_PERIOD, TracingRawMetricValue.IntGauge(72L))
        put(TracingMetricDecoder.CURRENT_KES_PERIOD, TracingRawMetricValue.IntGauge(36L))
        put(TracingMetricDecoder.REMAINING_KES_PERIODS, TracingRawMetricValue.IntGauge(remainingKesPeriods))
        put(TracingMetricDecoder.TXS_IN_MEMPOOL, TracingRawMetricValue.IntGauge(8L))
        put(TracingMetricDecoder.MEMPOOL_BYTES, TracingRawMetricValue.IntGauge(9L))
        txsProcessedNum?.let {
            put(TracingMetricDecoder.TXS_PROCESSED_NUM, TracingRawMetricValue.Counter(it))
        }
        put(TracingMetricDecoder.TXS_SYNC_DURATION, TracingRawMetricValue.IntGauge(10L))
        put(TracingMetricDecoder.TXS_SYNC_DURATION_TOTAL, TracingRawMetricValue.Counter(11L))
        put(TracingMetricDecoder.TXS_MEMPOOL_TIMEOUT_SOFT, TracingRawMetricValue.Counter(12L))
        outboundConnections?.let {
            put(TracingMetricDecoder.OUTBOUND_CONNS, TracingRawMetricValue.IntGauge(it))
        }
        inboundConnections?.let {
            put(TracingMetricDecoder.INBOUND_CONNS, TracingRawMetricValue.IntGauge(it))
        }
    }
