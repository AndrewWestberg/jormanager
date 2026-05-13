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
    private val decoder: TraceForwardAdoptedBlockDecoder = TraceForwardAdoptedBlockDecoder(),
) : TraceForwardMessageSink {
    private val log = LoggerFactory.getLogger("TracingBlockMessageSink")

    override suspend fun onMessage(
        nodeId: Long,
        message: TraceForwardMessage,
    ) {
        val reply = message as? TraceForwardMessage.TraceObjectsReply ?: return
        val node = nodeRepository.findByIdOrNull(nodeId) ?: return
        if (node.isDeleted) {
            return
        }

        val host = hostRepository.findByIdOrNull(node.hostId)
        if (host == null) {
            log.warn("Unable to persist tracing block event for node ${node.name}: host ${node.hostId} not found")
            return
        }

        decoder.decode(reply).forEach { event ->
            runCatching {
                tracingBlockPersistenceService.persistTracingCandidateBlock(node, host, event)
            }.onFailure { throwable ->
                log.warn("Unable to persist tracing block event for node ${node.name}", throwable)
            }
        }
    }
}
