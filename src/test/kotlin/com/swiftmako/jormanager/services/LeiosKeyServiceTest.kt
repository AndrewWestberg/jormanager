package com.swiftmako.jormanager.services

import com.swiftmako.jormanager.controllers.LeiosController
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.model.LeiosKeyRequest
import com.swiftmako.jormanager.repositories.*
import io.mockk.*
import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.jacksonObjectMapper

class LeiosKeyServiceTest {
    @Test
    fun `invalid claimed key proof and changed target never persist signing material`() {
        val wallet = mockk<WalletUtils>()
        val wallets = mockk<WalletRepository>()
        val files = mockk<FileRepository>()
        val keys = mockk<LeiosKeyRepository>()
        val nodes = mockk<NodeRepository>()
        val eras = mockk<CardanoRepository>()
        val host = mockk<Host>(relaxed = true)
        val node = mockk<Node>(relaxed = true)
        val publicKey = "01".repeat(96)
        val proof = "02".repeat(48)
        val directory = "/tmp/jormanager-leios-fixture"
        var certificateProof = "5830$proof"
        val evidence = CardanoRepository.EraEvidence("network", "dijkstra", "current", "host:1/node:2", generation = 1)
        every { wallet.isValidSpendingPassword("password") } returns true
        every { wallet.encryptSKeyContent(any(), "password") } returns "encrypted"
        every { host.id } returns 1L
        every { host.cardanoCliPath } returns "/bin/cardano-cli"
        every { node.id } returns 2L
        every { node.hostId } returns 1L
        every { node.type } returns "core"
        every { node.poolId } returns "pool"
        every { node.coreVKeyId } returns 3L
        every { node.vrfVKeyId } returns 4L
        every { node.ownerStakingAccountId } returns 5L
        every { node.rewardsStakingAccountId } returns 6L
        every { node.genesisShelleyFileId } returns 7L
        every { nodes.findById(2L) } returns Optional.of(node)
        every { eras.networkKey(node) } returns "network"
        every { eras.evidence(node) } returns evidence
        every { wallets.findById(any()) } returns Optional.of(WalletEntry(5, "owner", "payment", "address", stakingVkey = File(9, "stake", "{}")))
        every { files.findById(any()) } answers { Optional.of(File(firstArg(), "public", """{"networkId":"Testnet","networkMagic":42}""")) }
        val service = LeiosKeyService(wallet, wallets, files, keys, nodes, mockk(), eras, jacksonObjectMapper(), mockk())
        fun request(public: String? = null, claimedProof: String? = null) = LeiosKeyRequest(
            "request", LeiosController.Target(1, 2, "pool", "network"), "prepare", "password",
            publicKeyHex = public, proofOfPossessionHex = claimedProof)
        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().command(any<List<String>>()) } answers {
                val args = firstArg<List<String>>()
                when {
                    args.first() == "mktemp" -> directory
                    args == listOf("cat", "$directory/derived.vkey") || args == listOf("cat", "$directory/generated.vkey") -> """{"cborHex":"5860$publicKey"}"""
                    args == listOf("cat", "$directory/pool.cert") -> """{"cborHex":"8b030000825860$publicKey${certificateProof}00000000000000"}"""
                    args == listOf("cat", "$directory/bls.skey") -> "secret-envelope"
                    else -> ""
                }
            }
            every { anyConstructed<HostConnection>().commandWriteFile(any(), any()) } returns ""
            assertThrows<IllegalArgumentException> { service.prepare(request(public = "03".repeat(96)), host, node) }
            assertThrows<IllegalArgumentException> { service.prepare(request(claimedProof = "03".repeat(48)), host, node) }
            certificateProof = "582f${"02".repeat(47)}"
            assertThrows<IllegalArgumentException> { service.prepare(request(), host, node) }
            certificateProof = "5830$proof"
            every { eras.evidence(node) } returnsMany listOf(evidence, evidence.copy(generation = 2))
            assertEquals("Key target evidence changed", assertThrows<IllegalStateException> { service.prepare(request(), host, node) }.message)
            verify(exactly = 0) { files.save(any()) }
            verify(exactly = 0) { keys.save(any()) }
            verify(exactly = 4) { anyConstructed<HostConnection>().command(listOf("rm", "-rf", directory)) }
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }
}
