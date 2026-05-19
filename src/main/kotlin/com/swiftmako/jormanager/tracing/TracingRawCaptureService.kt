package com.swiftmako.jormanager.tracing

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborTextString
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component

sealed interface TracingRawMetricValue {
    data class Counter(
        val value: Long,
    ) : TracingRawMetricValue

    data class IntGauge(
        val value: Long,
    ) : TracingRawMetricValue

    data class Label(
        val value: String,
    ) : TracingRawMetricValue
}

data class TracingRawMetricSnapshot(
    val nodeId: Long,
    val capturedAt: Instant,
    val metrics: Map<String, TracingRawMetricValue>,
    val rawJson: String,
)

data class TracingRawTraceObjectBatch(
    val nodeId: Long,
    val capturedAt: Instant,
    val traceObjects: CborArray,
) {
    fun toMessage(): TraceForwardMessage.TraceObjectsReply = TraceForwardMessage.TraceObjectsReply(traceObjects)
}

data class TracingRawDataPointSnapshot(
    val nodeId: Long,
    val capturedAt: Instant,
    val dataPoints: CborArray,
) {
    fun toMessage(): TraceForwardMessage.DataPointsReply = TraceForwardMessage.DataPointsReply(dataPoints)
}

@Component
class TracingRawCaptureService(
    private val tracingBlockMessageSink: TracingBlockMessageSink,
) : TraceForwardMessageSink {
    private val log = KotlinLogging.logger("TracingRawCaptureService")
    private val latestMetricSnapshots = ConcurrentHashMap<Long, TracingRawMetricSnapshot>()
    private val dataPointSnapshotBuffer = ConcurrentHashMap<Long, List<TracingRawDataPointSnapshot>>()
    private val traceObjectBatchBuffer = ConcurrentHashMap<Long, List<TracingRawTraceObjectBatch>>()

    override suspend fun onMessage(
        nodeId: Long,
        message: TraceForwardMessage,
    ) {
        val capturedAt = Instant.now()
        when (message) {
            is TraceForwardMessage.MetricsReply -> {
                if (isTracingDebugEnabled()) {
                    log.info {
                        "Tracing debug: metric snapshot node=$nodeId keys=${message.metrics.keys.sorted().joinToString()}"
                    }
                }
                latestMetricSnapshots[nodeId] =
                    TracingRawMetricSnapshot(
                        nodeId = nodeId,
                        capturedAt = capturedAt,
                        metrics = message.metrics,
                        rawJson = message.rawJson,
                    )
            }

            is TraceForwardMessage.DataPointsReply -> {
                val snapshot =
                    TracingRawDataPointSnapshot(
                        nodeId = nodeId,
                        capturedAt = capturedAt,
                        dataPoints = message.dataPoints,
                    )
                dataPointSnapshotBuffer.compute(nodeId) { _, existing -> ((existing ?: emptyList()) + snapshot).takeLast(MAX_DATA_POINT_SNAPSHOTS_PER_NODE) }
                if (isTracingDebugEnabled()) {
                    log.info {
                        "Tracing debug: datapoint snapshot node=$nodeId names=${snapshot.dataPointNames().joinToString()} nonEmpty=${snapshot.nonEmptyDataPointNames().joinToString()} bufferSize=${dataPointSnapshotBuffer[nodeId]?.size ?: 0}"
                    }
                }
            }

            is TraceForwardMessage.TraceObjectsReply -> {
                val batch =
                    TracingRawTraceObjectBatch(
                        nodeId = nodeId,
                        capturedAt = capturedAt,
                        traceObjects = message.traceObjects,
                    )
                traceObjectBatchBuffer.compute(nodeId) { _, existing -> ((existing ?: emptyList()) + batch).takeLast(MAX_TRACE_BATCHES_PER_NODE) }
                if (isTracingDebugEnabled()) {
                    log.info {
                        "Tracing debug: trace object batch node=$nodeId count=${message.traceObjects.size()} bufferSize=${traceObjectBatchBuffer[nodeId]?.size ?: 0}"
                    }
                }
                tracingBlockMessageSink.onTraceObjectBatch(nodeId, batch)
            }

            TraceForwardMessage.Done -> Unit
        }
    }

    fun latestMetricSnapshot(nodeId: Long): TracingRawMetricSnapshot? = latestMetricSnapshots[nodeId]

    fun latestFreshMetricSnapshot(
        nodeId: Long,
        maxAge: Duration,
        now: Instant = Instant.now(),
    ): TracingRawMetricSnapshot? = latestMetricSnapshots[nodeId]?.takeIf { it.capturedAt.plus(maxAge).isAfter(now) }

    fun latestDataPointSnapshot(nodeId: Long): TracingRawDataPointSnapshot? = dataPointSnapshotBuffer[nodeId]?.lastOrNull()

    fun latestFreshDataPointSnapshot(
        nodeId: Long,
        maxAge: Duration,
        now: Instant = Instant.now(),
    ): TracingRawDataPointSnapshot? = recentFreshDataPointSnapshots(nodeId, maxAge, now).lastOrNull()

    fun recentDataPointSnapshots(nodeId: Long): List<TracingRawDataPointSnapshot> = dataPointSnapshotBuffer[nodeId].orEmpty()

    fun recentFreshDataPointSnapshots(
        nodeId: Long,
        maxAge: Duration,
        now: Instant = Instant.now(),
    ): List<TracingRawDataPointSnapshot> =
        dataPointSnapshotBuffer[nodeId]
            ?.filter { it.capturedAt.plus(maxAge).isAfter(now) }
            .orEmpty()

    fun recentTraceObjectBatches(nodeId: Long): List<TracingRawTraceObjectBatch> =
        traceObjectBatchBuffer[nodeId].orEmpty()

    fun recentFreshTraceObjectBatches(
        nodeId: Long,
        maxAge: Duration,
        now: Instant = Instant.now(),
    ): List<TracingRawTraceObjectBatch> =
        traceObjectBatchBuffer[nodeId]
            ?.filter { it.capturedAt.plus(maxAge).isAfter(now) }
            .orEmpty()

    fun clearNode(nodeId: Long) {
        latestMetricSnapshots.remove(nodeId)
        dataPointSnapshotBuffer.remove(nodeId)
        traceObjectBatchBuffer.remove(nodeId)
    }

    internal fun recordMetricSnapshotForTest(
        nodeId: Long,
        snapshot: TracingRawMetricSnapshot,
    ) {
        latestMetricSnapshots[nodeId] = snapshot
    }

    internal fun recordDataPointSnapshotForTest(
        nodeId: Long,
        snapshot: TracingRawDataPointSnapshot,
    ) {
        dataPointSnapshotBuffer.compute(nodeId) { _, existing -> ((existing ?: emptyList()) + snapshot).takeLast(MAX_DATA_POINT_SNAPSHOTS_PER_NODE) }
    }

    internal fun recordTraceObjectBatchForTest(
        nodeId: Long,
        batch: TracingRawTraceObjectBatch,
    ) {
        traceObjectBatchBuffer.compute(nodeId) { _, existing -> ((existing ?: emptyList()) + batch).takeLast(MAX_TRACE_BATCHES_PER_NODE) }
    }

    companion object {
        private const val MAX_DATA_POINT_SNAPSHOTS_PER_NODE = 32
        private const val MAX_TRACE_BATCHES_PER_NODE = 32
    }
}

internal fun TracingRawDataPointSnapshot.dataPointNames(): List<String> =
    buildList {
        for (index in 0 until dataPoints.size()) {
            val pair = dataPoints.elementAt(index) as? CborArray ?: continue
            val name = (pair.elementAt(0) as? CborTextString)?.stringValue() ?: continue
            add(name)
        }
    }

internal fun TracingRawDataPointSnapshot.nonEmptyDataPointNames(): List<String> =
    buildList {
        for (index in 0 until dataPoints.size()) {
            val pair = dataPoints.elementAt(index) as? CborArray ?: continue
            val name = (pair.elementAt(0) as? CborTextString)?.stringValue() ?: continue
            val maybeValue = pair.elementAt(1) as? CborArray ?: continue
            if (maybeValue.size() > 0) {
                add(name)
            }
        }
    }
