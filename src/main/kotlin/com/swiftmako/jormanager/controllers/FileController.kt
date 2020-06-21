package com.swiftmako.jormanager.controllers

import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.File
import com.swiftmako.jormanager.repositories.FileRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller

@Controller
class FileController @Autowired constructor(
        private val fileRepository: FileRepository
) {
    private val log = LoggerFactory.getLogger(FileController::class.java)

    @MessageMapping("/file_options")
    @SendTo("/topic/messages")
    fun getFiles(): SocketResponse<List<File>> {
        val files = fileRepository.findAll().map { file -> File(value = file.id!!, text = file.name) }
        return SocketResponse.Success(type = "file_options", data = files)
    }
}