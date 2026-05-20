package com.swiftmako.jormanager.tracing

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import org.springframework.stereotype.Component

data class TracingDashboardSignals(
    val nodeStateMetrics: NodeStateMetrics?,
    val startupInfo: NodeStartupInfo?,
)

@Component
class TracingDashboardSignalService(
    private val tracingRawCaptureService: TracingRawCaptureService,
    private val tracingRuntimeProfiler: TracingRuntimeProfiler,
    private val tracingMetricDecoder: TracingMetricDecoder = TracingMetricDecoder(),
    private val traceForwardProtocol3Extractor: TraceForwardProtocol3Extractor = TraceForwardProtocol3Extractor(),
) {
    private val log = KotlinLogging.logger("TracingDashboardSignalService")

    fun loadSignals(
        nodeId: Long,
        epochLength: Long,
        rawSnapshotMaxAge: Duration,
    ): TracingDashboardSignals? {
        val startedAt = System.nanoTime()
        val recentDataPointSnapshots = tracingRawCaptureService.recentFreshDataPointSnapshots(nodeId, rawSnapshotMaxAge)
        val mergedDataPointSnapshot = recentDataPointSnapshots.mergeDataPointsSnapshot(nodeId)
        val protocol3Data =
            mergedDataPointSnapshot
                ?.let(traceForwardProtocol3Extractor::decode)
        val freshDataPointMetrics = protocol3Data?.nodeStateMetrics
        val latestMetricSnapshot = tracingRawCaptureService.latestMetricSnapshot(nodeId)
        val freshMetricSnapshot = tracingRawCaptureService.latestFreshMetricSnapshot(nodeId, rawSnapshotMaxAge)
        val recentSnapshotCount = recentDataPointSnapshots.size
        val freshMetricPresent = freshMetricSnapshot != null

        if (isTracingDebugEnabled()) {
            log.info {
                "Tracing debug: loadSignals node=$nodeId epochLength=$epochLength recentDataPointSnapshots=${recentDataPointSnapshots.size} mergedDataPointNames=${mergedDataPointSnapshot?.dataPointNames()?.joinToString()} mergedNonEmpty=${mergedDataPointSnapshot?.nonEmptyDataPointNames()?.joinToString()} protocol3Metrics=$freshDataPointMetrics startupInfo=${protocol3Data?.startupInfo} metricKeys=${freshMetricSnapshot?.metrics?.keys?.sorted()?.joinToString()}"
            }
        }

        (freshMetricSnapshot ?: latestMetricSnapshot)
            ?.let { snapshot -> tracingMetricDecoder.decode(snapshot.metrics).toNodeStateMetrics(peers = 0, incomingPeers = 0) }
            ?.let { metrics ->
                if (isTracingDebugEnabled()) {
                    log.info { "Tracing debug: loadSignals node=$nodeId selectedSource=protocol1 metrics=$metrics datapointPeers=$freshDataPointMetrics metricFresh=${freshMetricSnapshot != null}" }
                }
                return TracingDashboardSignals(
                    nodeStateMetrics =
                        metrics.copy(
                            peers = freshDataPointMetrics?.peers ?: metrics.peers,
                            incomingPeers = freshDataPointMetrics?.incomingPeers ?: metrics.incomingPeers,
                        ),
                    startupInfo = protocol3Data?.startupInfo,
                ).also {
                    tracingRuntimeProfiler.recordDashboardLoad(
                        nodeId = nodeId,
                        selectedSource = "protocol1",
                        durationNanos = System.nanoTime() - startedAt,
                        recentDataPointSnapshots = recentSnapshotCount,
                        freshMetricPresent = freshMetricPresent,
                    )
                }
            }

        freshDataPointMetrics?.let { metrics ->
            if (isTracingDebugEnabled()) {
                log.info { "Tracing debug: loadSignals node=$nodeId selectedSource=protocol3 metrics=$metrics" }
            }
            return TracingDashboardSignals(
                nodeStateMetrics = metrics.withDerivedSlot(epochLength),
                startupInfo = protocol3Data.startupInfo,
            ).also {
                tracingRuntimeProfiler.recordDashboardLoad(
                    nodeId = nodeId,
                    selectedSource = "protocol3",
                    durationNanos = System.nanoTime() - startedAt,
                    recentDataPointSnapshots = recentSnapshotCount,
                    freshMetricPresent = freshMetricPresent,
                )
            }
        }

        if (isTracingDebugEnabled()) {
            log.info { "Tracing debug: loadSignals node=$nodeId selectedSource=none startupInfo=${protocol3Data?.startupInfo} metricPresent=${latestMetricSnapshot != null}" }
        }
        return protocol3Data?.startupInfo?.let { startupInfo ->
            TracingDashboardSignals(nodeStateMetrics = null, startupInfo = startupInfo)
        }.also {
            tracingRuntimeProfiler.recordDashboardLoad(
                nodeId = nodeId,
                selectedSource = "none",
                durationNanos = System.nanoTime() - startedAt,
                recentDataPointSnapshots = recentSnapshotCount,
                freshMetricPresent = freshMetricPresent,
            )
        }
    }
}

private fun List<TracingRawDataPointSnapshot>.mergeDataPointsSnapshot(nodeId: Long): TracingRawDataPointSnapshot? {
    if (isEmpty()) {
        return null
    }

    val mergedByName = linkedMapOf<String, com.google.iot.cbor.CborObject>()
    var newestCapturedAt = first().capturedAt
    for (snapshot in this) {
        if (snapshot.capturedAt.isAfter(newestCapturedAt)) {
            newestCapturedAt = snapshot.capturedAt
        }
        for (index in 0 until snapshot.dataPoints.size()) {
            val pair = snapshot.dataPoints.elementAt(index) as? com.google.iot.cbor.CborArray ?: continue
            if (pair.size() != 2) {
                continue
            }
            val name = (pair.elementAt(0) as? com.google.iot.cbor.CborTextString)?.stringValue() ?: continue
            mergedByName[name] = pair.elementAt(1)
        }
    }

    if (mergedByName.isEmpty()) {
        return null
    }

    val mergedDataPoints =
        com.google.iot.cbor.CborArray.create().apply {
            mergedByName.forEach { (name, value) ->
                add(
                    com.google.iot.cbor.CborArray.create().apply {
                        add(
                            com.google.iot.cbor.CborTextString
                                .create(name)
                        )
                        add(value)
                    }
                )
            }
        }

    return TracingRawDataPointSnapshot(
        nodeId = nodeId,
        capturedAt = newestCapturedAt,
        dataPoints = mergedDataPoints,
    )
}

internal fun isTracingDebugEnabled(): Boolean =
    System.getProperty("jormanager.tracing.debug") == "true" ||
        System.getenv("JORMANAGER_TRACING_DEBUG") == "true"

private fun NodeStateMetrics.withDerivedSlot(epochLength: Long): NodeStateMetrics =
    if (slot > 0L) {
        this
    } else {
        copy(slot = epoch * epochLength + slotInEpoch)
    }
