package com.swiftmako.jormanager.controllers

import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.controllers.utils.BlockUtils
import com.swiftmako.jormanager.entities.Block
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.Genesis
import com.swiftmako.jormanager.model.GenesisByron
import com.swiftmako.jormanager.repositories.BlockRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller

@Controller
class BlockController @Autowired constructor(
        private val buildProperties: BuildProperties,
        private val blockRepository: BlockRepository,
        private val nodeRepository: NodeRepository,
        private val fileRepository: FileRepository,
        private val blockUtils: BlockUtils,
        moshi: Moshi,
) {

    private val log = LoggerFactory.getLogger(BlockController::class.java)

    private val shelleyGenesisAdapter by lazy { moshi.adapter(Genesis::class.java) }
    private val byronGenesisAdapter by lazy { moshi.adapter(GenesisByron::class.java) }

    @MessageMapping("/version")
    @SendTo("/topic/messages")
    fun getVersion(): SocketResponse<String> {
        return SocketResponse.Success(type = "version", data = "JorManager ${buildProperties.version.split('-')[0]}")
    }

    @MessageMapping("/blocks")
    @SendTo("/topic/messages")
    fun getBlocks(): SocketResponse<List<Block>> {
        val blocks = blockRepository.findAll(Sort.by(Sort.Direction.ASC, "slot"))
        blocks.map { block ->
            if (block.epoch < 0L) {
                // block needs to be updated with epoch/slot in epoch
                nodeRepository.findDefault()?.let { node ->
                    fileRepository.findByIdOrNull(node.genesisByronFileId)?.let { byronFile ->
                        byronGenesisAdapter.fromJson(byronFile.content)?.let { byron ->
                            fileRepository.findByIdOrNull(node.genesisShelleyFileId)?.let { shelleyFile ->
                                shelleyGenesisAdapter.fromJson(shelleyFile.content)?.let { shelley ->
                                    val (epoch, slotInEpoch) = blockUtils.getEpochAndSlot(byron, shelley, block.slot)
                                    if (epoch > 0L && slotInEpoch > 0L) {
                                        blockRepository.save(block.copy(epoch = epoch, slotInEpoch = slotInEpoch))
                                    } else {
                                        log.warn("blockutils returned bad epoch/slotInEpoch")
                                        block
                                    }
                                } ?: run {
                                    log.warn("could not parse shelley genesis json!")
                                    block
                                }
                            } ?: run {
                                log.warn("shelley genesis file not found!")
                                block
                            }
                        } ?: run {
                            log.warn("could not parse byron genesis json!")
                            block
                        }
                    } ?: run {
                        log.warn("byron genesis file not found!")
                        block
                    }
                } ?: run {
                    log.warn("No default node found!")
                    block
                }
            } else {
                block
            }
        }
        return SocketResponse.Success(type = "blocks", data = blocks)
    }

}