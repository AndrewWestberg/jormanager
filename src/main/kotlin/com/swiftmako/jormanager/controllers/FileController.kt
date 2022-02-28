package com.swiftmako.jormanager.controllers

import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.File
import com.swiftmako.jormanager.repositories.FileRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_SINGLETON
import org.springframework.context.annotation.Scope
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Controller
@Scope(SCOPE_SINGLETON)
class FileController @Autowired constructor(
        private val fileRepository: FileRepository,
        private val walletUtils: WalletUtils,
        private val argon2PasswordEncoder: Argon2PasswordEncoder,
        @Value("\${jormanager.spendingpassword}") private val spendingPasswordHash: String,
) {
    private val log by lazy {  LoggerFactory.getLogger("FileController") }
    private val backupMap = mutableMapOf<String, String>()

    @MessageMapping("/file_options")
    @SendTo("/topic/messages")
    fun getFiles(): SocketResponse<List<File>> {
        val files = fileRepository.findAll().map { file -> File(value = file.id!!, text = file.name) }
        return SocketResponse.Success(type = "file_options", data = files)
    }

    @MessageMapping("/backup")
    @SendTo("/topic/messages")
    fun postBackup(spendingPassword: String): SocketResponse<String> {
        backupMap.clear()
        return if (!argon2PasswordEncoder.matches(spendingPassword, spendingPasswordHash)) {
            SocketResponse.Error(type = "backup", exception = IllegalArgumentException("Invalid spending password!"))
        } else {
            val token = UUID.randomUUID().toString()
            backupMap[token] = spendingPassword
            SocketResponse.Success(type = "backup", data = token)
        }
    }

    @GetMapping("/jormanager_backup.zip")
    fun getBackup(@RequestParam token: String): ResponseEntity<Resource> {
        val spendingPassword = backupMap[token]
        backupMap.clear()
        if (spendingPassword != null && argon2PasswordEncoder.matches(spendingPassword, spendingPasswordHash)) {
            ByteArrayOutputStream().use { bos ->
                ZipOutputStream(bos).use { zipOutputStream ->
                    val files = fileRepository.findAll()
                    files.forEach { file ->
                        val fileZipEntry = ZipEntry(file.name)
                        zipOutputStream.putNextEntry(fileZipEntry)
                        val fileContent = if (file.name.matches(SKEY_REGEX)) {
                            walletUtils.getSKeyContent(file, spendingPassword)
                        } else {
                            file.content
                        }
                        zipOutputStream.write(fileContent.toByteArray())
                    }
                }
                bos.flush()
                val zipBytes = bos.toByteArray()
                return ResponseEntity
                        .ok()
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .contentLength(zipBytes.size.toLong())
                        .body(ByteArrayResource(zipBytes))
            }
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
    }

    companion object {
        private val SKEY_REGEX = Regex(".*\\.skey(-\\d+)?")
    }
}