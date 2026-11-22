package com.swiftmako.jormanager.controllers

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.model.GovernanceVoteRequest
import com.swiftmako.jormanager.model.NodeVoteSelection
import com.swiftmako.jormanager.model.RetirePoolRequest
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.services.TransactionPlannerService
import com.swiftmako.jormanager.spring.config.Configuration
import com.swiftmako.jormanager.utils.Bech32
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.io.IOException
import java.math.BigInteger
import java.util.Optional
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NodeControllerPlannerTest {
    private val config = Configuration()
    private val moshi = config.getMoshi()

    private val feeAddr =
        "addr_test1qpz9v4j2k8h0x7m3n5l6q2w4e8r0t2y4u6i8o0p2a4s6d8f0g2h4j6k8l0z9x8c7v6b5n4m3q2w8e7r6t5y4u3i2o1p0s5x0v9"

    private val testHost =
        Host(
            id = 1L,
            type = "local",
            cardanoCliPath = "/usr/local/bin/cardano-cli",
            cardanoNodePath = "/usr/local/bin/cardano-node",
            hostname = "localhost",
            sshUser = "jormanager",
            nodeHomePath = "/opt/cardano",
            jcliPath = null,
        )

    private fun txHash(seed: Int): String = seed.toString(16).padStart(64, '0')

    @Test
    fun `submitGovernanceVote selects compact UTxO subset from 500-UTxO wallet and enforces 16KB limit`() {
        val nodeRepository = mockk<NodeRepository>()
        val hostRepository = mockk<HostRepository>()
        val fileRepository = mockk<FileRepository>()
        val walletRepository = mockk<WalletRepository>()
        val ledgerDao = mockk<LedgerDao>(relaxed = true)
        val cardanoRepository = mockk<CardanoRepository>()
        val walletUtils =
            mockk<WalletUtils> {
                every { isValidSpendingPassword(any()) } returns true
                every { getSKeyContent(any(), any()) } returns "decrypted-skey"
            }

        val defaultNode =
            Node(
                id = 1L,
                hostId = 1L,
                color = "#000000",
                type = NodeController.NODE_TYPE_CORE,
                processorThreads = 2,
                name = "core-1",
                listen = "127.0.0.1",
                port = 3001,
                promPort = 12791,
                genesisByronFileId = 10L,
                genesisShelleyFileId = 10L,
                genesisAlonzoFileId = 10L,
                genesisConwayFileId = 10L,
                configFileId = 10L,
                isDefault = true,
                coreVKeyId = 11L,
                coreSKeyId = 12L,
            )

        val files =
            mapOf(
                10L to
                    File(
                        id = 10L,
                        name = "shelley.json",
                        content = """{"activeSlotsCoeff":0.05,"networkId":"mainnet","slotLength":1,"epochLength":432000,"slotsPerKESPeriod":129600,"systemStart":"2017-09-23T21:44:51Z","maxKESEvolutions":62}""",
                    ),
                11L to File(11L, "node1.vkey", "vkey-1"),
                12L to File(12L, "node1.skey", "encrypted-skey-1"),
            )
        val feeWallet =
            WalletEntry(
                id = 5L,
                name = "fees",
                type = "payment",
                paymentAddr = feeAddr,
                paymentSkey = File(30L, "payment.skey", "enc"),
            )

        // 500 UTxOs in fee-payer wallet (would exceed 16KB if swept greedily!)
        val utxos =
            (1..500).map { idx ->
                Utxo(txHash(idx), 0, BigInteger.valueOf(5_000_000L), emptyList())
            }

        every { nodeRepository.findDefault() } returns defaultNode
        every { nodeRepository.findById(1L) } returns Optional.of(defaultNode)
        every { hostRepository.findById(1L) } returns Optional.of(testHost)
        every { fileRepository.findById(any()) } answers { Optional.ofNullable(files[firstArg()]) }
        every { walletRepository.findById(5L) } returns Optional.of(feeWallet)
        every { cardanoRepository.getEra(any(), any(), any(), any()) } returns "conway"
        every { walletUtils.getUtxos(any(), any(), any(), any(), any(), feeAddr) } returns utxos
        coEvery { ledgerDao.updateLiveLedgerState(any(), any()) } returns Unit

        val commands = mutableListOf<String>()
        var signedTxHex = "a0".repeat(350)

        val transactionRepository = mockk<com.swiftmako.jormanager.repositories.TransactionRepository>(relaxed = true)
        every { transactionRepository.save(any<com.swiftmako.jormanager.entities.Transaction>()) } answers { firstArg() }

        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().commandWriteFile(any(), any()) } returns ""
            every { anyConstructed<HostConnection>().commandReadFile(any()) } answers {
                when (val path = firstArg<String>()) {
                    "/tmp/governance-vote.txsigned", "/tmp/governance_vote.txsigned" ->
                        """{"type":"Tx ConwayEra","description":"","cborHex":"$signedTxHex"}"""
                    else ->
                        if (path.endsWith(".vote")) {
                            """{"type":"GovernanceVoteConway","description":"","cborHex":"8200"}"""
                        } else {
                            ""
                        }
                }
            }
            every { anyConstructed<HostConnection>().command(any<String>()) } answers {
                firstArg<String>().also(commands::add).let { cmd ->
                    when {
                        "query protocol-parameters" in cmd -> "{}"
                        "query tip" in cmd -> """{"era":"Conway","slot":1000}"""
                        "transaction txid" in cmd -> txHash(888)
                        else -> ""
                    }
                }
            }

            val httpClient = OkHttpClient.Builder().build()
            val controller =
                NodeController(
                    nodeRepository = nodeRepository,
                    hostRepository = hostRepository,
                    fileRepository = fileRepository,
                    walletRepository = walletRepository,
                    walletUtils = walletUtils,
                    transactionRepository = transactionRepository,
                    webSocketTemplate = mockk(relaxed = true),
                    nodesChannel = mockk(relaxed = true),
                    retrofit = config.getRetrofit(httpClient, moshi),
                    okHttpClient = httpClient,
                    relayRepository = mockk(relaxed = true),
                    extendedMetadataAdapter = config.getExtendedMetadataAdapter(moshi),
                    shelleyGenesisAdapter = config.getShelleyGenesisAdapter(moshi),
                    byronGenesisAdapter = config.getByronGenesisAdapter(moshi),
                    metadataAdapter = config.getMetadataAdapter(moshi),
                    protocolParamsAdapter = config.getProtocolParametersAdapter(moshi),
                    queryTipAdapter = config.getQueryTipAdapter(moshi),
                    keyFileJsonAdapter = mockk(relaxed = true),
                    bulkCredentialsJsonAdapter = mockk(relaxed = true),
                    txSignedAdapter = config.getTxSignedAdapter(moshi),
                    ledgerDao = ledgerDao,
                    cardanoUtils = mockk(relaxed = true),
                    cardanoRepository = cardanoRepository,
                    transactionPlannerService = TransactionPlannerService(),
                )

            val request =
                GovernanceVoteRequest(
                    govActionId = Bech32.encode("gov_action", ByteArray(33)),
                    votes = listOf(NodeVoteSelection(1L, "YES")),
                    feesAccountId = 5L,
                    spendingPassword = "secret",
                    rationale = null,
                )

            controller.submitGovernanceVote(request)

            val buildCmds = commands.filter { "transaction build-raw" in it }
            assertThat(buildCmds).hasSize(1)
            val txInCount = Regex("--tx-in ").findAll(buildCmds.single()).count()
            assertThat(txInCount).isLessThan(10)
            assertThat(commands.none { "calculate-min-fee" in it }).isTrue()
            assertThat(commands.none { "query utxo --tx-in" in it }).isTrue()
            assertThat(commands.count { "transaction submit" in it }).isEqualTo(1)

            // Verify 16KB signed envelope check blocks submission
            commands.clear()
            signedTxHex = "ff".repeat(16_500)
            val ex = assertThrows<RuntimeException> { controller.submitGovernanceVote(request) }
            assertThat(ex.cause).isInstanceOf(IOException::class.java)
            assertThat(ex.cause).hasMessageThat().contains("maxTxSize")
            assertThat(commands.none { "transaction submit" in it }).isTrue()
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test
    fun `retirePool selects compact UTxO subset from 500-UTxO wallet with zero CLI fee dry-runs`() {
        val nodeRepository = mockk<NodeRepository>(relaxed = true)
        val hostRepository = mockk<HostRepository>()
        val fileRepository = mockk<FileRepository>(relaxed = true)
        val walletRepository = mockk<WalletRepository>()
        val ledgerDao = mockk<LedgerDao>(relaxed = true)
        val cardanoRepository = mockk<CardanoRepository>()
        val walletUtils =
            mockk<WalletUtils> {
                every { isValidSpendingPassword(any()) } returns true
                every { getSKeyContent(any(), any()) } returns "decrypted-skey"
            }

        val defaultNode =
            Node(
                id = 1L,
                hostId = 1L,
                color = "#000000",
                type = NodeController.NODE_TYPE_CORE,
                processorThreads = 2,
                name = "default-core",
                listen = "127.0.0.1",
                port = 3001,
                promPort = 12791,
                genesisByronFileId = 10L,
                genesisShelleyFileId = 10L,
                genesisAlonzoFileId = 10L,
                genesisConwayFileId = 10L,
                configFileId = 10L,
                isDefault = true,
            )
        val retiringNode =
            Node(
                id = 2L,
                hostId = 1L,
                color = "#112233",
                type = NodeController.NODE_TYPE_CORE,
                processorThreads = 2,
                name = "retiring-core",
                listen = "127.0.0.1",
                port = 3002,
                promPort = 12792,
                genesisByronFileId = 10L,
                genesisShelleyFileId = 10L,
                genesisAlonzoFileId = 10L,
                genesisConwayFileId = 10L,
                configFileId = 10L,
                isDefault = false,
                coreVKeyId = 21L,
                coreSKeyId = 22L,
            )

        val files =
            mapOf(
                10L to
                    File(
                        id = 10L,
                        name = "shelley.json",
                        content = """{"activeSlotsCoeff":0.05,"networkId":"mainnet","slotLength":1,"epochLength":432000,"slotsPerKESPeriod":129600,"systemStart":"2017-09-23T21:44:51Z","maxKESEvolutions":62}""",
                    ),
                21L to File(21L, "retire.vkey", "vkey-2"),
                22L to File(22L, "retire.skey", "skey-2"),
            )
        val feeWallet =
            WalletEntry(
                id = 5L,
                name = "fees",
                type = "payment",
                paymentAddr = feeAddr,
                paymentSkey = File(30L, "payment.skey", "enc"),
            )
        val utxos =
            (1..500).map { idx ->
                Utxo(txHash(1_000 + idx), 0, BigInteger.valueOf(6_000_000L), emptyList())
            }

        val transactionRepository = mockk<com.swiftmako.jormanager.repositories.TransactionRepository>(relaxed = true)
        every { transactionRepository.save(any<com.swiftmako.jormanager.entities.Transaction>()) } answers { firstArg() }
        every { nodeRepository.findDefault() } returns defaultNode
        every { nodeRepository.findById(2L) } returns Optional.of(retiringNode)
        every { nodeRepository.save(any<Node>()) } answers { firstArg<Node>() }
        every { hostRepository.findById(1L) } returns Optional.of(testHost)
        every { fileRepository.findById(any()) } answers { Optional.ofNullable(files[firstArg()]) }
        every { fileRepository.save(any<File>()) } answers { firstArg<File>() }
        every { walletRepository.findById(5L) } returns Optional.of(feeWallet)
        every { cardanoRepository.getEra(any(), any(), any(), any()) } returns "conway"
        every { walletUtils.getUtxos(any(), any(), any(), any(), any(), feeAddr) } returns utxos
        coEvery { ledgerDao.updateLiveLedgerState(any(), any()) } returns Unit

        val commands = mutableListOf<String>()
        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().sudoCommand(any<String>(), any()) } returns "/root"
            every { anyConstructed<HostConnection>().commandWriteFile(any(), any()) } returns ""
            every { anyConstructed<HostConnection>().commandReadFile("/tmp/transaction.txsigned") } returns
                """{"type":"Tx ConwayEra","description":"","cborHex":"${"a0".repeat(300)}"}"""
            every { anyConstructed<HostConnection>().command(any<String>()) } answers {
                firstArg<String>().also(commands::add).let { cmd ->
                    when {
                        "query protocol-parameters" in cmd -> "{}"
                        "query tip" in cmd -> """{"era":"Conway","slot":2000}"""
                        "transaction txid" in cmd -> txHash(555)
                        else -> ""
                    }
                }
            }

            val httpClient = OkHttpClient.Builder().build()
            val controller =
                NodeController(
                    nodeRepository = nodeRepository,
                    hostRepository = hostRepository,
                    fileRepository = fileRepository,
                    walletRepository = walletRepository,
                    walletUtils = walletUtils,
                    transactionRepository = transactionRepository,
                    webSocketTemplate = mockk(relaxed = true),
                    nodesChannel = mockk(relaxed = true),
                    retrofit = config.getRetrofit(httpClient, moshi),
                    okHttpClient = httpClient,
                    relayRepository = mockk(relaxed = true),
                    extendedMetadataAdapter = config.getExtendedMetadataAdapter(moshi),
                    shelleyGenesisAdapter = config.getShelleyGenesisAdapter(moshi),
                    byronGenesisAdapter = config.getByronGenesisAdapter(moshi),
                    metadataAdapter = config.getMetadataAdapter(moshi),
                    protocolParamsAdapter = config.getProtocolParametersAdapter(moshi),
                    queryTipAdapter = config.getQueryTipAdapter(moshi),
                    keyFileJsonAdapter = mockk(relaxed = true),
                    bulkCredentialsJsonAdapter = mockk(relaxed = true),
                    txSignedAdapter = config.getTxSignedAdapter(moshi),
                    ledgerDao = ledgerDao,
                    cardanoUtils = mockk(relaxed = true),
                    cardanoRepository = cardanoRepository,
                    transactionPlannerService = TransactionPlannerService(),
                )

            controller.retirePool(
                RetirePoolRequest(
                    id = 2L,
                    sudoPassword = "sudo",
                    spendingPassword = "secret",
                    retireFeesAccount = 5L,
                    retireEpoch = 520L,
                )
            )

            val buildCmds = commands.filter { "transaction build-raw" in it }
            assertThat(buildCmds).hasSize(1)
            val txInCount = Regex("--tx-in ").findAll(buildCmds.single()).count()
            assertThat(txInCount).isLessThan(10)
            assertThat(commands.none { "calculate-min-fee" in it }).isTrue()
            assertThat(commands.count { "transaction submit" in it }).isEqualTo(1)
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }
}
