package com.swiftmako.jormanager.controllers

import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.SSHClientPool
import com.swiftmako.jormanager.controllers.utils.SshFingerprints
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.HostFingerprintRequest
import com.swiftmako.jormanager.model.HostFingerprintResult
import com.swiftmako.jormanager.repositories.HostRepository
import java.io.IOException
import java.util.UUID
import net.schmizz.sshj.common.SSHRuntimeException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_SINGLETON
import org.springframework.context.annotation.Scope
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional

@Controller
@Scope(SCOPE_SINGLETON)
class HostController
    @Autowired
    constructor(
        private val hostRepository: HostRepository,
        private val walletUtils: WalletUtils,
        private val webSocketTemplateProvider: ObjectProvider<SimpMessagingTemplate>
    ) {
        private val log by lazy { LoggerFactory.getLogger("HostController") }

        private fun sortedHosts(): List<Host> = hostRepository.findAll(Sort.by(Sort.Direction.ASC, "hostname"))

        private fun broadcastHosts() {
            runCatching {
                webSocketTemplateProvider.ifAvailable?.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Success(type = "hosts", data = sortedHosts())
                )
            }.onFailure { e ->
                log.warn("Unable to broadcast hosts update", e)
            }
        }

        @MessageMapping("/hosts")
        @SendTo("/topic/messages")
        fun getHosts(): SocketResponse<List<Host>> = SocketResponse.Success(type = "hosts", data = sortedHosts())

        @MessageMapping("/addhost")
        @SendTo("/topic/messages")
        @Transactional
        fun addHost(host: Host): SocketResponse<String> {
            val existingHost = host.id?.let { id -> hostRepository.findByIdOrNull(id) }
            val effectiveHost =
                if (existingHost != null) {
                    if (!host.isRemote ||
                        existingHost.type != host.type ||
                        existingHost.hostname != host.hostname ||
                        existingHost.sshPort != host.sshPort ||
                        existingHost.sshUser != host.sshUser ||
                        existingHost.sshPemPath != host.sshPemPath
                    ) {
                        SSHClientPool.evictForHost(existingHost.id)
                    }
                    host.copy(
                        sshFingerprint = if (host.isRemote) existingHost.sshFingerprint else null,
                        sshMismatchFingerprint = if (host.isRemote) existingHost.sshMismatchFingerprint else null
                    )
                } else {
                    val cleanNewHost = host.copy(sshFingerprint = null, sshMismatchFingerprint = null)
                    if (cleanNewHost.isRemote) {
                        SSHClientPool.evict(cleanNewHost)
                    }
                    cleanNewHost
                }

            try {
                val hostConnection = HostConnection(effectiveHost)
                // get the host information
                var hostInfo = hostConnection.command("lsb_release -idrc")
                // create the node home path directory if it doesn't exist
                hostConnection.command("mkdir -p ${effectiveHost.nodeHomePath}")
                // make sure cardano-cli exists
                if (!hostConnection.commandFileExists(effectiveHost.cardanoCliPath)) {
                    throw SSHRuntimeException("File at '${effectiveHost.cardanoCliPath}' does not exist!")
                }
                val cliVersion = hostConnection.command("${effectiveHost.cardanoCliPath} --version")
                hostInfo += "       cardano-cli: $cliVersion"
                // make sure cardano-node exists
                if (!hostConnection.commandFileExists(effectiveHost.cardanoNodePath)) {
                    throw SSHRuntimeException("File at '${effectiveHost.cardanoNodePath}' does not exist!")
                }
                val nodeVersion = hostConnection.command("${effectiveHost.cardanoNodePath} --version")
                hostInfo += "      cardano-node: $nodeVersion"

                val jcliPath = effectiveHost.jcliPath
                if (!jcliPath.isNullOrBlank()) {
                    // make sure jcli exists
                    if (!hostConnection.commandFileExists(jcliPath)) {
                        throw SSHRuntimeException("File at '$jcliPath' does not exist!")
                    }
                    val jcliVersion = hostConnection.command("$jcliPath --version")
                    hostInfo += "              jcli: $jcliVersion"
                }

                val pinnedFingerprint =
                    if (effectiveHost.isRemote) {
                        hostConnection.pinnedSshFingerprint()
                            ?: existingHost?.let { hostRepository.findByIdOrNull(it.id!!)?.sshFingerprint }
                            ?: effectiveHost.sshFingerprint
                            ?: throw SSHRuntimeException("Failed to record SSH host key fingerprint for ${effectiveHost.hostname}!")
                    } else {
                        null
                    }

                if (existingHost != null) {
                    hostRepository.save(
                        existingHost.copy(
                            type = effectiveHost.type,
                            hostname = effectiveHost.hostname,
                            sshUser = effectiveHost.sshUser,
                            sshPort = effectiveHost.sshPort,
                            sshPemPath = effectiveHost.sshPemPath,
                            cardanoCliPath = effectiveHost.cardanoCliPath,
                            cardanoNodePath = effectiveHost.cardanoNodePath,
                            nodeHomePath = effectiveHost.nodeHomePath,
                            jcliPath = effectiveHost.jcliPath,
                            sshFingerprint = pinnedFingerprint,
                            sshMismatchFingerprint = null
                        )
                    )
                } else {
                    if (effectiveHost.isRemote) {
                        SSHClientPool.evict(effectiveHost)
                    }
                    hostRepository.save(
                        effectiveHost.copy(
                            sshFingerprint = pinnedFingerprint,
                            sshMismatchFingerprint = null
                        )
                    )
                }
                broadcastHosts()
                return SocketResponse.Success(type = "addhost", data = hostInfo)
            } catch (e: IOException) {
                val error = "IOException communicating with ${host.hostname}"
                log.error(error, e)
                return SocketResponse.Error(type = "addhost", exception = e)
            } catch (e: IllegalStateException) {
                log.error("IllegalStateException communicating with ${host.hostname}", e)
                return SocketResponse.Error(type = "addhost", exception = e)
            } catch (e: Throwable) {
                val error = "Fatal error communicating with ${host.hostname}!"
                log.error(error, e)
                return SocketResponse.Error(type = "addhost", exception = e)
            }
        }

        @MessageMapping("/host/fingerprint")
        @SendTo("/topic/messages")
        @Transactional
        fun updateHostFingerprint(request: HostFingerprintRequest): SocketResponse<HostFingerprintResult> {
            val responseType = "host_fingerprint_updated"
            try {
                UUID.fromString(request.requestId)
            } catch (_: IllegalArgumentException) {
                return fingerprintFailure(request.requestId, "INVALID_REQUEST", "Invalid request identifier.")
            }

            if (!walletUtils.isValidSpendingPassword(request.spendingPassword)) {
                return fingerprintFailure(request.requestId, "UNAUTHORIZED", "Invalid spending password!")
            }

            val existing =
                hostRepository.findByIdOrNull(request.hostId)
                    ?: return fingerprintFailure(request.requestId, "HOST_NOT_FOUND", "Host not found.")

            if (!existing.isRemote) {
                return fingerprintFailure(request.requestId, "INVALID_HOST_TYPE", "Local hosts do not use SSH host key fingerprints.")
            }

            return when (request.action) {
                "reset" -> {
                    val saved =
                        hostRepository.save(
                            existing.copy(
                                sshFingerprint = null,
                                sshMismatchFingerprint = null
                            )
                        )
                    SSHClientPool.evictForHost(saved.id)
                    broadcastHosts()
                    log.info("Reset SSH host key fingerprint for ${saved.hostname}:${saved.sshPort} (id=${saved.id})")
                    SocketResponse.Success(
                        type = responseType,
                        data =
                            HostFingerprintResult(
                                requestId = request.requestId,
                                hostId = requireNotNull(saved.id),
                                action = "reset",
                                sshFingerprint = null,
                                message = "SSH host key fingerprint reset for ${saved.hostname}. Next connection will record a fresh fingerprint (TOFU)."
                            )
                    )
                }

                "accept" -> {
                    val mismatchFp = existing.sshMismatchFingerprint
                    val requestedFp = request.fingerprint?.trim()
                    if (mismatchFp.isNullOrBlank()) {
                        return fingerprintFailure(
                            request.requestId,
                            "NO_ACTIVE_MISMATCH",
                            "No pending mismatched SSH fingerprint exists for ${existing.hostname}."
                        )
                    }
                    if (!requestedFp.isNullOrBlank() && !SshFingerprints.matches(mismatchFp, requestedFp)) {
                        return fingerprintFailure(
                            request.requestId,
                            "MISMATCH_CHANGED",
                            "The received SSH fingerprint changed since you opened this prompt; review the latest fingerprint before accepting."
                        )
                    }
                    if (!SshFingerprints.isValidSha256Fingerprint(mismatchFp)) {
                        return fingerprintFailure(
                            request.requestId,
                            "INVALID_FINGERPRINT",
                            "Received SSH fingerprint format is invalid."
                        )
                    }
                    val saved =
                        hostRepository.save(
                            existing.copy(
                                sshFingerprint = mismatchFp,
                                sshMismatchFingerprint = null
                            )
                        )
                    SSHClientPool.evictForHost(saved.id)
                    broadcastHosts()
                    log.info("Accepted updated SSH host key fingerprint for ${saved.hostname}:${saved.sshPort} (id=${saved.id}): $mismatchFp")
                    SocketResponse.Success(
                        type = responseType,
                        data =
                            HostFingerprintResult(
                                requestId = request.requestId,
                                hostId = requireNotNull(saved.id),
                                action = "accept",
                                sshFingerprint = saved.sshFingerprint,
                                message = "Updated trusted SSH host key fingerprint for ${saved.hostname}."
                            )
                    )
                }

                "update" -> {
                    val newFp = request.fingerprint?.trim().orEmpty()
                    if (!SshFingerprints.isValidSha256Fingerprint(newFp)) {
                        return fingerprintFailure(
                            request.requestId,
                            "INVALID_FINGERPRINT",
                            "Invalid SSH fingerprint format. Expected 'SHA256:<43-char-base64>'."
                        )
                    }
                    val saved =
                        hostRepository.save(
                            existing.copy(
                                sshFingerprint = newFp,
                                sshMismatchFingerprint = null
                            )
                        )
                    SSHClientPool.evictForHost(saved.id)
                    broadcastHosts()
                    log.info("Manually updated SSH host key fingerprint for ${saved.hostname}:${saved.sshPort} (id=${saved.id}): $newFp")
                    SocketResponse.Success(
                        type = responseType,
                        data =
                            HostFingerprintResult(
                                requestId = request.requestId,
                                hostId = requireNotNull(saved.id),
                                action = "update",
                                sshFingerprint = saved.sshFingerprint,
                                message = "Saved SSH host key fingerprint for ${saved.hostname}."
                            )
                    )
                }

                else ->
                    fingerprintFailure(
                        request.requestId,
                        "INVALID_ACTION",
                        "Unsupported fingerprint action '${request.action}'."
                    )
            }
        }

        private fun fingerprintFailure(
            requestId: String,
            code: String,
            message: String
        ): SocketResponse.Error =
            SocketResponse.Error(
                type = "host_fingerprint_updated",
                exception =
                    SocketResponse.PublicFailure(
                        code = code,
                        message = message,
                        operation = "host_fingerprint_updated",
                        retryDisposition = "abort",
                        requestId = requestId
                    )
            )
    }
