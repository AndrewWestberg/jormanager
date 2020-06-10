package com.swiftmako.jormanager.controllers

import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.repositories.BlockRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Sort
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller

@Controller
class BlockController @Autowired constructor(
        private val blockRepository: BlockRepository,
        private val webSocketTemplate: SimpMessagingTemplate
) {

    private val log = LoggerFactory.getLogger(BlockController::class.java)

    @MessageMapping("/version")
    @SendTo("/topic/version")
    fun getVersion(): SocketResponse<String> {
        return SocketResponse.Success("JorManager 1.0.0")
    }

    @MessageMapping("/blocks")
    fun getBlocks() {
        blockRepository.findAll(Sort.by(Sort.Direction.ASC, "slot")).forEach { block ->
            webSocketTemplate.convertAndSend("/topic/blocks", SocketResponse.Success(block))
        }
    }

}