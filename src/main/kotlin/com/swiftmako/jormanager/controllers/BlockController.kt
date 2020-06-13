package com.swiftmako.jormanager.controllers

import com.swiftmako.jormanager.entities.Block
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.repositories.BlockRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.data.domain.Sort
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller

@Controller
class BlockController @Autowired constructor(
        private val buildProperties: BuildProperties,
        private val blockRepository: BlockRepository
//        ,private val webSocketTemplate: SimpMessagingTemplate
) {

    private val log = LoggerFactory.getLogger(BlockController::class.java)

    @MessageMapping("/version")
    @SendTo("/topic/messages")
    fun getVersion(): SocketResponse<String> {
        return SocketResponse.Success(type = "version", data = "JorManager ${buildProperties.version.split('-')[0]}")
    }

    @MessageMapping("/blocks")
    @SendTo("/topic/messages")
    fun getBlocks(): SocketResponse<List<Block>> {
        val blocks = blockRepository.findAll(Sort.by(Sort.Direction.ASC, "slot"))
        return SocketResponse.Success(type = "blocks", data = blocks)
//            webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success<Block>(type = "blocks", data = block))
    }

}