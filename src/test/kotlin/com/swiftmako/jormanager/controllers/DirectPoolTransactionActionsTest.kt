package com.swiftmako.jormanager.controllers

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.Relay
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.model.Metadata
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.ProtocolParametersRepository
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.repositories.RelayRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.services.PoolRegistrationCertificateBuilder
import com.swiftmako.jormanager.services.WalletTransactionBuilder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.verify
import java.math.BigInteger
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.messaging.simp.SimpMessagingTemplate
import tools.jackson.module.kotlin.jacksonObjectMapper

class DirectPoolTransactionActionsTest {
    private val json = jacksonObjectMapper()
    private val walletUtils = mockk<WalletUtils>()
    private val ledger = mockk<LedgerDao>()
    private val nodeController = mockk<NodeController>()
    private val certificates = mockk<PoolRegistrationCertificateBuilder>()
    private val registration =
        PoolRegistrationController(
            json,
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            mockk(),
            walletUtils,
            certificates,
            mockk(),
            ledger,
            nodeController,
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
    private val retirement = PoolRetirementController(json, mockk(), mockk(), mockk(), mockk(), mockk(), walletUtils, mockk(), mockk(), ledger)
    private val id = UUID.randomUUID().toString()


    @Test
    fun registrationRequiresPassword() {
        val metadata = Metadata("TEST", "Pool", "Description", "https://example.test", null)
        val input = PoolRegistrationController.SubmitRequest(id, 1, 2, action = "update", metadata = metadata, spendingPassword = "secret")
        every { walletUtils.isValidSpendingPassword("secret") } returns false
        assertThrows<IllegalArgumentException> { registration.execute(input) }
        verify(exactly = 0) { nodeController.publishPoolMetadata(any(), any(), any()) }
    }

    @Test
    fun retirementRequiresPassword() {
        val input = PoolRetirementController.SubmitRequest(id, 1, 2, "500", "secret")
        every { walletUtils.isValidSpendingPassword("secret") } returns false
        assertThrows<IllegalArgumentException> { retirement.execute(input) }
    }

    @Test
    fun registrationRejectsClientMetadataAnchorBeforeAnySideEffect() {
        every { walletUtils.isValidSpendingPassword("secret") } returns true
        val changes = json.readTree("""{"metadata":{"url":"https://example.test","hash":"abc"}}""")
        val input = PoolRegistrationController.SubmitRequest(id, 1, 2, action = "update", changes = changes, spendingPassword = "secret")
        assertThrows<IllegalArgumentException> { registration.execute(input) }
        verify(exactly = 0) { nodeController.publishPoolMetadata(any(), any(), any()) }
    }

    @Test
    fun registrationRejectsNonNumericPoolMargin() {
        every { walletUtils.isValidSpendingPassword("secret") } returns true
        val nonNumericMargins =
            listOf(
                """{"margin":{"numerator":3,"denominator":100}}""",
                """{"margin":"3/100"}""",
                """{"margin":"abc"}""",
                """{"margin":1.5}""",
                """{"margin":-0.01}""",
            )
        for (raw in nonNumericMargins) {
            val input =
                PoolRegistrationController.SubmitRequest(
                    id,
                    1,
                    2,
                    action = "update",
                    changes = json.readTree(raw),
                    spendingPassword = "secret",
                )
            assertThrows<IllegalArgumentException> { registration.execute(input) }
        }
    }

    @Test
    fun publicationFailureStopsRegistrationBeforeCertificateOrSubmission() {
        val nodes = mockk<NodeRepository>()
        val hosts = mockk<HostRepository>()
        val files = mockk<FileRepository>()
        val accounts = mockk<WalletRepository>()
        val eras = mockk<CardanoRepository>()
        val parameters = mockk<ProtocolParametersRepository>()
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
        every { parameters.query(any(), any(), any(), any(), any(), any()) } returns "{}"
        every { walletUtils.getUtxos(any(), any(), any(), any(), any(), any()) } returns
            listOf(Utxo("a".repeat(64), 0, BigInteger.TEN, emptyList()))
        every { ledger.withSubmissionLock(any<() -> PoolRegistrationController.Result>()) } answers {
            firstArg<() -> PoolRegistrationController.Result>()()
        }
        every { walletUtils.isValidSpendingPassword("secret") } returns true
        val metadata = Metadata("TEST", "Pool", "Description", "https://example.test", null)
        val failure = IllegalStateException("publication failed")
        every { nodeController.publishPoolMetadata(node, metadata, "secret") } throws failure
        val controller =
            PoolRegistrationController(
                json,
                nodes,
                hosts,
                files,
                accounts,
                eras,
                parameters,
                walletUtils,
                certificates,
                mockk(),
                ledger,
                nodeController,
                mockk(relaxed = true),
                mockk(relaxed = true),
            )
        val input = PoolRegistrationController.SubmitRequest(id, 1, 2, action = "update", metadata = metadata, spendingPassword = "secret")
        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().commandArgs(any()) } answers {
                if ("pool-state" in firstArg<List<String>>()) """{"$poolId":{"deposit":1}}""" else """{"epoch":10}"""
            }
            assertThat(assertThrows<IllegalStateException> { controller.execute(input) }).isSameInstanceAs(failure)
            verify(exactly = 1) { nodeController.publishPoolMetadata(node, metadata, "secret") }
            verify(exactly = 0) { certificates.build(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test
    fun successfulUpdatePersistsPoolConfigRelaysAndBroadcastsNodesWithRelays() {
        val nodes = mockk<NodeRepository>()
        val hosts = mockk<HostRepository>()
        val files = mockk<FileRepository>()
        val accounts = mockk<WalletRepository>()
        val eras = mockk<CardanoRepository>()
        val parameters = mockk<ProtocolParametersRepository>()
        val builder = mockk<WalletTransactionBuilder>()
        val relays = mockk<RelayRepository>(relaxed = true)
        val messages = mockk<SimpMessagingTemplate>(relaxed = true)
        val poolId = "a".repeat(56)
        val host =
            Host(
                id = 1L,
                type = "local",
                cardanoCliPath = "/bin/cardano-cli",
                cardanoNodePath = "/bin/cardano-node",
                hostname = "host",
                sshUser = "user",
                nodeHomePath = "/nodes",
                jcliPath = null,
            )
        val pool =
            Node(
                id = 1L,
                hostId = 1L,
                color = "#112233",
                type = "core",
                processorThreads = 4,
                name = "pool",
                listen = "0.0.0.0",
                port = 3001,
                promPort = 12798,
                genesisByronFileId = 1L,
                genesisShelleyFileId = 5L,
                genesisAlonzoFileId = 2L,
                genesisConwayFileId = 3L,
                configFileId = 6L,
                poolId = poolId,
                isDefault = true,
                coreVKeyId = 4L,
                coreSKeyId = 4L,
                vrfVKeyId = 4L,
                ownerStakingAccountId = 2L,
                rewardsStakingAccountId = 2L,
                poolPledge = BigInteger.valueOf(100_000_000L),
                poolCost = BigInteger.valueOf(340_000_000L),
                poolMargin = "0.05",
            )
        val key = File(4L, "key", """{"cborHex":"5820${"11".repeat(32)}"}""")
        val genesis = File(5L, "genesis", """{"networkId":"mainnet"}""")
        val ownerAccount =
            WalletEntry(
                id = 2L,
                name = "owner",
                type = "stake",
                paymentAddr = "addr1_owner",
                paymentSkey = key,
                stakingAddr = "stake1_owner",
                stakingSkey = key,
                stakingVkey = key,
            )
        val rewardAccount =
            WalletEntry(
                id = 3L,
                name = "reward",
                type = "stake",
                paymentAddr = "addr1_reward",
                paymentSkey = key,
                stakingAddr = "stake1_reward",
                stakingSkey = key,
                stakingVkey = key,
            )
        val oldRelay = Relay(id = 50L, nodeId = 1L, addr = "old.relay.test", port = 3000)
        val savedNodeSlot = slot<Node>()
        val savedRelaysSlot = slot<Iterable<Relay>>()
        val metadata = Metadata("TEST", "Pool", "Description", "https://example.test", null)
        val published =
            NodeController.PublishedPoolMetadata(
                url = "https://cardanostakehouse.com/meta.json",
                hash = "c".repeat(64),
                extendedUrl = "https://cardanostakehouse.com/ext.json",
                itnPrivateKeyId = 77L,
                itnPublicKeyId = 78L,
            )

        every { nodes.findDefault() } returns pool
        every { nodes.findById(1L) } returns Optional.of(pool)
        every { nodes.save(capture(savedNodeSlot)) } answers { savedNodeSlot.captured }
        every { hosts.findById(1L) } returns Optional.of(host)
        every { files.findById(4L) } returns Optional.of(key)
        every { files.findById(5L) } returns Optional.of(genesis)
        every { accounts.findById(2L) } returns Optional.of(ownerAccount)
        every { accounts.findById(3L) } returns Optional.of(rewardAccount)
        every { eras.networkKey(any()) } returns "network"
        every { eras.getEra(any(), any(), any(), any()) } returns "conway"
        every { parameters.query(any(), any(), any(), any(), any(), any()) } returns
            """{"stakePoolDeposit":500000000,"stakeAddressDeposit":2000000}"""
        every { walletUtils.isValidSpendingPassword("secret") } returns true
        every { walletUtils.getSKeyContent(any(), "secret") } returns key.content
        every { walletUtils.getUtxos(any(), any(), any(), any(), any(), any()) } returns
            listOf(Utxo("a".repeat(64), 0, BigInteger.valueOf(10_000_000L), emptyList()))
        every { walletUtils.queryReferenceScriptSize(any(), any(), any(), any(), any(), any()) } returns 0
        every { ledger.withSubmissionLock(any<() -> PoolRegistrationController.Result>()) } answers {
            firstArg<() -> PoolRegistrationController.Result>()()
        }
        every { ledger.recordSubmitted(any(), any(), any(), any(), any()) } returns Unit
        every { nodeController.publishPoolMetadata(pool, metadata, "secret") } returns published
        every { certificates.build(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            PoolRegistrationCertificateBuilder.Result(
                certPath = "/tmp/pool.cert",
                signingFileIds = listOf(4L),
                targetPublicKey = null,
                deposit = BigInteger.ZERO,
                cleanupPaths = listOf("/tmp/pool.cert"),
                ownerAccountIds = listOf(2L),
                rewardAccountId = 3L,
            )
        every {
            builder.build(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns
            WalletTransactionBuilder.CliBody(
                textEnvelope = """{"type":"TxBodyConway","description":"","cborHex":"81a1021a00029810"}""",
                txId = "d".repeat(64),
            )
        every { relays.findByNodeId(1L) } returns listOf(oldRelay)
        every { relays.saveAll(capture(savedRelaysSlot)) } answers { savedRelaysSlot.captured.toList() }
        val expectedRelays =
            listOf(
                Relay(nodeId = 1L, addr = "1.2.3.4", port = 3001),
                Relay(nodeId = 1L, addr = "2001:db8::1", port = 3002),
                Relay(nodeId = 1L, addr = "relay.pool.test", port = 3003),
            )
        every { nodeController.activeNodesWithRelays() } answers {
            listOf(savedNodeSlot.captured.copy(relays = expectedRelays))
        }

        val controller =
            PoolRegistrationController(
                json,
                nodes,
                hosts,
                files,
                accounts,
                eras,
                parameters,
                walletUtils,
                certificates,
                builder,
                ledger,
                nodeController,
                relays,
                messages,
            )
        val changes =
            json.readTree(
                """
                {
                  "pledge": "500000000",
                  "cost": "345000000",
                  "margin": 0.03,
                  "rewardAccountId": 3,
                  "relays": [
                    {"single host address": {"port": 3001, "IPv4": "1.2.3.4", "IPv6": null}},
                    {"single host address": {"port": 3002, "IPv4": null, "IPv6": "2001:db8::1"}},
                    {"single host name": {"port": 3003, "dnsName": "relay.pool.test"}}
                  ]
                }
                """.trimIndent(),
            )
        val input =
            PoolRegistrationController.SubmitRequest(
                requestId = id,
                nodeId = 1L,
                feePayerAccountId = 2L,
                action = "update",
                changes = changes,
                metadata = metadata,
                spendingPassword = "secret",
            )

        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().commandWriteFile(any(), any()) } returns ""
            every { anyConstructed<HostConnection>().commandWriteSecret(any(), any()) } returns Unit
            every { anyConstructed<HostConnection>().commandReadFile(any()) } returns """{"cborHex":"84a0a0f5f6"}"""
            every { anyConstructed<HostConnection>().commandArgs(any()) } answers {
                val args = firstArg<List<String>>()
                when {
                    "pool-state" in args -> """{"$poolId":{"deposit":500000000}}"""
                    "stake-address-info" in args -> """[{"address":"stake1","rewardAccountBalance":0}]"""
                    "tip" in args -> """{"slot":1000,"era":"conway","hash":"${"b".repeat(64)}","epoch":10}"""
                    else -> ""
                }
            }

            val result = controller.execute(input)
            assertThat(result.state).isEqualTo("submitted")
            assertThat(savedNodeSlot.captured.poolPledge).isEqualTo(BigInteger.valueOf(500_000_000L))
            assertThat(savedNodeSlot.captured.poolCost).isEqualTo(BigInteger.valueOf(345_000_000L))
            assertThat(savedNodeSlot.captured.poolMargin).isEqualTo("0.03")
            assertThat(savedNodeSlot.captured.ownerStakingAccountId).isEqualTo(2L)
            assertThat(savedNodeSlot.captured.rewardsStakingAccountId).isEqualTo(3L)
            assertThat(savedNodeSlot.captured.metadataUrl).isEqualTo("https://cardanostakehouse.com/meta.json")
            assertThat(savedNodeSlot.captured.extendedMetadataUrl).isEqualTo("https://cardanostakehouse.com/ext.json")
            assertThat(savedNodeSlot.captured.itnPrivateKeyId).isEqualTo(77L)
            assertThat(savedNodeSlot.captured.itnPublicKeyId).isEqualTo(78L)
            verify(exactly = 1) { relays.deleteAll(listOf(oldRelay)) }
            assertThat(savedRelaysSlot.captured.toList()).isEqualTo(expectedRelays)
            verify(exactly = 1) {
                messages.convertAndSend(
                    "/topic/messages",
                    match<SocketResponse.Success<List<Node>>> {
                        it.type == "nodes" && it.data.single().relays == expectedRelays
                    },
                )
            }

            val stringMarginInput =
                input.copy(
                    requestId = UUID.randomUUID().toString(),
                    changes = json.readTree("""{"pledge":"500000000","cost":"345000000","margin":"0.04"}"""),
                    metadata = null,
                )
            controller.execute(stringMarginInput)
            assertThat(savedNodeSlot.captured.poolMargin).isEqualTo("0.04")
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }
}
