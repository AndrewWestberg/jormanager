package com.swiftmako.jormanager.controllers

import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.File
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.services.NetworkBundleValidator
import com.swiftmako.jormanager.services.NetworkBundleOptions
import com.swiftmako.jormanager.services.NetworkConfigurationBackup
import com.swiftmako.jormanager.services.NetworkConfigurationRestoreResult
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class NetworkConfigurationRestoreRequest(val backupBase64: String, val spendingPassword: String) {
    override fun toString(): String = "NetworkConfigurationRestoreRequest([REDACTED])"
}

@Controller
@Scope(SCOPE_SINGLETON)
class FileController
    @Autowired
    constructor(
        private val fileRepository: FileRepository,
        private val walletUtils: WalletUtils,
        private val networkBundleValidator: NetworkBundleValidator,
        private val networkConfigurationBackup: NetworkConfigurationBackup,
        private val argon2PasswordEncoder: Argon2PasswordEncoder,
        @param:Value("\${jormanager.spendingpassword}") private val spendingPasswordHash: String,
    ) {
        private val log by lazy { LoggerFactory.getLogger("FileController") }
        private val backupMap = mutableMapOf<String, String>()

        @MessageMapping("/file_options")
        @SendTo("/topic/messages")
        fun getFiles(): SocketResponse<List<File>> {
            val files = fileRepository.findAll().filterNot { it.name.startsWith("musashi-") || it.name.startsWith("snapshot-") }.map { file -> File(value = file.id!!, text = file.name) }
            return SocketResponse.Success(type = "file_options", data = files)
        }

        @MessageMapping("/network_bundle_options")
        @SendTo("/topic/messages")
        fun getNetworkBundles(): SocketResponse<NetworkBundleOptions> =
            SocketResponse.Success(type = "network_bundle_options", data = networkBundleValidator.options())

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
        fun getBackup(
            @RequestParam token: String
        ): ResponseEntity<Resource> {
            val spendingPassword = backupMap[token]
            backupMap.clear()
            if (spendingPassword != null && argon2PasswordEncoder.matches(spendingPassword, spendingPasswordHash)) {
                ByteArrayOutputStream().use { bos ->
                    ZipOutputStream(bos).use { zipOutputStream ->
                        zipOutputStream.putNextEntry(ZipEntry(CONFIGURATION_ARCHIVE))
                        zipOutputStream.write(networkConfigurationBackup.export())
                        zipOutputStream.closeEntry()
                        val files = fileRepository.findAll()
                        files.forEach { file ->
                            val fileZipEntry = ZipEntry(file.name)
                            zipOutputStream.putNextEntry(fileZipEntry)
                            val fileContent =
                                if (file.name.matches(SKEY_REGEX)) {
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

        @MessageMapping("/network_configuration_restore")
        @SendTo("/topic/messages")
        fun restoreConfiguration(request: NetworkConfigurationRestoreRequest): SocketResponse<NetworkConfigurationRestoreResult> {
            if (!argon2PasswordEncoder.matches(request.spendingPassword, spendingPasswordHash)) {
                return SocketResponse.Error("network_configuration_restore", IllegalArgumentException("Invalid spending password"))
            }
            return try {
                require(request.backupBase64.length.toLong() <= ((MAX_ARCHIVE_BYTES + 2) / 3) * 4) { "Backup is too large" }
                val archive = Base64.getDecoder().decode(request.backupBase64)
                require(archive.isNotEmpty() && archive.size.toLong() <= MAX_ARCHIVE_BYTES) { "Backup is empty or too large" }
                var payload: ByteArray? = null
                var total = 0L
                ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val contents = zip.readNBytes((MAX_ARCHIVE_BYTES - total + 1).toInt())
                        total += contents.size
                        require(total <= MAX_ARCHIVE_BYTES) { "Uncompressed backup is too large" }
                        if (entry.name == CONFIGURATION_ARCHIVE) {
                            require(payload == null && !entry.isDirectory) { "Duplicate configuration archive" }
                            payload = contents
                        }
                        zip.closeEntry()
                    }
                }
                val result = networkConfigurationBackup.restore(requireNotNull(payload) { "Backup has no configuration archive" })
                SocketResponse.Success("network_configuration_restore", result)
            } catch (e: Exception) {
                val message = if (e is IllegalArgumentException) e.message ?: "Invalid configuration backup" else "Invalid configuration backup"
                SocketResponse.Error("network_configuration_restore", IllegalArgumentException(message))
            }
        }

        companion object {
            private const val CONFIGURATION_ARCHIVE = "jormanager-network-configurations.json"
            private const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
            private val SKEY_REGEX = Regex(".*\\.skey(-\\d+)?")
        }
    }
