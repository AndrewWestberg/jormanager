package com.swiftmako.jormanager.services

import com.swiftmako.jormanager.controllers.utils.HostKeyMismatchException
import com.swiftmako.jormanager.controllers.utils.SSHClientPool
import com.swiftmako.jormanager.controllers.utils.SshFingerprints
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.HostKeyMismatchAlert
import com.swiftmako.jormanager.repositories.HostRepository
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Lazy
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

interface HostFingerprintStore {
    fun resolvePinnedFingerprint(host: Host): String?

    fun recordFirstUseFingerprint(
        host: Host,
        fingerprint: String
    ): Host

    fun recordMismatch(
        host: Host,
        expectedFingerprint: String,
        receivedFingerprint: String
    )

    fun clearMismatchIfNeeded(host: Host)
}

class InMemoryHostFingerprintStore : HostFingerprintStore {
    private val pinnedByHostKey = ConcurrentHashMap<String, String>()
    private val mismatchByHostKey = ConcurrentHashMap<String, String>()

    private fun keyFor(host: Host): String = host.id?.let { "id:$it" } ?: "addr:${host.hostname}:${host.sshPort}"

    override fun resolvePinnedFingerprint(host: Host): String? = pinnedByHostKey[keyFor(host)] ?: host.sshFingerprint

    override fun recordFirstUseFingerprint(
        host: Host,
        fingerprint: String
    ): Host {
        val key = keyFor(host)
        val existing = pinnedByHostKey.putIfAbsent(key, fingerprint)
        if (existing != null && !SshFingerprints.matches(existing, fingerprint)) {
            mismatchByHostKey[key] = fingerprint
            throw HostKeyMismatchException(
                hostId = host.id,
                hostname = host.hostname,
                sshPort = host.sshPort,
                expectedFingerprint = existing,
                receivedFingerprint = fingerprint
            )
        }
        mismatchByHostKey.remove(key)
        return host.copy(sshFingerprint = existing ?: fingerprint, sshMismatchFingerprint = null)
    }

    override fun recordMismatch(
        host: Host,
        expectedFingerprint: String,
        receivedFingerprint: String
    ) {
        mismatchByHostKey[keyFor(host)] = receivedFingerprint
    }

    override fun clearMismatchIfNeeded(host: Host) {
        mismatchByHostKey.remove(keyFor(host))
    }

    fun getMismatch(host: Host): String? = mismatchByHostKey[keyFor(host)]

    fun setPinned(
        host: Host,
        fingerprint: String?
    ) {
        val key = keyFor(host)
        if (fingerprint == null) {
            pinnedByHostKey.remove(key)
        } else {
            pinnedByHostKey[key] = fingerprint
        }
        mismatchByHostKey.remove(key)
    }
}

@Service
@Lazy(false)
class HostKeyVerificationService(
    private val hostRepository: HostRepository,
    transactionManager: PlatformTransactionManager,
    private val webSocketTemplateProvider: ObjectProvider<SimpMessagingTemplate>
) : HostFingerprintStore {
    private val log by lazy { LoggerFactory.getLogger("HostKeyVerificationService") }

    private val requiresNewTx =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    private val readOnlyRequiresNewTx =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            isReadOnly = true
        }

    init {
        SSHClientPool.configureStore(this)
    }

    override fun resolvePinnedFingerprint(host: Host): String? {
        val hostId = host.id ?: return host.sshFingerprint
        return readOnlyRequiresNewTx.execute {
            hostRepository.findByIdOrNull(hostId)?.sshFingerprint
        }
    }

    override fun recordFirstUseFingerprint(
        host: Host,
        fingerprint: String
    ): Host {
        val hostId = host.id ?: return host.copy(sshFingerprint = fingerprint, sshMismatchFingerprint = null)
        var broadcastHosts: List<Host>? = null
        var mismatchToBroadcast: HostKeyMismatchAlert? = null
        var raceException: HostKeyMismatchException? = null

        val resultHost =
            requiresNewTx.execute {
                val current = hostRepository.findByIdOrNull(hostId)
                if (current == null) {
                    host.copy(sshFingerprint = fingerprint, sshMismatchFingerprint = null)
                } else {
                    val existingFp = current.sshFingerprint
                    if (existingFp == null) {
                        val saved =
                            hostRepository.saveAndFlush(
                                current.copy(
                                    sshFingerprint = fingerprint,
                                    sshMismatchFingerprint = null
                                )
                            )
                        broadcastHosts = hostRepository.findAll(Sort.by(Sort.Direction.ASC, "hostname"))
                        log.info("Pinned TOFU SSH host key fingerprint for ${saved.hostname}:${saved.sshPort}: $fingerprint")
                        saved
                    } else if (SshFingerprints.matches(existingFp, fingerprint)) {
                        if (current.sshMismatchFingerprint != null) {
                            val cleared = hostRepository.saveAndFlush(current.copy(sshMismatchFingerprint = null))
                            broadcastHosts = hostRepository.findAll(Sort.by(Sort.Direction.ASC, "hostname"))
                            cleared
                        } else {
                            current
                        }
                    } else {
                        val updated =
                            hostRepository.saveAndFlush(
                                current.copy(sshMismatchFingerprint = fingerprint)
                            )
                        broadcastHosts = hostRepository.findAll(Sort.by(Sort.Direction.ASC, "hostname"))
                        val message =
                            HostKeyMismatchException.buildMessage(
                                updated.hostname,
                                updated.sshPort,
                                existingFp,
                                fingerprint
                            )
                        mismatchToBroadcast =
                            HostKeyMismatchAlert(
                                hostId = updated.id,
                                hostname = updated.hostname,
                                sshPort = updated.sshPort,
                                expectedFingerprint = existingFp,
                                receivedFingerprint = fingerprint,
                                message = message
                            )
                        raceException =
                            HostKeyMismatchException(
                                hostId = updated.id,
                                hostname = updated.hostname,
                                sshPort = updated.sshPort,
                                expectedFingerprint = existingFp,
                                receivedFingerprint = fingerprint
                            )
                        updated
                    }
                }
            }

        broadcastHosts?.let { publishHosts(it) }
        mismatchToBroadcast?.let { publishMismatchAlert(it) }
        raceException?.let { throw it }
        return resultHost
    }

    override fun recordMismatch(
        host: Host,
        expectedFingerprint: String,
        receivedFingerprint: String
    ) {
        log.error(
            "SSH host key mismatch detected for ${host.hostname}:${host.sshPort} (id=${host.id}): expected=$expectedFingerprint, received=$receivedFingerprint"
        )
        var broadcastHosts: List<Host>? = null
        val hostId = host.id
        if (hostId != null) {
            requiresNewTx.execute {
                val current = hostRepository.findByIdOrNull(hostId)
                if (current != null && current.sshMismatchFingerprint != receivedFingerprint) {
                    hostRepository.saveAndFlush(current.copy(sshMismatchFingerprint = receivedFingerprint))
                }
                broadcastHosts = hostRepository.findAll(Sort.by(Sort.Direction.ASC, "hostname"))
            }
        }
        broadcastHosts?.let { publishHosts(it) }
        val alert =
            HostKeyMismatchAlert(
                hostId = hostId,
                hostname = host.hostname,
                sshPort = host.sshPort,
                expectedFingerprint = expectedFingerprint,
                receivedFingerprint = receivedFingerprint,
                message =
                    HostKeyMismatchException.buildMessage(
                        host.hostname,
                        host.sshPort,
                        expectedFingerprint,
                        receivedFingerprint
                    )
            )
        publishMismatchAlert(alert)
    }

    override fun clearMismatchIfNeeded(host: Host) {
        val hostId = host.id ?: return
        var broadcastHosts: List<Host>? = null
        requiresNewTx.execute {
            val current = hostRepository.findByIdOrNull(hostId)
            if (current != null && current.sshMismatchFingerprint != null) {
                hostRepository.saveAndFlush(current.copy(sshMismatchFingerprint = null))
                broadcastHosts = hostRepository.findAll(Sort.by(Sort.Direction.ASC, "hostname"))
            }
        }
        broadcastHosts?.let { publishHosts(it) }
    }

    private fun publishHosts(hosts: List<Host>) {
        runCatching {
            webSocketTemplateProvider.ifAvailable?.convertAndSend(
                "/topic/messages",
                SocketResponse.Success(type = "hosts", data = hosts)
            )
        }.onFailure { e ->
            log.warn("Unable to broadcast updated hosts list", e)
        }
    }

    private fun publishMismatchAlert(alert: HostKeyMismatchAlert) {
        runCatching {
            webSocketTemplateProvider.ifAvailable?.convertAndSend(
                "/topic/messages",
                SocketResponse.Success(type = "host_key_mismatch", data = alert)
            )
        }.onFailure { e ->
            log.warn("Unable to broadcast host_key_mismatch alert", e)
        }
    }
}
