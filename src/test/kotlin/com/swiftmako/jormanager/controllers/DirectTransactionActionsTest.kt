package com.swiftmako.jormanager.controllers

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.model.QueryTip
import com.swiftmako.jormanager.model.ChainReadState
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.ProtocolSnapshotRepository
import com.swiftmako.jormanager.services.PendingWalletState
import com.swiftmako.jormanager.utils.Bech32
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.AuthorizedOperation
import com.swiftmako.jormanager.model.NodeVoteSelection
import com.swiftmako.jormanager.repositories.AuthorizedOperationRepository
import com.swiftmako.jormanager.services.ReviewedWalletOperations
import com.swiftmako.jormanager.services.WalletTransactionBuilder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigInteger
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.module.kotlin.jacksonObjectMapper

class DirectTransactionActionsTest {
    private val json = jacksonObjectMapper()
    private val wallets = mockk<WalletUtils>()
    private val operations = mockk<AuthorizedOperationRepository>()
    private val reviewed = mockk<ReviewedWalletOperations>()
    private val nodeController = mockk<NodeController>()
    private val wallet = ReviewedWalletController(json, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), wallets, reviewed, mockk(), operations)
    private val governance = GovernanceReviewController(mockk(), mockk(), mockk(), mockk(), wallets, mockk(), mockk(), reviewed, mockk(), operations, nodeController, mockk(), mockk(), json)
    private val id = UUID.randomUUID().toString()

    private fun record(payload: String, operation: String) = AuthorizedOperation(
        id, UUID.randomUUID().toString(), "network", 1, 2, operation, "conway", 1,
        "target", "submitted", "request", payload, "parameters", "state", "tx", "body",
        fee = BigInteger.ONE, deposit = BigInteger.ZERO, refund = BigInteger.ZERO,
    )

    @Test
    fun walletValidatesPasswordBeforeLookupAndReusesOnlyMatchingIntent() {
        val request = json.readTree("""{"fromId":1,"toAccounts":[],"txFee":"3"}""")
        val input = ReviewedWalletController.SubmitRequest(id, "send", request, "secret")
        every { wallets.isValidSpendingPassword("secret") } returns false
        assertThrows<IllegalArgumentException> { wallet.execute(input) }
        verify(exactly = 0) { operations.findById(any()) }
        every { wallets.isValidSpendingPassword("secret") } returns true
        val intent = mapOf("kind" to "send", "request" to request)
        every { operations.findById(id) } returns Optional.of(record(json.writeValueAsString(mapOf("intent" to intent, "kind" to "send", "request" to request)), "wallet_send"))
        assertThat(wallet.execute(input).state).isEqualTo("submitted")
        assertThrows<IllegalStateException> { wallet.execute(input.copy(request = json.readTree("""{"fromId":2,"toAccounts":[],"txFee":"3"}"""))) }
        verify(exactly = 0) { reviewed.submit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun governanceValidatesPasswordAndDoesNotRepublishOrResubmitExistingIntent() {
        val request = GovernanceReviewController.VoteRequest("action", listOf(NodeVoteSelection(1, "YES")), 2, "public rationale")
        val input = GovernanceReviewController.SubmitRequest(id, request, "secret")
        every { wallets.isValidSpendingPassword("secret") } returns false
        assertThrows<IllegalArgumentException> { governance.execute(input) }
        verify(exactly = 0) { operations.findById(any()) }
        every { wallets.isValidSpendingPassword("secret") } returns true
        every { operations.findById(id) } returns Optional.of(record(json.writeValueAsString(mapOf("intent" to request, "request" to request)), "governance.vote"))
        assertThat(governance.execute(input).state).isEqualTo("submitted")
        assertThrows<IllegalStateException> { governance.execute(input.copy(request = request.copy(rationale = "changed"))) }
        verify(exactly = 0) { nodeController.uploadGovernanceRationale(any()) }
        verify(exactly = 0) { reviewed.submit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun rationaleIsPublishedAfterValidationAndAnchoredBeforeVoteConstruction() {
        val nodes = mockk<NodeRepository>()
        val hosts = mockk<HostRepository>()
        val files = mockk<FileRepository>()
        val accounts = mockk<WalletRepository>()
        val eras = mockk<CardanoRepository>()
        val parameters = mockk<ProtocolSnapshotRepository>()
        val genesis = mockk<JsonAdapter<GenesisShelley>>()
        val tip = mockk<JsonAdapter<QueryTip>>()
        val node = mockk<Node>(relaxed = true)
        val host = mockk<Host>(relaxed = true)
        val key = File(4, "key", "key-content")
        val payer = WalletEntry(2, "payer", "payment", "address", paymentSkey = key)
        every { node.id } returns 1L
        every { node.hostId } returns 1L
        every { node.genesisShelleyFileId } returns 4L
        every { node.coreVKeyId } returns 4L
        every { node.coreSKeyId } returns 4L
        every { node.type } returns "core"
        every { host.id } returns 1L
        every { nodes.findDefault() } returns node
        every { nodes.findById(1L) } returns Optional.of(node)
        every { hosts.findById(1L) } returns Optional.of(host)
        every { files.findById(4L) } returns Optional.of(key)
        every { accounts.findById(2L) } returns Optional.of(payer)
        every { genesis.fromJson(any<String>()) } returns mockk<GenesisShelley>(relaxed = true)
        every { eras.getEra(any(), any(), any(), any()) } returns "conway"
        every { eras.networkKey(any()) } returns "network"
        every { eras.evidence(any()) } returns CardanoRepository.EraEvidence("network", "conway", "current", "host:1/node:1")
        every { parameters.query(any(), any(), any(), any(), any(), any()) } returns "{}"
        every { wallets.getUtxoSnapshot(any(), any(), any(), any(), any(), any(), any(), any()) } returns PendingWalletState.Snapshot(
            ChainReadState("network", "complete", null, null, null, true, "1"),
            mapOf("address" to listOf(Utxo("a".repeat(64), 0, BigInteger.TEN, emptyList()))), emptyMap(), emptyMap())
        every { wallets.isValidSpendingPassword("secret") } returns true
        every { operations.findById(any()) } returns Optional.empty()
        val builder = mockk<WalletTransactionBuilder>()
        val controller = GovernanceReviewController(nodes, hosts, files, accounts, wallets, eras, parameters, reviewed, builder, operations, nodeController, genesis, tip, json)
        val request = GovernanceReviewController.VoteRequest(Bech32.encode("gov_action", ByteArray(33)), listOf(NodeVoteSelection(1, "YES")), 2)
        every { tip.fromJson(any<String>()) } returns mockk<QueryTip> { every { slot } returns 100L }
        every { builder.build(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("fixture builder failure")
        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().commandArgs(any()) } answers {
                val args = firstArg<List<String>>()
                if ("gov-state" in args) "{}" else ""
            }
            every { anyConstructed<HostConnection>().commandWriteFile(any(), any()) } returns ""
            every { nodeController.uploadGovernanceRationale(null) } returns null
            every { nodeController.uploadGovernanceRationale("public") } returns ("https://example.test/rationale" to "a".repeat(64))
            every { nodeController.uploadGovernanceRationale("failure") } throws IllegalStateException("fixture publication failure")
            fun submit(rationale: String?) = controller.receiveSubmit(GovernanceReviewController.SubmitRequest(UUID.randomUUID().toString(), request.copy(rationale = rationale), "secret"))
            assertThat(submit(null)).isInstanceOf(SocketResponse.Error::class.java)
            val publishedThenFailed = submit("public")
            assertThat(publishedThenFailed).isInstanceOf(SocketResponse.Error::class.java)
            assertThat((publishedThenFailed as SocketResponse.Error).exception.message).isEqualTo("The governance action could not be completed.")
            assertThat(submit("failure")).isInstanceOf(SocketResponse.Error::class.java)
            verify(exactly = 1) { nodeController.uploadGovernanceRationale(null) }
            verify(exactly = 1) { nodeController.uploadGovernanceRationale("public") }
            verify(exactly = 1) { nodeController.uploadGovernanceRationale("failure") }
            verify(exactly = 1) { anyConstructed<HostConnection>().commandArgs(match { "vote" in it && "--anchor-url" !in it }) }
            verify(exactly = 1) { anyConstructed<HostConnection>().commandArgs(match { "vote" in it && "--anchor-url" in it && "https://example.test/rationale" in it && "a".repeat(64) in it }) }
            verify(exactly = 2) { builder.build(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { reviewed.submit(any(), any(), any(), any(), any(), any()) }
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }
    @Test
    fun changedFeeFailsBeforeSigningOrSubmission() {
        val nodes = mockk<NodeRepository>()
        val hosts = mockk<HostRepository>()
        val files = mockk<FileRepository>()
        val accounts = mockk<WalletRepository>()
        val eras = mockk<CardanoRepository>()
        val parameters = mockk<ProtocolSnapshotRepository>()
        val builder = mockk<WalletTransactionBuilder>()
        val node = mockk<Node>(relaxed = true)
        val host = mockk<Host>(relaxed = true)
        val key = File(4, "key", """{"networkId":"mainnet"}""")
        val payer = WalletEntry(2, "payer", "payment", "address", paymentSkey = key)
        every { node.id } returns 1L
        every { node.hostId } returns 1L
        every { node.genesisShelleyFileId } returns 4L
        every { node.name } returns "node"
        every { host.id } returns 1L
        every { host.nodeHomePath } returns "/node"
        every { host.cardanoCliPath } returns "cardano-cli"
        every { nodes.findDefault() } returns node
        every { hosts.findById(1L) } returns Optional.of(host)
        every { files.findById(4L) } returns Optional.of(key)
        every { accounts.findById(2L) } returns Optional.of(payer)
        every { eras.getEra(any(), any(), any(), any()) } returns "conway"
        every { eras.evidence(any()) } returns CardanoRepository.EraEvidence("network", "conway", "current", "host:1/node:1")
        every { parameters.query(any(), any(), any(), any(), any(), any()) } returns "{}"
        every { wallets.getUtxoSnapshot(any(), any(), any(), any(), any(), any(), any(), any()) } returns PendingWalletState.Snapshot(
            ChainReadState("network", "complete", null, null, null, true, "1"),
            mapOf("address" to listOf(Utxo("a".repeat(64), 0, BigInteger.TEN, emptyList()))), emptyMap(), emptyMap())
        every { wallets.isValidSpendingPassword("secret") } returns true
        every { wallets.queryReferenceScriptSize(any(), any(), any(), any(), any(), any(), any()) } returns 0L
        every { operations.findById(any()) } returns Optional.empty()
        every { builder.build(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            ReviewedWalletOperations.CliBody("""{"cborHex":"81a10204"}""", "tx")
        val controller = ReviewedWalletController(json, nodes, hosts, files, accounts, eras, parameters, wallets, reviewed, builder, operations)
        val request = json.readTree("""{"fromId":2,"toAccounts":[{"account":-1,"address":"destination","currency":"ada","amount":"1"}],"txFee":"3"}""")
        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().commandWriteFile(any(), any()) } returns ""
            every { anyConstructed<HostConnection>().commandArgs(any()) } answers {
                if ("tip" in firstArg<List<String>>()) """{"slot":100,"era":"conway","hash":"${"a".repeat(64)}"}""" else ""
            }
            val response = controller.submitMessage(ReviewedWalletController.SubmitRequest(id, "send", request, "secret"))
            assertThat(response).isInstanceOf(SocketResponse.Error::class.java)
            val failure = (response as SocketResponse.Error).exception
            assertThat(failure.code).isEqualTo("FEE_CHANGED")
            assertThat(failure.message).isEqualTo("Fee changed; refresh the form and submit again.")
            assertThat(failure.requestId).isEqualTo(id)
            verify(exactly = 0) { reviewed.preview(any()) }
            verify(exactly = 0) { reviewed.submit(any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { anyConstructed<HostConnection>().commandArgs(match { "transaction" in it && ("sign" in it || "submit" in it) }) }
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }


}
