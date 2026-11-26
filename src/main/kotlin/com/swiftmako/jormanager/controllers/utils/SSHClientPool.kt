package com.swiftmako.jormanager.controllers.utils

import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.services.HostFingerprintStore
import com.swiftmako.jormanager.services.InMemoryHostFingerprintStore
import io.ktor.utils.io.pool.DefaultPool
import io.ktor.utils.io.pool.ObjectPool
import java.security.PublicKey
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SSHRuntimeException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.slf4j.LoggerFactory

class SSHClientPool(
    private val host: Host,
    private val fingerprintStore: HostFingerprintStore = currentStore(),
    private val clientFactory: () -> SSHClient = { SSHClient() }
) : ObjectPool<SSHClient> {
    private val log by lazy { LoggerFactory.getLogger("SSHClientPool") }
    private val inMemoryPinnedFingerprint = AtomicReference<String?>(host.sshFingerprint)
    private val verifiedFingerprints: MutableMap<SSHClient, String> =
        Collections.synchronizedMap(IdentityHashMap())

    private val delegatePool =
        object : DefaultPool<SSHClient>(4) {
            override fun produceInstance(): SSHClient = createVerifiedClient()

            override fun validateInstance(instance: SSHClient) = validatePooledClient(instance)

            override fun disposeInstance(instance: SSHClient) = disposeClient(instance)
        }

    override val capacity: Int
        get() = delegatePool.capacity

    override fun borrow(): SSHClient {
        repeat(capacity + 1) {
            val client = delegatePool.borrow()
            if (isClientTrusted(client)) {
                return client
            }
            disposeClient(client)
        }
        throw SSHRuntimeException("Unable to borrow a trusted SSH connection for ${host.hostname}:${host.sshPort}")
    }

    override fun recycle(instance: SSHClient) {
        delegatePool.recycle(instance)
    }

    override fun dispose() {
        delegatePool.dispose()
    }

    fun pinnedFingerprint(): String? =
        if (host.id != null) {
            fingerprintStore.resolvePinnedFingerprint(host)
        } else {
            fingerprintStore.resolvePinnedFingerprint(host) ?: inMemoryPinnedFingerprint.get()
        }

    private fun resolveExpectedFingerprint(): String? = pinnedFingerprint()

    private fun isClientTrusted(instance: SSHClient): Boolean {
        val verifiedFp = verifiedFingerprints[instance] ?: return false
        val expectedFp = resolveExpectedFingerprint() ?: return false
        return SshFingerprints.matches(expectedFp, verifiedFp)
    }

    private fun createVerifiedClient(): SSHClient {
        val capturedFingerprint = AtomicReference<String?>(null)
        val mismatchError = AtomicReference<HostKeyMismatchException?>(null)
        val client = clientFactory()
        try {
            client.addHostKeyVerifier(
                object : HostKeyVerifier {
                    override fun findExistingAlgorithms(
                        hostname: String?,
                        port: Int
                    ): MutableList<String> = Collections.emptyList()

                    override fun verify(
                        hostname: String?,
                        port: Int,
                        key: PublicKey
                    ): Boolean {
                        val actual = SshFingerprints.sha256(key)
                        val expected = resolveExpectedFingerprint()
                        if (expected == null) {
                            capturedFingerprint.set(actual)
                            return true
                        }
                        if (SshFingerprints.matches(expected, actual)) {
                            capturedFingerprint.set(actual)
                            return true
                        }
                        fingerprintStore.recordMismatch(host, expected, actual)
                        mismatchError.set(
                            HostKeyMismatchException(
                                hostId = host.id,
                                hostname = host.hostname,
                                sshPort = host.sshPort,
                                expectedFingerprint = expected,
                                receivedFingerprint = actual
                            )
                        )
                        return false
                    }
                }
            )
            client.useCompression()
            client.connect(host.hostname, host.sshPort)
            mismatchError.get()?.let { throw it }
            val verifiedFp =
                capturedFingerprint.get()
                    ?: throw SSHRuntimeException("SSH connection to ${host.hostname}:${host.sshPort} did not verify host key!")
            client.authPublickey(host.sshUser, host.sshPemPath)
            client.connection.keepAlive.keepAliveInterval = 30

            val currentExpected = fingerprintStore.resolvePinnedFingerprint(host)
            if (currentExpected == null) {
                if (host.id == null) {
                    val pinnedNow = inMemoryPinnedFingerprint.compareAndSet(null, verifiedFp)
                    if (!pinnedNow) {
                        val existingInMem = inMemoryPinnedFingerprint.get()
                        if (existingInMem != null && !SshFingerprints.matches(existingInMem, verifiedFp)) {
                            fingerprintStore.recordMismatch(host, existingInMem, verifiedFp)
                            throw HostKeyMismatchException(
                                hostId = host.id,
                                hostname = host.hostname,
                                sshPort = host.sshPort,
                                expectedFingerprint = existingInMem,
                                receivedFingerprint = verifiedFp
                            )
                        }
                    }
                }
                val persisted = fingerprintStore.recordFirstUseFingerprint(host, verifiedFp)
                inMemoryPinnedFingerprint.set(persisted.sshFingerprint ?: verifiedFp)
            } else {
                inMemoryPinnedFingerprint.set(currentExpected)
                fingerprintStore.clearMismatchIfNeeded(host)
            }
            verifiedFingerprints[client] = verifiedFp
            return client
        } catch (t: Throwable) {
            runCatching { client.close() }
            if (t is HostKeyMismatchException) {
                throw t
            }
            val mismatch = mismatchError.get()
            if (mismatch != null) {
                throw HostKeyMismatchException(
                    hostId = mismatch.hostId,
                    hostname = mismatch.hostname,
                    sshPort = mismatch.sshPort,
                    expectedFingerprint = mismatch.expectedFingerprint,
                    receivedFingerprint = mismatch.receivedFingerprint,
                    cause = t
                )
            }
            throw t
        }
    }

    private fun validatePooledClient(instance: SSHClient) {
        try {
            if (!isClientTrusted(instance)) {
                throw SSHRuntimeException("Cached SSH connection fingerprint is no longer trusted for ${host.hostname}:${host.sshPort}")
            }
            instance.startSession().use { session ->
                session.exec("pwd").use { cmd ->
                    cmd.join(5, TimeUnit.SECONDS)
                    if (cmd.exitStatus != 0) {
                        throw SSHRuntimeException("validateInstance failure: $host")
                    }
                }
            }
        } catch (e: Throwable) {
            disposeClient(instance)
            throw e
        }
    }

    private fun disposeClient(instance: SSHClient) {
        verifiedFingerprints.remove(instance)
        try {
            instance.close()
        } catch (t: Throwable) {
            log.error("Error closing SSHClient!", t)
        }
    }

    data class PoolKey(
        val hostId: Long?,
        val hostname: String,
        val sshPort: Int,
        val sshUser: String,
        val sshPemPath: String
    ) {
        companion object {
            fun from(host: Host): PoolKey =
                PoolKey(
                    hostId = host.id,
                    hostname = host.hostname,
                    sshPort = host.sshPort,
                    sshUser = host.sshUser,
                    sshPemPath = host.sshPemPath
                )
        }
    }

    companion object {
        private val storeRef = AtomicReference<HostFingerprintStore>(InMemoryHostFingerprintStore())
        private val poolMap = ConcurrentHashMap<PoolKey, SSHClientPool>()

        fun configureStore(store: HostFingerprintStore) {
            storeRef.set(store)
        }

        fun currentStore(): HostFingerprintStore = storeRef.get()

        @Synchronized
        fun getInstance(host: Host): SSHClientPool {
            val key = PoolKey.from(host)
            return poolMap[key] ?: run {
                val newPool = SSHClientPool(host)
                poolMap[key] = newPool
                newPool
            }
        }

        @Synchronized
        fun evict(host: Host) {
            val key = PoolKey.from(host)
            poolMap.remove(key)?.dispose()
        }

        @Synchronized
        fun evictForHost(hostId: Long?) {
            if (hostId == null) return
            val keysToRemove = poolMap.keys().toList().filter { it.hostId == hostId }
            keysToRemove.forEach { key ->
                poolMap.remove(key)?.dispose()
            }
        }

        @Synchronized
        fun shutdown() {
            poolMap.forEach { (_, sshClientPool) ->
                sshClientPool.dispose()
            }
            poolMap.clear()
        }
    }
}
