package com.swiftmako.jormanager.controllers

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.AuthorizedOperation
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.model.ChainReadState
import com.swiftmako.jormanager.model.Metadata
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.repositories.AuthorizedOperationRepository
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.ProtocolSnapshotRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.services.PendingWalletState
import com.swiftmako.jormanager.services.PoolRegistrationCertificateBuilder
import com.swiftmako.jormanager.services.ReviewedWalletOperations
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import java.math.BigInteger
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import tools.jackson.module.kotlin.jacksonObjectMapper

class DirectPoolTransactionActionsTest {
    private val json = jacksonObjectMapper()
    private val walletUtils = mockk<WalletUtils>()
    private val operations = mockk<AuthorizedOperationRepository>()
    private val reviewed = mockk<ReviewedWalletOperations>()
    private val nodeController = mockk<NodeController>()
    private val certificates = mockk<PoolRegistrationCertificateBuilder>()
    private val registration = ReviewedPoolRegistrationController(json, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), walletUtils, certificates, reviewed, mockk(), operations, mockk(), nodeController)
    private val retirement = ReviewedPoolRetirementController(json, mockk(), mockk(), mockk(), mockk(), mockk(), walletUtils, reviewed, mockk(), operations, mockk(), mockk())
    private val id = UUID.randomUUID().toString()

    private fun record(operation: String, payload: Any) = AuthorizedOperation(
        id, UUID.randomUUID().toString(), "network", 1, 2, operation, "conway", 1,
        "target", "submitted", "request", json.writeValueAsString(payload), "parameters", "state", "tx", "body",
        fee = BigInteger.ONE, deposit = BigInteger.ZERO, refund = BigInteger.ZERO,
    )

    @Test
    fun registrationRequiresPasswordAndReusesOnlyMatchingDurableIntent() {
        val metadata = Metadata("TEST", "Pool", "Description", "https://example.test", null)
        val input = ReviewedPoolRegistrationController.SubmitRequest(id, 1, 2, action = "update", metadata = metadata, spendingPassword = "secret")
        every { walletUtils.isValidSpendingPassword("secret") } returns false
        assertThrows<IllegalArgumentException> { registration.execute(input) }
        verify(exactly = 0) { operations.findById(any()) }
        verify(exactly = 0) { nodeController.publishPoolMetadata(any(), any(), any()) }
        every { walletUtils.isValidSpendingPassword("secret") } returns true
        val intent = mapOf("nodeId" to 1, "feePayerAccountId" to 2, "keyId" to null, "action" to "update", "changes" to null, "metadata" to metadata, "itnPrivateKeyFingerprint" to null)
        every { operations.findById(id) } returns Optional.of(record("pool.update", mapOf("intent" to intent, "nodeId" to 1, "action" to "update")))
        val result = registration.execute(input)
        assertThat(result.state).isEqualTo("submitted")
        assertThat(result.txId).isEqualTo("tx")
        assertThrows<IllegalStateException> { registration.execute(input.copy(feePayerAccountId = 3)) }
        verify(exactly = 0) { nodeController.publishPoolMetadata(any(), any(), any()) }
        verify(exactly = 0) { reviewed.submit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun retirementRequiresPasswordAndReusesOnlyMatchingDurableIntent() {
        val input = ReviewedPoolRetirementController.SubmitRequest(id, 1, 2, "500", "secret")
        every { walletUtils.isValidSpendingPassword("secret") } returns false
        assertThrows<IllegalArgumentException> { retirement.execute(input) }
        verify(exactly = 0) { operations.findById(any()) }
        every { walletUtils.isValidSpendingPassword("secret") } returns true
        val intent = mapOf("nodeId" to 1, "feePayerAccountId" to 2, "retireEpoch" to "500")
        every { operations.findById(id) } returns Optional.of(record("pool.retire", mapOf("intent" to intent, "nodeId" to 1)))
        val result = retirement.execute(input)
        assertThat(result.state).isEqualTo("submitted")
        assertThat(result.txId).isEqualTo("tx")
        assertThrows<IllegalStateException> { retirement.execute(input.copy(retireEpoch = "501")) }
        verify(exactly = 0) { reviewed.submit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun registrationRejectsClientMetadataAnchorBeforeAnySideEffect() {
        every { walletUtils.isValidSpendingPassword("secret") } returns true
        val changes = json.readTree("""{"metadata":{"url":"https://example.test","hash":"abc"}}""")
        val input = ReviewedPoolRegistrationController.SubmitRequest(id, 1, 2, action = "update", changes = changes, spendingPassword = "secret")
        assertThrows<IllegalArgumentException> { registration.execute(input) }
        verify(exactly = 0) { operations.findById(any()) }
        verify(exactly = 0) { nodeController.publishPoolMetadata(any(), any(), any()) }
        verify(exactly = 0) { reviewed.submit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun publicationFailureStopsRegistrationBeforeCertificateOrSubmission() {
        val nodes = mockk<NodeRepository>()
        val hosts = mockk<HostRepository>()
        val files = mockk<FileRepository>()
        val accounts = mockk<WalletRepository>()
        val eras = mockk<CardanoRepository>()
        val snapshots = mockk<ProtocolSnapshotRepository>()
        val jdbc = mockk<JdbcTemplate>()
        val node = mockk<Node>(relaxed = true)
        val host = mockk<Host>(relaxed = true)
        val key = File(4, "key", "key-content")
        val genesis = File(5, "genesis", """{"networkId":"mainnet"}""")
        val payer = WalletEntry(2, "payer", "payment", "address", paymentSkey = key, stakingVkey = key)
        val poolId = "a".repeat(56)
        every { node.id } returns 1L
        every { node.hostId } returns 1L
        every { node.genesisShelleyFileId } returns 5L
        every { node.coreVKeyId } returns 4L
        every { node.coreSKeyId } returns 4L
        every { node.vrfVKeyId } returns 4L
        every { node.ownerStakingAccountId } returns 2L
        every { node.rewardsStakingAccountId } returns 2L
        every { node.poolId } returns poolId
        every { host.id } returns 1L
        every { nodes.findDefault() } returns node
        every { nodes.findById(1L) } returns Optional.of(node)
        every { hosts.findById(1L) } returns Optional.of(host)
        every { files.findById(4L) } returns Optional.of(key)
        every { files.findById(5L) } returns Optional.of(genesis)
        every { accounts.findById(2L) } returns Optional.of(payer)
        every { eras.networkKey(any()) } returns "network"
        every { eras.getEra(any(), any(), any(), any()) } returns "conway"
        every { eras.evidence(any()) } returns CardanoRepository.EraEvidence("network", "conway", "current", "host:1/node:1")
        every { snapshots.query(any(), any(), any(), any(), any(), any()) } returns "{}"
        every { jdbc.query(any<String>(), any<RowMapper<Pair<String, String>>>(), *anyVararg()) } returns emptyList()
        every { walletUtils.getUtxoSnapshot(any(), any(), any(), any(), any(), any(), any(), any()) } returns PendingWalletState.Snapshot(
            ChainReadState("network", "complete", null, null, null, true, "1"),
            mapOf("address" to listOf(Utxo("a".repeat(64), 0, BigInteger.TEN, emptyList()))), emptyMap(), emptyMap())
        every { walletUtils.isValidSpendingPassword("secret") } returns true
        every { operations.findById(id) } returns Optional.empty()
        val metadata = Metadata("TEST", "Pool", "Description", "https://example.test", null)
        val failure = IllegalStateException("publication failed")
        every { nodeController.publishPoolMetadata(node, metadata, "secret") } throws failure
        val controller = ReviewedPoolRegistrationController(json, nodes, hosts, files, accounts, eras, snapshots, walletUtils, certificates, reviewed, mockk(), operations, jdbc, nodeController)
        val input = ReviewedPoolRegistrationController.SubmitRequest(id, 1, 2, action = "update", metadata = metadata, spendingPassword = "secret")
        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().commandArgs(any()) } answers {
                if ("pool-state" in firstArg<List<String>>()) """{"$poolId":{"deposit":1}}""" else """{"epoch":10}"""
            }
            assertThat(assertThrows<IllegalStateException> { controller.execute(input) }).isSameInstanceAs(failure)
            verify(exactly = 1) { nodeController.publishPoolMetadata(node, metadata, "secret") }
            verify(exactly = 0) { certificates.build(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { reviewed.submit(any(), any(), any(), any(), any(), any()) }
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }
}
