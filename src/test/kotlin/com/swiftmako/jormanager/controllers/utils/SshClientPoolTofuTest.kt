package com.swiftmako.jormanager.controllers.utils

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.HostKeyMismatchAlert
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.services.HostKeyVerificationService
import com.swiftmako.jormanager.services.InMemoryHostFingerprintStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.FingerprintVerifier
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.xfer.scp.SCPFileTransfer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.domain.Sort
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus

class SshClientPoolTofuTest {
    private val keyA: PublicKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public
    private val keyB: PublicKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public
    private val rsaKey: PublicKey =
        KeyPairGenerator
            .getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
            .public

    private val remoteHost =
        Host(
            id = 42L,
            type = "remote",
            cardanoCliPath = "/usr/local/bin/cardano-cli",
            cardanoNodePath = "/usr/local/bin/cardano-node",
            hostname = "relay1.cardano.example",
            sshUser = "cardano",
            sshPort = 2222,
            sshPemPath = "/home/cardano/.ssh/id_ed25519",
            nodeHomePath = "/opt/cardano",
            jcliPath = null
        )

    @AfterEach
    fun tearDown() {
        SSHClientPool.shutdown()
        SSHClientPool.configureStore(InMemoryHostFingerprintStore())
    }

    private fun mockSshClientPresenting(
        presentedKeyProvider: () -> PublicKey,
        sessionOutput: String = "/home/cardano\n"
    ): Triple<SSHClient, Session, SCPFileTransfer> {
        val verifiers = CopyOnWriteArrayList<HostKeyVerifier>()
        val cmd = mockk<Session.Command>(relaxed = true) {
            every { inputStream } returns ByteArrayInputStream(sessionOutput.toByteArray(Charsets.UTF_8))
            every { errorStream } returns ByteArrayInputStream(ByteArray(0))
            every { exitStatus } returns 0
        }
        val session = mockk<Session>(relaxed = true) {
            every { exec(any()) } returns cmd
        }
        val scp = mockk<SCPFileTransfer>(relaxed = true)
        val client = mockk<SSHClient>(relaxed = true)

        every { client.startSession() } returns session
        every { client.newSCPFileTransfer() } returns scp
        every { client.addHostKeyVerifier(any<HostKeyVerifier>()) } answers {
            verifiers.add(firstArg())
        }
        every { client.connect(any<String>(), any<Int>()) } answers {
            val hostname = firstArg<String>()
            val port = secondArg<Int>()
            val presentedKey = presentedKeyProvider()
            val accepted = verifiers.any { it.verify(hostname, port, presentedKey) }
            if (!accepted) {
                throw TransportException("Host key verification failed for $hostname:$port")
            }
        }
        return Triple(client, session, scp)
    }

    @Test
    fun `sha256 fingerprint matches OpenSSH SHA256 format and SSHJ FingerprintVerifier`() {
        val edFp = SshFingerprints.sha256(keyA)
        val rsaFp = SshFingerprints.sha256(rsaKey)

        assertThat(edFp).startsWith("SHA256:")
        assertThat(edFp.length).isEqualTo(50)
        assertThat(SshFingerprints.isValidSha256Fingerprint(edFp)).isTrue()
        assertThat(SshFingerprints.isValidSha256Fingerprint(rsaFp)).isTrue()
        assertThat(SshFingerprints.isValidSha256Fingerprint("MD5:aa:bb:cc")).isFalse()
        assertThat(SshFingerprints.isValidSha256Fingerprint("SHA256:not-43-chars")).isFalse()

        assertThat(FingerprintVerifier.getInstance(edFp).verify("relay1.cardano.example", 22, keyA)).isTrue()
        assertThat(FingerprintVerifier.getInstance(edFp).verify("relay1.cardano.example", 22, keyB)).isFalse()
        assertThat(SshFingerprints.matches(edFp, edFp)).isTrue()
        assertThat(SshFingerprints.matches(edFp, rsaFp)).isFalse()
    }

    @Test
    fun `initial SSH connection pins TOFU fingerprint without loading OS known_hosts and validates subsequent connections`() {
        val store = InMemoryHostFingerprintStore()
        var currentPresentedKey = keyA
        val createdClients = mutableListOf<SSHClient>()

        val pool =
            SSHClientPool(
                host = remoteHost,
                fingerprintStore = store,
                clientFactory = {
                    val (client, _, _) = mockSshClientPresenting({ currentPresentedKey })
                    createdClients.add(client)
                    client
                }
            )

        val expectedFp = SshFingerprints.sha256(keyA)
        assertThat(store.resolvePinnedFingerprint(remoteHost)).isNull()

        val firstClient = pool.borrow()
        assertThat(store.resolvePinnedFingerprint(remoteHost)).isEqualTo(expectedFp)
        assertThat(pool.pinnedFingerprint()).isEqualTo(expectedFp)
        verify(exactly = 0) { firstClient.loadKnownHosts() }
        verify(exactly = 1) { firstClient.authPublickey(remoteHost.sshUser, remoteHost.sshPemPath) }

        // Borrow a second concurrent connection while firstClient is still borrowed; must validate against pinned keyA
        val secondClient = pool.borrow()
        assertThat(createdClients).hasSize(2)
        verify(exactly = 1) { secondClient.authPublickey(remoteHost.sshUser, remoteHost.sshPemPath) }

        pool.recycle(firstClient)
        pool.recycle(secondClient)
        pool.dispose()
    }

    @Test
    fun `mismatched SSH host key blocks connection prior to auth, session execution, and SCP file transfer`() {
        val expectedFp = SshFingerprints.sha256(keyA)
        val attackerFp = SshFingerprints.sha256(keyB)
        val store = InMemoryHostFingerprintStore().also { it.setPinned(remoteHost, expectedFp) }
        SSHClientPool.configureStore(store)

        val (attackerClient, session, scp) = mockSshClientPresenting({ keyB })
        val pool =
            SSHClientPool(
                host = remoteHost.copy(sshFingerprint = expectedFp),
                fingerprintStore = store,
                clientFactory = { attackerClient }
            )

        val mismatch = assertThrows(HostKeyMismatchException::class.java) {
            pool.borrow()
        }

        assertThat(mismatch.hostId).isEqualTo(42L)
        assertThat(mismatch.expectedFingerprint).isEqualTo(expectedFp)
        assertThat(mismatch.receivedFingerprint).isEqualTo(attackerFp)
        assertThat(store.getMismatch(remoteHost)).isEqualTo(attackerFp)
        // Store must NOT overwrite trusted fingerprint with attacker's key
        assertThat(store.resolvePinnedFingerprint(remoteHost)).isEqualTo(expectedFp)

        // Connection must be aborted BEFORE authentication, command execution, or SCP file transfer
        verify(exactly = 0) { attackerClient.authPublickey(any<String>(), any<String>()) }
        verify(exactly = 0) { attackerClient.startSession() }
        verify(exactly = 0) { session.exec(any()) }
        verify(exactly = 0) { attackerClient.newSCPFileTransfer() }
        verify(exactly = 0) { scp.upload(any<String>(), any<String>()) }
        verify(exactly = 0) { scp.download(any<String>(), any<String>()) }
        verify(atLeast = 1) { attackerClient.close() }
    }

    @Test
    fun `HostConnection aborts remote command and SCP transfers on host key mismatch`() {
        val expectedFp = SshFingerprints.sha256(keyA)
        val store = InMemoryHostFingerprintStore().also { it.setPinned(remoteHost, expectedFp) }
        val (attackerClient, session, scp) = mockSshClientPresenting({ keyB })

        // Install a pool factory state by registering a custom store and pre-populating pool via subclass/direct check
        SSHClientPool.configureStore(store)
        val pinnedHost = remoteHost.copy(sshFingerprint = expectedFp)

        // Replace SSHClientPool instance in poolMap via mockkConstructor on SSHClient? No, test HostConnection with real SSHClientPool
        // pointing to a closed local port vs custom pool: let's test HostConnection by mocking SSHClientPool.getInstance
        io.mockk.mockkObject(SSHClientPool.Companion)
        try {
            val pool =
                SSHClientPool(
                    host = pinnedHost,
                    fingerprintStore = store,
                    clientFactory = { attackerClient }
                )
            every { SSHClientPool.getInstance(any()) } returns pool

            val connection = HostConnection(pinnedHost)

            assertThrows(HostKeyMismatchException::class.java) {
                connection.command("uname -a")
            }
            assertThrows(HostKeyMismatchException::class.java) {
                connection.bashCommand("cat /etc/hostname")
            }
            assertThrows(HostKeyMismatchException::class.java) {
                connection.sudoCommand("systemctl restart cardano-node", "sudo-pass")
            }
            assertThrows(HostKeyMismatchException::class.java) {
                connection.commandReadFile("/opt/cardano/kes.skey")
            }
            val secretPath = "/tmp/kes-rotation-test-secret"
            assertThrows(HostKeyMismatchException::class.java) {
                connection.commandWriteSecret(secretPath, "secret-kes-material")
            }
            assertThat(Files.exists(Path.of(secretPath))).isFalse()

            verify(exactly = 0) { attackerClient.authPublickey(any<String>(), any<String>()) }
            verify(exactly = 0) { session.exec(any()) }
            verify(exactly = 0) { scp.download(any<String>(), any<String>()) }
            verify(exactly = 0) { scp.upload(any<String>(), any<String>()) }
            pool.dispose()
        } finally {
            io.mockk.unmockkObject(SSHClientPool.Companion)
        }
    }

    @Test
    fun `pooled SSHClient is invalidated immediately when host fingerprint is reset or updated`() {
        val store = InMemoryHostFingerprintStore()
        var currentServerKey = keyA
        val createdClients = mutableListOf<SSHClient>()

        val pool =
            SSHClientPool(
                host = remoteHost,
                fingerprintStore = store,
                clientFactory = {
                    val (client, _, _) = mockSshClientPresenting({ currentServerKey })
                    createdClients.add(client)
                    client
                }
            )

        val first = pool.borrow()
        assertThat(store.resolvePinnedFingerprint(remoteHost)).isEqualTo(SshFingerprints.sha256(keyA))
        pool.recycle(first)

        // Operator resets the fingerprint and rotates the server key to keyB
        store.setPinned(remoteHost, null)
        currentServerKey = keyB

        val second = pool.borrow()
        assertThat(second).isNotSameInstanceAs(first)
        verify(atLeast = 1) { first.close() }
        assertThat(store.resolvePinnedFingerprint(remoteHost)).isEqualTo(SshFingerprints.sha256(keyB))
        pool.recycle(second)
        pool.dispose()
    }

    @Test
    fun `HostKeyVerificationService persists TOFU fingerprint, records mismatches, and guards concurrent races`() {
        val hostRepository = mockk<HostRepository>()
        val messagingTemplate = mockk<SimpMessagingTemplate>(relaxed = true)
        val templateProvider = mockk<ObjectProvider<SimpMessagingTemplate>> {
            every { ifAvailable } returns messagingTemplate
        }
        val txManager =
            object : PlatformTransactionManager {
                override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()

                override fun commit(status: TransactionStatus) {}

                override fun rollback(status: TransactionStatus) {}
            }

        var dbHost = remoteHost.copy(sshFingerprint = null, sshMismatchFingerprint = null)
        every { hostRepository.findById(42L) } answers { Optional.of(dbHost) }
        val savedSlot = slot<Host>()
        every { hostRepository.saveAndFlush(capture(savedSlot)) } answers {
            dbHost = savedSlot.captured
            dbHost
        }
        every { hostRepository.findAll(any<Sort>()) } answers { listOf(dbHost) }

        val service = HostKeyVerificationService(hostRepository, txManager, templateProvider)
        val fpA = SshFingerprints.sha256(keyA)
        val fpB = SshFingerprints.sha256(keyB)

        // 1. Initial TOFU pin persists fingerprint to DB and broadcasts hosts
        val pinned = service.recordFirstUseFingerprint(remoteHost, fpA)
        assertThat(pinned.sshFingerprint).isEqualTo(fpA)
        assertThat(dbHost.sshFingerprint).isEqualTo(fpA)
        assertThat(service.resolvePinnedFingerprint(remoteHost)).isEqualTo(fpA)

        // 2. Concurrent first-use race with a different key throws HostKeyMismatchException and preserves fpA
        assertThrows(HostKeyMismatchException::class.java) {
            service.recordFirstUseFingerprint(remoteHost, fpB)
        }
        assertThat(dbHost.sshFingerprint).isEqualTo(fpA)
        assertThat(dbHost.sshMismatchFingerprint).isEqualTo(fpB)

        val broadcastPayloads = mutableListOf<Any>()
        every { messagingTemplate.convertAndSend("/topic/messages", capture(broadcastPayloads)) } returns Unit

        // 3. Explicit mismatch alert broadcast includes both stored and received fingerprints
        service.recordMismatch(remoteHost, fpA, fpB)
        val mismatchBroadcast =
            broadcastPayloads
                .filterIsInstance<SocketResponse.Success<*>>()
                .first { it.type == "host_key_mismatch" }
                .data as HostKeyMismatchAlert
        assertThat(mismatchBroadcast.expectedFingerprint).isEqualTo(fpA)
        assertThat(mismatchBroadcast.receivedFingerprint).isEqualTo(fpB)

        // 4. Subsequent verified connection clears stale mismatch marker
        service.clearMismatchIfNeeded(remoteHost)
        assertThat(dbHost.sshMismatchFingerprint).isNull()
        assertThat(dbHost.sshFingerprint).isEqualTo(fpA)
    }
}
