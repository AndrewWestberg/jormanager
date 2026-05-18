package com.swiftmako.jormanager.tracing

import java.time.Duration
import org.springframework.stereotype.Component

data class TracingDashboardSignals(
    val nodeStateMetrics: NodeStateMetrics?,
    val startupInfo: NodeStartupInfo?,
)

@Component
class TracingDashboardSignalService(
    private val tracingRawCaptureService: TracingRawCaptureService,
    private val tracingMetricDecoder: TracingMetricDecoder = TracingMetricDecoder(),
    private val traceForwardProtocol3Extractor: TraceForwardProtocol3Extractor = TraceForwardProtocol3Extractor(),
    private val traceForwardProtocol2Extractor: TraceForwardProtocol2Extractor = TraceForwardProtocol2Extractor(),
) {
    fun loadSignals(
        nodeId: Long,
        epochLength: Long,
        rawSnapshotMaxAge: Duration,
    ): TracingDashboardSignals? {
        val protocol3Data =
            tracingRawCaptureService.latestFreshDataPointSnapshot(nodeId, rawSnapshotMaxAge)
                ?.let(traceForwardProtocol3Extractor::decode)
        val freshDataPointMetrics = protocol3Data?.nodeStateMetrics
        val peerState = loadForwardedNodeState(nodeId, rawSnapshotMaxAge)
        tracingRawCaptureService.latestFreshMetricSnapshot(nodeId, rawSnapshotMaxAge)
            ?.let { snapshot -> tracingMetricDecoder.decode(snapshot.metrics).toNodeStateMetrics(peers = 0, incomingPeers = 0) }
            ?.let { metrics ->
                return TracingDashboardSignals(
                    nodeStateMetrics =
                        metrics.copy(
                            peers = peerState?.peers ?: freshDataPointMetrics?.peers ?: 0,
                            incomingPeers = peerState?.incomingPeers ?: freshDataPointMetrics?.incomingPeers ?: 0,
                        ),
                    startupInfo = protocol3Data?.startupInfo,
                )
            }

        freshDataPointMetrics?.let { metrics ->
            return TracingDashboardSignals(
                nodeStateMetrics =
                    metrics.withDerivedSlot(epochLength).copy(
                        peers = peerState?.peers ?: metrics.peers,
                        incomingPeers = peerState?.incomingPeers ?: metrics.incomingPeers,
                    ),
                startupInfo = protocol3Data.startupInfo,
            )
        }

        peerState?.let { state ->
            val slot = state.slot ?: return@let
            val blockHeight = state.blockHeight ?: return@let
            val epoch = slot / epochLength
            val slotInEpoch = slot % epochLength
            return TracingDashboardSignals(
                nodeStateMetrics =
                    NodeStateMetrics(
                        peers = state.peers ?: 0,
                        incomingPeers = state.incomingPeers ?: 0,
                        blockHeight = blockHeight,
                        remainingKESPeriods = 0,
                        epoch = epoch,
                        slot = slot,
                        slotInEpoch = slotInEpoch,
                        txsProcessed = 0,
                    ),
                startupInfo = protocol3Data?.startupInfo,
            )
        }

        return protocol3Data?.startupInfo?.let { startupInfo ->
            TracingDashboardSignals(nodeStateMetrics = null, startupInfo = startupInfo)
        }
    }

    private fun loadForwardedNodeState(
        nodeId: Long,
        rawSnapshotMaxAge: Duration,
    ): ForwardedNodeState? =
        traceForwardProtocol2Extractor.decodeNodeState(
            tracingRawCaptureService.recentFreshTraceObjectBatches(nodeId, rawSnapshotMaxAge)
        )
}

private fun NodeStateMetrics.withDerivedSlot(epochLength: Long): NodeStateMetrics =
    if (slot > 0L) {
        this
    } else {
        copy(slot = epoch * epochLength + slotInEpoch)
    }
