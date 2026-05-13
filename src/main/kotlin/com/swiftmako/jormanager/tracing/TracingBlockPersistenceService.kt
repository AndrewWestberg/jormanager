package com.swiftmako.jormanager.tracing

import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.controllers.utils.BlockUtils
import com.swiftmako.jormanager.entities.Block
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.GenesisByron
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.repositories.BlockRepository
import com.swiftmako.jormanager.repositories.FileRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Service
class TracingBlockPersistenceService(
    private val blockRepository: BlockRepository,
    private val fileRepository: FileRepository,
    private val webSocketTemplate: SimpMessagingTemplate,
    private val blockUtils: BlockUtils,
    private val byronGenesisAdapter: JsonAdapter<GenesisByron>,
    private val shelleyGenesisAdapter: JsonAdapter<GenesisShelley>,
) {
    private val log = LoggerFactory.getLogger("TracingBlockPersistenceService")
    private val persistenceMutex = Mutex()

    suspend fun persistTracingCandidateBlock(
        node: Node,
        host: Host,
        event: ForwardedAdoptedBlockEvent,
    ): Block? {
        val byronGenesisFile =
            fileRepository.findByIdOrNull(node.genesisByronFileId)
                ?: error("Unable to read byron genesis file for node ${node.name}")
        val shelleyGenesisFile =
            fileRepository.findByIdOrNull(node.genesisShelleyFileId)
                ?: error("Unable to read shelley genesis file for node ${node.name}")

        val byron = byronGenesisAdapter.fromJson(byronGenesisFile.content) ?: error("Unable to parse byron genesis file for node ${node.name}")
        val shelley =
            shelleyGenesisAdapter.fromJson(shelleyGenesisFile.content)
                ?: error("Unable to parse shelley genesis file for node ${node.name}")

        val (epoch, slotInEpoch) = blockUtils.getEpochAndSlot(byron, shelley, event.slot)
        val candidateBlock =
            Block(
                at = event.timestamp,
                pool = "---",
                host = host.hostname,
                slot = event.slot,
                epoch = epoch,
                slotInEpoch = slotInEpoch,
                hash = event.blockHash,
                status = "completed",
            )

        return persistCandidateBlock(candidateBlock)
    }

    suspend fun persistCandidateBlock(
        candidateBlock: Block,
    ): Block? =
        persistenceMutex.withLock {
            val existingBlock = blockRepository.findBySlot(candidateBlock.slot).firstOrNull()
            if (existingBlock != null && existingBlock.hash.isNotEmpty()) {
                return null
            }

            val savedBlock =
                blockRepository.save(
                    candidateBlock.copy(
                        id = existingBlock?.id,
                        pool = existingBlock?.pool ?: candidateBlock.pool,
                    )
                )

            log.info(savedBlock.toString())
            webSocketTemplate.convertAndSend(
                "/topic/messages",
                SocketResponse.Success(type = "block", data = savedBlock),
            )
            savedBlock
        }
}
