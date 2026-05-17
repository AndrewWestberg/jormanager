package com.swiftmako.jormanager.tracing

import com.google.iot.cbor.CborArray
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
    private val latestMetricSnapshots = ConcurrentHashMap<Long, TracingRawMetricSnapshot>()
    private val latestDataPointSnapshots = ConcurrentHashMap<Long, TracingRawDataPointSnapshot>()
    private val traceObjectBatchBuffer = ConcurrentHashMap<Long, List<TracingRawTraceObjectBatch>>()

    override suspend fun onMessage(
        nodeId: Long,
        message: TraceForwardMessage,
    ) {
        val capturedAt = Instant.now()
        when (message) {
            is TraceForwardMessage.MetricsReply -> {
                latestMetricSnapshots[nodeId] =
                    TracingRawMetricSnapshot(
                        nodeId = nodeId,
                        capturedAt = capturedAt,
                        metrics = message.metrics,
                        rawJson = message.rawJson,
                    )
            }

            is TraceForwardMessage.DataPointsReply -> {
                latestDataPointSnapshots[nodeId] =
                    TracingRawDataPointSnapshot(
                        nodeId = nodeId,
                        capturedAt = capturedAt,
                        dataPoints = message.dataPoints,
                    )
            }

            is TraceForwardMessage.TraceObjectsReply -> {
                val batch =
                    TracingRawTraceObjectBatch(
                        nodeId = nodeId,
                        capturedAt = capturedAt,
                        traceObjects = message.traceObjects,
                    )
                traceObjectBatchBuffer.compute(nodeId) { _, existing -> ((existing ?: emptyList()) + batch).takeLast(MAX_TRACE_BATCHES_PER_NODE) }
                tracingBlockMessageSink.onTraceObjectBatch(nodeId, batch)
            }

            TraceForwardMessage.Done -> Unit
        }
    }

    fun latestMetricSnapshot(nodeId: Long): TracingRawMetricSnapshot? = latestMetricSnapshots[nodeId]

    fun latestDataPointSnapshot(nodeId: Long): TracingRawDataPointSnapshot? = latestDataPointSnapshots[nodeId]

    fun latestFreshDataPointSnapshot(
        nodeId: Long,
        maxAge: Duration,
        now: Instant = Instant.now(),
    ): TracingRawDataPointSnapshot? = latestDataPointSnapshots[nodeId]?.takeIf { it.capturedAt.plus(maxAge).isAfter(now) }

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
        latestDataPointSnapshots.remove(nodeId)
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
        latestDataPointSnapshots[nodeId] = snapshot
    }

    internal fun recordTraceObjectBatchForTest(
        nodeId: Long,
        batch: TracingRawTraceObjectBatch,
    ) {
        traceObjectBatchBuffer.compute(nodeId) { _, existing -> ((existing ?: emptyList()) + batch).takeLast(MAX_TRACE_BATCHES_PER_NODE) }
    }

    companion object {
        private const val MAX_TRACE_BATCHES_PER_NODE = 32
    }
}
