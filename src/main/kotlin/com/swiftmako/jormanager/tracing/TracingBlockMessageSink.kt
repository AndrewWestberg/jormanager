package com.swiftmako.jormanager.tracing

import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

@Component
class TracingBlockMessageSink(
    private val nodeRepository: NodeRepository,
    private val hostRepository: HostRepository,
    private val tracingBlockPersistenceService: TracingBlockPersistenceService,
    private val tracingRuntimeProfiler: TracingRuntimeProfiler,
    private val protocol2Extractor: TraceForwardProtocol2Extractor = TraceForwardProtocol2Extractor(),
) : TraceForwardMessageSink {
    private val log = LoggerFactory.getLogger("TracingBlockMessageSink")

    override suspend fun onMessage(
        nodeId: Long,
        message: TraceForwardMessage,
    ) {
        val reply = message as? TraceForwardMessage.TraceObjectsReply ?: return
        onTraceObjectBatch(nodeId, TracingRawTraceObjectBatch(nodeId = nodeId, capturedAt = java.time.Instant.now(), traceObjects = reply.traceObjects))
    }

    suspend fun onTraceObjectBatch(
        nodeId: Long,
        batch: TracingRawTraceObjectBatch,
    ) {
        val node = nodeRepository.findByIdOrNull(nodeId) ?: return
        if (node.isDeleted) {
            return
        }

        val host = hostRepository.findByIdOrNull(node.hostId)
        if (host == null) {
            log.warn("Unable to persist tracing block event for node ${node.name}: host ${node.hostId} not found")
            return
        }

        val decodeStartedAt = System.nanoTime()
        val decodedEvents = protocol2Extractor.decodeBlockEvents(batch)
        val decodeNanos = System.nanoTime() - decodeStartedAt
        val persistStartedAt = System.nanoTime()
        decodedEvents.forEach { event ->
            runCatching {
                tracingBlockPersistenceService.persistTracingCandidateBlock(node, host, event)
            }.onFailure { throwable ->
                log.warn("Unable to persist tracing block event for node ${node.name}", throwable)
            }
        }
        tracingRuntimeProfiler.recordBlockBatchProcessing(
            nodeId = nodeId,
            traceObjectCount = batch.traceObjects.size(),
            blockEventCount = decodedEvents.size,
            decodeNanos = decodeNanos,
            persistNanos = System.nanoTime() - persistStartedAt,
        )
    }
}
