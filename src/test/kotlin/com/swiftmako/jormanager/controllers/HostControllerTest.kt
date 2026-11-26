package com.swiftmako.jormanager.controllers

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.HostKeyMismatchException
import com.swiftmako.jormanager.controllers.utils.SshFingerprints
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.HostFingerprintRequest
import com.swiftmako.jormanager.repositories.HostRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.verify
import java.security.KeyPairGenerator
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.domain.Sort
import org.springframework.messaging.simp.SimpMessagingTemplate

class HostControllerTest {
    private val fpA = SshFingerprints.sha256(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public)
    private val fpB = SshFingerprints.sha256(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public)

    private val baseRemoteHost =
        Host(
            id = null,
            type = "remote",
            cardanoCliPath = "/usr/local/bin/cardano-cli",
            cardanoNodePath = "/usr/local/bin/cardano-node",
            hostname = "core.pool.example",
            sshUser = "ubuntu",
            sshPort = 22,
            sshPemPath = "/home/ubuntu/.ssh/id_ed25519",
            nodeHomePath = "/opt/cardano",
            jcliPath = null
        )

    @AfterEach
    fun tearDown() {
        unmockkConstructor(HostConnection::class)
    }

    private fun setupController(): Triple<HostController, HostRepository, WalletUtils> {
        val hostRepository = mockk<HostRepository>()
        val walletUtils = mockk<WalletUtils>()
        val messaging = mockk<SimpMessagingTemplate>(relaxed = true)
        val provider = mockk<ObjectProvider<SimpMessagingTemplate>> {
            every { ifAvailable } returns messaging
        }
        every { hostRepository.findAll(any<Sort>()) } returns emptyList()
        return Triple(HostController(hostRepository, walletUtils, provider), hostRepository, walletUtils)
    }

    @Test
    fun `addHost stores TOFU SSH fingerprint when onboarding a new remote host`() {
        val (controller, hostRepository, _) = setupController()
        val savedSlot = slot<Host>()
        every { hostRepository.save(capture(savedSlot)) } answers { savedSlot.captured.copy(id = 10L) }

        mockkConstructor(HostConnection::class)
        every { anyConstructed<HostConnection>().command(any<String>()) } returns "Ubuntu 24.04"
        every { anyConstructed<HostConnection>().commandFileExists(any()) } returns true
        every { anyConstructed<HostConnection>().pinnedSshFingerprint() } returns fpA

        val response = controller.addHost(baseRemoteHost)
        assertThat(response).isInstanceOf(SocketResponse.Success::class.java)
        assertThat(savedSlot.captured.sshFingerprint).isEqualTo(fpA)
        assertThat(savedSlot.captured.sshMismatchFingerprint).isNull()
    }

    @Test
    fun `addHost preserves existing database-pinned fingerprint on edit and surfaces HOST_KEY_MISMATCH on key change`() {
        val (controller, hostRepository, _) = setupController()
        val existing = baseRemoteHost.copy(id = 7L, sshFingerprint = fpA, sshMismatchFingerprint = null)
        every { hostRepository.findById(7L) } returns Optional.of(existing)

        mockkConstructor(HostConnection::class)
        every { anyConstructed<HostConnection>().command(any<String>()) } throws
            HostKeyMismatchException(
                hostId = 7L,
                hostname = existing.hostname,
                sshPort = existing.sshPort,
                expectedFingerprint = fpA,
                receivedFingerprint = fpB
            )

        // Client submits edit payload without sshFingerprint; backend must still enforce existing fpA and block save
        val response = controller.addHost(existing.copy(nodeHomePath = "/opt/cardano-new", sshFingerprint = null))
        assertThat(response).isInstanceOf(SocketResponse.Error::class.java)
        val failure = (response as SocketResponse.Error).exception
        assertThat(failure.code).isEqualTo("HOST_KEY_MISMATCH")
        assertThat(failure.retryDisposition).isEqualTo("abort")
        assertThat(failure.message).contains(fpA)
        assertThat(failure.message).contains(fpB)
        verify(exactly = 0) { hostRepository.save(any()) }
    }

    @Test
    fun `updateHostFingerprint requires valid spending password and supports accept, reset, and update`() {
        val (controller, hostRepository, walletUtils) = setupController()
        var currentHost = baseRemoteHost.copy(id = 9L, sshFingerprint = fpA, sshMismatchFingerprint = fpB)
        every { hostRepository.findById(9L) } answers { Optional.of(currentHost) }
        val savedSlot = slot<Host>()
        every { hostRepository.save(capture(savedSlot)) } answers {
            currentHost = savedSlot.captured
            currentHost
        }

        every { walletUtils.isValidSpendingPassword("wrong") } returns false
        every { walletUtils.isValidSpendingPassword("correct") } returns true

        // 1. Unauthorized password rejected
        val unauthorized =
            controller.updateHostFingerprint(
                HostFingerprintRequest(UUID.randomUUID().toString(), 9L, "accept", fpB, "wrong")
            )
        assertThat(unauthorized).isInstanceOf(SocketResponse.Error::class.java)
        assertThat((unauthorized as SocketResponse.Error).exception.code).isEqualTo("UNAUTHORIZED")
        assertThat(currentHost.sshFingerprint).isEqualTo(fpA)

        // 2. TOCTOU guard rejects accepting if mismatch changed since operator opened prompt
        val staleAccept =
            controller.updateHostFingerprint(
                HostFingerprintRequest(UUID.randomUUID().toString(), 9L, "accept", fpA, "correct")
            )
        assertThat(staleAccept).isInstanceOf(SocketResponse.Error::class.java)
        assertThat((staleAccept as SocketResponse.Error).exception.code).isEqualTo("MISMATCH_CHANGED")

        // 3. Authorized accept updates sshFingerprint to fpB and clears mismatch
        val accepted =
            controller.updateHostFingerprint(
                HostFingerprintRequest(UUID.randomUUID().toString(), 9L, "accept", fpB, "correct")
            )
        assertThat(accepted).isInstanceOf(SocketResponse.Success::class.java)
        assertThat(currentHost.sshFingerprint).isEqualTo(fpB)
        assertThat(currentHost.sshMismatchFingerprint).isNull()

        // 4. Authorized reset clears saved fingerprint for fresh TOFU
        val reset =
            controller.updateHostFingerprint(
                HostFingerprintRequest(UUID.randomUUID().toString(), 9L, "reset", null, "correct")
            )
        assertThat(reset).isInstanceOf(SocketResponse.Success::class.java)
        assertThat(currentHost.sshFingerprint).isNull()
        assertThat(currentHost.sshMismatchFingerprint).isNull()

        // 5. Authorized manual update validates SHA256 format
        val invalidManual =
            controller.updateHostFingerprint(
                HostFingerprintRequest(UUID.randomUUID().toString(), 9L, "update", "invalid-fp", "correct")
            )
        assertThat(invalidManual).isInstanceOf(SocketResponse.Error::class.java)
        assertThat((invalidManual as SocketResponse.Error).exception.code).isEqualTo("INVALID_FINGERPRINT")

        val validManual =
            controller.updateHostFingerprint(
                HostFingerprintRequest(UUID.randomUUID().toString(), 9L, "update", fpA, "correct")
            )
        assertThat(validManual).isInstanceOf(SocketResponse.Success::class.java)
        assertThat(currentHost.sshFingerprint).isEqualTo(fpA)
    }
}
