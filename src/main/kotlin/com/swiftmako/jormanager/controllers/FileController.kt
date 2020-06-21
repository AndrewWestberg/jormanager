package com.swiftmako.jormanager.controllers

import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.File
import com.swiftmako.jormanager.repositories.FileRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller

@Controller
class FileController @Autowired constructor(
        private val buildProperties: BuildProperties,
        private val fileRepository: FileRepository
//        ,private val webSocketTemplate: SimpMessagingTemplate
) {
    private val log = LoggerFactory.getLogger(FileController::class.java)

    @MessageMapping("/genesis_file_options")
    @SendTo("/topic/messages")
    fun getGenesisFiles(): SocketResponse<List<File>> {
        log.info("Get GENESIS files!!!")
        val files = fileRepository.findGenesisFiles().map { file -> File(value = file.id!!, text = file.name) }
        log.info("files.size: ${files.size}")
        return SocketResponse.Success(type = "genesis_file_options", data = files)
    }
}