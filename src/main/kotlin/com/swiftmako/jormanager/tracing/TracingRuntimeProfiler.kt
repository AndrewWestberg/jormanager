package com.swiftmako.jormanager.tracing

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.springframework.stereotype.Component

@Component
class TracingRuntimeProfiler {
    private val log = KotlinLogging.logger("TracingRuntimeProfiler")
    private val nodeProfiles = ConcurrentHashMap<Long, NodeProfile>()
    private val nextSummaryAtMillis = AtomicLong(0L)

    fun registerNodeTarget(
        nodeId: Long,
        nodeName: String,
        nodeType: String,
        hostname: String,
        tracingPort: Int,
        enableTraceObjects: Boolean,
    ) {
        if (!isTracingProfileEnabled()) {
            return
        }

        profile(nodeId).apply {
            this.nodeName = nodeName
            this.nodeType = nodeType
            this.hostname = hostname
            this.tracingPort = tracingPort
            this.enableTraceObjects = enableTraceObjects
        }
    }

    fun clearNode(nodeId: Long) {
        nodeProfiles.remove(nodeId)
    }

    fun recordConnectionOpened(nodeId: Long) {
        if (!isTracingProfileEnabled()) {
            return
        }

        val profile = profile(nodeId)
        profile.connectionOpens.incrementAndGet()
        log.info {
            "Tracing profile: connection-open node=$nodeId name=${profile.nodeName} type=${profile.nodeType} target=${profile.hostname}:${profile.tracingPort} traceObjects=${if (profile.enableTraceObjects) "on" else "off"}"
        }
        maybeEmitSummary()
    }

    fun recordConnectionClosed(
        nodeId: Long,
        expected: Boolean,
    ) {
        if (!isTracingProfileEnabled()) {
            return
        }

        val profile = profile(nodeId)
        profile.connectionCloses.incrementAndGet()
        if (!expected) {
            profile.connectionErrors.incrementAndGet()
        }
        maybeEmitSummary()
    }

    fun recordMetricReply(
        nodeId: Long,
        metricCount: Int,
        rawBytes: Int,
    ) {
        if (!isTracingProfileEnabled()) {
            return
        }

        val profile = profile(nodeId)
        profile.metricReplies.incrementAndGet()
        profile.metricCount.addAndGet(metricCount.toLong())
        profile.metricBytes.addAndGet(rawBytes.toLong())
        maybeEmitSummary()
    }

    fun recordDataPointReply(
        nodeId: Long,
        dataPointCount: Int,
        nonEmptyCount: Int,
        valueBytes: Int,
    ) {
        if (!isTracingProfileEnabled()) {
            return
        }

        val profile = profile(nodeId)
        profile.dataPointReplies.incrementAndGet()
        profile.dataPointCount.addAndGet(dataPointCount.toLong())
        profile.dataPointNonEmptyCount.addAndGet(nonEmptyCount.toLong())
        profile.dataPointBytes.addAndGet(valueBytes.toLong())
        maybeEmitSummary()
    }

    fun recordTraceObjectBatch(
        nodeId: Long,
        batchCount: Int,
        approxBytes: Int,
    ) {
        if (!isTracingProfileEnabled()) {
            return
        }

        val profile = profile(nodeId)
        profile.traceBatchReplies.incrementAndGet()
        profile.traceObjectCount.addAndGet(batchCount.toLong())
        profile.traceObjectBytes.addAndGet(approxBytes.toLong())
        maybeEmitSummary()
    }

    fun recordDashboardLoad(
        nodeId: Long,
        selectedSource: String,
        durationNanos: Long,
        recentDataPointSnapshots: Int,
        freshMetricPresent: Boolean,
    ) {
        if (!isTracingProfileEnabled()) {
            return
        }

        val profile = profile(nodeId)
        profile.dashboardLoads.incrementAndGet()
        profile.dashboardTotalNanos.addAndGet(durationNanos)
        profile.dashboardRecentDataPointSnapshots.addAndGet(recentDataPointSnapshots.toLong())
        if (freshMetricPresent) {
            profile.dashboardFreshMetricLoads.incrementAndGet()
        }
        profile.dashboardMaxNanos.updateMax(durationNanos)
        when (selectedSource) {
            "protocol1" -> profile.dashboardProtocol1Loads.incrementAndGet()
            "protocol3" -> profile.dashboardProtocol3Loads.incrementAndGet()
            else -> profile.dashboardNoneLoads.incrementAndGet()
        }

        maybeWarnSlowDashboardLoad(profile, nodeId, selectedSource, durationNanos, recentDataPointSnapshots, freshMetricPresent)
        maybeEmitSummary()
    }

    fun recordBlockBatchProcessing(
        nodeId: Long,
        traceObjectCount: Int,
        blockEventCount: Int,
        decodeNanos: Long,
        persistNanos: Long,
    ) {
        if (!isTracingProfileEnabled()) {
            return
        }

        val profile = profile(nodeId)
        profile.blockBatches.incrementAndGet()
        profile.blockTraceObjects.addAndGet(traceObjectCount.toLong())
        profile.blockEvents.addAndGet(blockEventCount.toLong())
        profile.blockDecodeTotalNanos.addAndGet(decodeNanos)
        profile.blockPersistTotalNanos.addAndGet(persistNanos)
        profile.blockDecodeMaxNanos.updateMax(decodeNanos)
        profile.blockPersistMaxNanos.updateMax(persistNanos)

        maybeWarnSlowBlockBatch(profile, nodeId, traceObjectCount, blockEventCount, decodeNanos, persistNanos)
        maybeEmitSummary()
    }

    private fun maybeWarnSlowDashboardLoad(
        profile: NodeProfile,
        nodeId: Long,
        selectedSource: String,
        durationNanos: Long,
        recentDataPointSnapshots: Int,
        freshMetricPresent: Boolean,
    ) {
        if (durationNanos < tracingProfileSlowDashboardThresholdMillis() * 1_000_000L) {
            return
        }

        val now = System.currentTimeMillis()
        val last = profile.lastSlowDashboardWarnAtMillis.get()
        if (last != 0L && now - last < tracingProfileWarnThrottleMillis()) {
            return
        }
        if (!profile.lastSlowDashboardWarnAtMillis.compareAndSet(last, now)) {
            return
        }

        log.warn {
            "Tracing profile: slow-dashboard-load node=$nodeId name=${profile.nodeName} type=${profile.nodeType} durationMs=${durationNanos.toMillisString()} source=$selectedSource recentDataPointSnapshots=$recentDataPointSnapshots freshMetricPresent=$freshMetricPresent"
        }
    }

    private fun maybeWarnSlowBlockBatch(
        profile: NodeProfile,
        nodeId: Long,
        traceObjectCount: Int,
        blockEventCount: Int,
        decodeNanos: Long,
        persistNanos: Long,
    ) {
        val totalNanos = decodeNanos + persistNanos
        if (totalNanos < tracingProfileSlowBlockThresholdMillis() * 1_000_000L) {
            return
        }

        val now = System.currentTimeMillis()
        val last = profile.lastSlowBlockWarnAtMillis.get()
        if (last != 0L && now - last < tracingProfileWarnThrottleMillis()) {
            return
        }
        if (!profile.lastSlowBlockWarnAtMillis.compareAndSet(last, now)) {
            return
        }

        log.warn {
            "Tracing profile: slow-block-batch node=$nodeId name=${profile.nodeName} type=${profile.nodeType} traceObjects=$traceObjectCount events=$blockEventCount decodeMs=${decodeNanos.toMillisString()} persistMs=${persistNanos.toMillisString()}"
        }
    }

    private fun maybeEmitSummary() {
        if (!isTracingProfileEnabled()) {
            return
        }

        val now = System.currentTimeMillis()
        val intervalMillis = tracingProfileIntervalMillis()
        while (true) {
            val scheduledAt = nextSummaryAtMillis.get()
            if (scheduledAt == 0L) {
                if (nextSummaryAtMillis.compareAndSet(0L, now + intervalMillis)) {
                    return
                }
                continue
            }
            if (now < scheduledAt) {
                return
            }
            if (nextSummaryAtMillis.compareAndSet(scheduledAt, now + intervalMillis)) {
                emitSummary(intervalMillis)
                return
            }
        }
    }

    private fun emitSummary(intervalMillis: Long) {
        nodeProfiles.forEach { (nodeId, profile) ->
            val snapshot = profile.drainInterval()
            if (!snapshot.hasActivity()) {
                return@forEach
            }

            log.info {
                "Tracing profile: interval=${intervalMillis / 1000}s node=$nodeId name=${profile.nodeName} type=${profile.nodeType} target=${profile.hostname}:${profile.tracingPort} traceObjects=${if (profile.enableTraceObjects) "on" else "off"} opens=${snapshot.connectionOpens} closes=${snapshot.connectionCloses} errors=${snapshot.connectionErrors} metrics=${snapshot.metricReplies} keys=${snapshot.metricCount} metricBytes=${snapshot.metricBytes.formatBytes()} datapoints=${snapshot.dataPointReplies} names=${snapshot.dataPointCount} nonEmpty=${snapshot.dataPointNonEmptyCount} datapointBytes=${snapshot.dataPointBytes.formatBytes()} traceBatches=${snapshot.traceBatchReplies} traceObjects=${snapshot.traceObjectCount} traceBytes=${snapshot.traceObjectBytes.formatBytes()} dashboardLoads=${snapshot.dashboardLoads} dashboardAvgMs=${snapshot.dashboardAverageMillis()} dashboardMaxMs=${snapshot.dashboardMaxNanos.toMillisString()} dashboardRecentDpAvg=${snapshot.dashboardAverageRecentDataPointSnapshots()} dashboardFreshMetrics=${snapshot.dashboardFreshMetricLoads} sources[p1=${snapshot.dashboardProtocol1Loads},p3=${snapshot.dashboardProtocol3Loads},none=${snapshot.dashboardNoneLoads}] blockBatches=${snapshot.blockBatches} blockTraceObjects=${snapshot.blockTraceObjects} blockEvents=${snapshot.blockEvents} blockDecodeMs=${snapshot.blockDecodeTotalNanos.toMillisString()} blockDecodeMaxMs=${snapshot.blockDecodeMaxNanos.toMillisString()} blockPersistMs=${snapshot.blockPersistTotalNanos.toMillisString()} blockPersistMaxMs=${snapshot.blockPersistMaxNanos.toMillisString()}"
            }
        }
    }

    private fun profile(nodeId: Long): NodeProfile = nodeProfiles.computeIfAbsent(nodeId) { NodeProfile() }

    private class NodeProfile {
        @Volatile var nodeName: String = "unknown"

        @Volatile var nodeType: String = "unknown"

        @Volatile var hostname: String = "unknown"

        @Volatile var tracingPort: Int = -1

        @Volatile var enableTraceObjects: Boolean = false

        val connectionOpens = AtomicLong(0L)
        val connectionCloses = AtomicLong(0L)
        val connectionErrors = AtomicLong(0L)
        val metricReplies = AtomicLong(0L)
        val metricCount = AtomicLong(0L)
        val metricBytes = AtomicLong(0L)
        val dataPointReplies = AtomicLong(0L)
        val dataPointCount = AtomicLong(0L)
        val dataPointNonEmptyCount = AtomicLong(0L)
        val dataPointBytes = AtomicLong(0L)
        val traceBatchReplies = AtomicLong(0L)
        val traceObjectCount = AtomicLong(0L)
        val traceObjectBytes = AtomicLong(0L)
        val dashboardLoads = AtomicLong(0L)
        val dashboardTotalNanos = AtomicLong(0L)
        val dashboardMaxNanos = AtomicLong(0L)
        val dashboardRecentDataPointSnapshots = AtomicLong(0L)
        val dashboardFreshMetricLoads = AtomicLong(0L)
        val dashboardProtocol1Loads = AtomicLong(0L)
        val dashboardProtocol3Loads = AtomicLong(0L)
        val dashboardNoneLoads = AtomicLong(0L)
        val blockBatches = AtomicLong(0L)
        val blockTraceObjects = AtomicLong(0L)
        val blockEvents = AtomicLong(0L)
        val blockDecodeTotalNanos = AtomicLong(0L)
        val blockPersistTotalNanos = AtomicLong(0L)
        val blockDecodeMaxNanos = AtomicLong(0L)
        val blockPersistMaxNanos = AtomicLong(0L)
        val lastSlowDashboardWarnAtMillis = AtomicLong(0L)
        val lastSlowBlockWarnAtMillis = AtomicLong(0L)

        fun drainInterval(): NodeProfileSnapshot =
            NodeProfileSnapshot(
                connectionOpens = connectionOpens.getAndSet(0L),
                connectionCloses = connectionCloses.getAndSet(0L),
                connectionErrors = connectionErrors.getAndSet(0L),
                metricReplies = metricReplies.getAndSet(0L),
                metricCount = metricCount.getAndSet(0L),
                metricBytes = metricBytes.getAndSet(0L),
                dataPointReplies = dataPointReplies.getAndSet(0L),
                dataPointCount = dataPointCount.getAndSet(0L),
                dataPointNonEmptyCount = dataPointNonEmptyCount.getAndSet(0L),
                dataPointBytes = dataPointBytes.getAndSet(0L),
                traceBatchReplies = traceBatchReplies.getAndSet(0L),
                traceObjectCount = traceObjectCount.getAndSet(0L),
                traceObjectBytes = traceObjectBytes.getAndSet(0L),
                dashboardLoads = dashboardLoads.getAndSet(0L),
                dashboardTotalNanos = dashboardTotalNanos.getAndSet(0L),
                dashboardMaxNanos = dashboardMaxNanos.getAndSet(0L),
                dashboardRecentDataPointSnapshots = dashboardRecentDataPointSnapshots.getAndSet(0L),
                dashboardFreshMetricLoads = dashboardFreshMetricLoads.getAndSet(0L),
                dashboardProtocol1Loads = dashboardProtocol1Loads.getAndSet(0L),
                dashboardProtocol3Loads = dashboardProtocol3Loads.getAndSet(0L),
                dashboardNoneLoads = dashboardNoneLoads.getAndSet(0L),
                blockBatches = blockBatches.getAndSet(0L),
                blockTraceObjects = blockTraceObjects.getAndSet(0L),
                blockEvents = blockEvents.getAndSet(0L),
                blockDecodeTotalNanos = blockDecodeTotalNanos.getAndSet(0L),
                blockPersistTotalNanos = blockPersistTotalNanos.getAndSet(0L),
                blockDecodeMaxNanos = blockDecodeMaxNanos.getAndSet(0L),
                blockPersistMaxNanos = blockPersistMaxNanos.getAndSet(0L),
            )
    }

    private data class NodeProfileSnapshot(
        val connectionOpens: Long,
        val connectionCloses: Long,
        val connectionErrors: Long,
        val metricReplies: Long,
        val metricCount: Long,
        val metricBytes: Long,
        val dataPointReplies: Long,
        val dataPointCount: Long,
        val dataPointNonEmptyCount: Long,
        val dataPointBytes: Long,
        val traceBatchReplies: Long,
        val traceObjectCount: Long,
        val traceObjectBytes: Long,
        val dashboardLoads: Long,
        val dashboardTotalNanos: Long,
        val dashboardMaxNanos: Long,
        val dashboardRecentDataPointSnapshots: Long,
        val dashboardFreshMetricLoads: Long,
        val dashboardProtocol1Loads: Long,
        val dashboardProtocol3Loads: Long,
        val dashboardNoneLoads: Long,
        val blockBatches: Long,
        val blockTraceObjects: Long,
        val blockEvents: Long,
        val blockDecodeTotalNanos: Long,
        val blockPersistTotalNanos: Long,
        val blockDecodeMaxNanos: Long,
        val blockPersistMaxNanos: Long,
    ) {
        fun hasActivity(): Boolean =
            connectionOpens > 0L ||
                connectionCloses > 0L ||
                connectionErrors > 0L ||
                metricReplies > 0L ||
                dataPointReplies > 0L ||
                traceBatchReplies > 0L ||
                dashboardLoads > 0L ||
                blockBatches > 0L

        fun dashboardAverageMillis(): String =
            if (dashboardLoads == 0L) {
                "0.0"
            } else {
                "%.1f".format((dashboardTotalNanos.toDouble() / dashboardLoads.toDouble()) / 1_000_000.0)
            }

        fun dashboardAverageRecentDataPointSnapshots(): String =
            if (dashboardLoads == 0L) {
                "0.0"
            } else {
                "%.1f".format(dashboardRecentDataPointSnapshots.toDouble() / dashboardLoads.toDouble())
            }
    }
}

internal fun isTracingProfileEnabled(): Boolean =
    System.getProperty("jormanager.tracing.profile") == "true" ||
        System.getenv("JORMANAGER_TRACING_PROFILE") == "true"

private fun tracingProfileIntervalMillis(): Long =
    (System.getProperty("jormanager.tracing.profile.interval.seconds") ?: System.getenv("JORMANAGER_TRACING_PROFILE_INTERVAL_SECONDS"))
        ?.toLongOrNull()
        ?.coerceAtLeast(5L)
        ?.times(1000L)
        ?: 30_000L

private fun tracingProfileWarnThrottleMillis(): Long =
    (System.getProperty("jormanager.tracing.profile.warn.throttle.seconds") ?: System.getenv("JORMANAGER_TRACING_PROFILE_WARN_THROTTLE_SECONDS"))
        ?.toLongOrNull()
        ?.coerceAtLeast(5L)
        ?.times(1000L)
        ?: 60_000L

private fun tracingProfileSlowDashboardThresholdMillis(): Long =
    (System.getProperty("jormanager.tracing.profile.slow.dashboard.ms") ?: System.getenv("JORMANAGER_TRACING_PROFILE_SLOW_DASHBOARD_MS"))
        ?.toLongOrNull()
        ?.coerceAtLeast(1L)
        ?: 25L

private fun tracingProfileSlowBlockThresholdMillis(): Long =
    (System.getProperty("jormanager.tracing.profile.slow.block.ms") ?: System.getenv("JORMANAGER_TRACING_PROFILE_SLOW_BLOCK_MS"))
        ?.toLongOrNull()
        ?.coerceAtLeast(1L)
        ?: 25L

private fun AtomicLong.updateMax(candidate: Long) {
    while (true) {
        val current = get()
        if (candidate <= current) {
            return
        }
        if (compareAndSet(current, candidate)) {
            return
        }
    }
}

private fun Long.formatBytes(): String =
    when {
        this < 1024L -> "${this}B"
        this < 1024L * 1024L -> "%.1fKB".format(this.toDouble() / 1024.0)
        else -> "%.1fMB".format(this.toDouble() / (1024.0 * 1024.0))
    }

private fun Long.toMillisString(): String = "%.1f".format(this.toDouble() / 1_000_000.0)
