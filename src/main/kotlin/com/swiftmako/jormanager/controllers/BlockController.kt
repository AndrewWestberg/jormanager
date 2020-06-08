package com.swiftmako.jormanager.controllers

import org.slf4j.LoggerFactory
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller

@Controller
class BlockController {

    private val log = LoggerFactory.getLogger(BlockController::class.java)

    @MessageMapping("/version")
    @SendTo("/topic/version")
    fun getVersion(): String {
        return "JorManager 1.0.0"
    }
}