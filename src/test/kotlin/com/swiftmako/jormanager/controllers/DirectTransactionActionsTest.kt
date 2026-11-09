package com.swiftmako.jormanager.controllers

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.model.NodeVoteSelection
import com.swiftmako.jormanager.model.QueryTip
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.ProtocolParametersRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.services.WalletTransactionBuilder
import com.swiftmako.jormanager.utils.Bech32
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
import tools.jackson.module.kotlin.jacksonObjectMapper

class DirectTransactionActionsTest {
    private val json = jacksonObjectMapper()
    private val wallets = mockk<WalletUtils>()
    private val nodeController = mockk<NodeController>()

    @Test
    fun governanceValidatesPasswordBeforeBuilding() {
        val ledger = mockk<LedgerDao>()
        val governance = GovernanceController(mockk(), mockk(), mockk(), mockk(), wallets, mockk(), mockk(), mockk(), ledger, nodeController, mockk(), mockk(), json)
        val request = GovernanceController.VoteRequest("action", listOf(NodeVoteSelection(1, "YES")), 2, "public rationale")
        val input = GovernanceController.SubmitRequest(UUID.randomUUID().toString(), request, "secret")
        every { wallets.isValidSpendingPassword("secret") } returns false
        assertThrows<IllegalArgumentException> { governance.execute(input) }
        verify(exactly = 0) { ledger.withSubmissionLock<GovernanceController.Result>(any()) }
        verify(exactly = 0) { nodeController.uploadGovernanceRationale(any()) }
    }

    @Test
    fun rationaleIsPublishedAfterValidationAndAnchoredBeforeVoteConstruction() {
        val nodes = mockk<NodeRepository>()
        val hosts = mockk<HostRepository>()
        val files = mockk<FileRepository>()
        val accounts = mockk<WalletRepository>()
        val eras = mockk<CardanoRepository>()
        val parameters = mockk<ProtocolParametersRepository>()
        val ledger = mockk<LedgerDao>()
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
        every { parameters.query(any(), any(), any(), any(), any(), any()) } returns "{}"
        every { wallets.getUtxos(any(), any(), any(), any(), any(), any()) } returns
            listOf(Utxo("a".repeat(64), 0, BigInteger.TEN, emptyList()))
        every { wallets.queryReferenceScriptSize(any(), any(), any(), any(), any(), any()) } returns 0L
        every { wallets.isValidSpendingPassword("secret") } returns true
        every { ledger.withSubmissionLock<GovernanceController.Result>(any()) } answers { firstArg<() -> GovernanceController.Result>().invoke() }
        val builder = mockk<WalletTransactionBuilder>()
        val controller = GovernanceController(nodes, hosts, files, accounts, wallets, eras, parameters, builder, ledger, nodeController, genesis, tip, json)
        val request = GovernanceController.VoteRequest(Bech32.encode("gov_action", ByteArray(33)), listOf(NodeVoteSelection(1, "YES")), 2)
        every { tip.fromJson(any<String>()) } returns mockk<QueryTip> { every { slot } returns 100L }
        every { builder.build(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws IllegalStateException("fixture builder failure")
        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().commandArgs(any()) } returns ""
            every { anyConstructed<HostConnection>().commandWriteFile(any(), any()) } returns ""
            every { nodeController.uploadGovernanceRationale(null) } returns null
            every { nodeController.uploadGovernanceRationale("public") } returns ("https://example.test/rationale" to "a".repeat(64))
            every { nodeController.uploadGovernanceRationale("failure") } throws IllegalStateException("fixture publication failure")
            fun submit(rationale: String?) = controller.receiveSubmit(GovernanceController.SubmitRequest(UUID.randomUUID().toString(), request.copy(rationale = rationale), "secret"))
            assertThat(submit(null)).isInstanceOf(SocketResponse.Error::class.java)
            assertThat(submit("public")).isInstanceOf(SocketResponse.Error::class.java)
            assertThat(submit("failure")).isInstanceOf(SocketResponse.Error::class.java)
            verify(exactly = 1) { nodeController.uploadGovernanceRationale(null) }
            verify(exactly = 1) { nodeController.uploadGovernanceRationale("public") }
            verify(exactly = 1) { nodeController.uploadGovernanceRationale("failure") }
            verify(exactly = 1) { anyConstructed<HostConnection>().commandArgs(match { "vote" in it && "--anchor-url" !in it }) }
            verify(exactly = 1) { anyConstructed<HostConnection>().commandArgs(match { "vote" in it && "--anchor-url" in it && "https://example.test/rationale" in it && "a".repeat(64) in it }) }
            verify(exactly = 2) { builder.build(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { ledger.recordSubmitted(any(), any(), any(), any(), any()) }
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }
}
