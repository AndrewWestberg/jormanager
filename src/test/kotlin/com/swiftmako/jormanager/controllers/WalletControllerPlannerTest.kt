package com.swiftmako.jormanager.controllers

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.model.CalculateFeeRequest
import com.swiftmako.jormanager.model.CalculateFeeResponse
import com.swiftmako.jormanager.model.NativeAsset
import com.swiftmako.jormanager.model.SubmitTransactionRequest
import com.swiftmako.jormanager.model.ToAccount
import com.swiftmako.jormanager.model.ToAccountFeeRequest
import com.swiftmako.jormanager.model.UpdateStakingAddressRequest
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.model.WalletItem
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.TransactionRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.services.TransactionPlannerService
import com.swiftmako.jormanager.spring.config.Configuration
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.verify
import java.io.IOException
import java.math.BigInteger
import java.util.Optional
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.messaging.simp.SimpMessagingTemplate

class WalletControllerPlannerTest {
    private val config = Configuration()
    private val moshi = config.getMoshi()

    private val senderAddr =
        "addr_test1qpz9v4j2k8h0x7m3n5l6q2w4e8r0t2y4u6i8o0p2a4s6d8f0g2h4j6k8l0z9x8c7v6b5n4m3q2w8e7r6t5y4u3i2o1p0s5x0v9"
    private val recipientAddr =
        "addr_test1vqwyf8g87u0x2ex9mjczrceyh65zj77fra23nfylnf4gxhqwz3dvu"
    private val policyId = "682fe60c9918842b3323c43b5144bc3d52a23bd2fb81345560d73f63"

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

    private val defaultNode =
        Node(
            id = 1L,
            hostId = 1L,
            color = "#22aa88",
            type = "core",
            processorThreads = 4,
            name = "core-1",
            listen = "127.0.0.1",
            port = 3001,
            promPort = 12798,
            genesisByronFileId = 10L,
            genesisShelleyFileId = 10L,
            genesisAlonzoFileId = 10L,
            genesisConwayFileId = 10L,
            configFileId = 10L,
            isDefault = true,
        )

    private val shelleyFile =
        File(
            id = 10L,
            name = "shelley-genesis.json",
            content = """{"activeSlotsCoeff":0.05,"networkId":"Testnet","networkMagic":1,"slotLength":1,"epochLength":432000,"slotsPerKESPeriod":129600,"systemStart":"2022-10-25T00:00:00Z","maxKESEvolutions":62}""",
        )

    private fun txHash(seed: Int): String = seed.toString(16).padStart(64, '0')

    private fun createController(
        walletUtils: WalletUtils,
        walletRepository: WalletRepository,
        webSocketTemplate: SimpMessagingTemplate,
    ): WalletController {
        val fileRepository = mockk<FileRepository>()
        val nodeRepository = mockk<NodeRepository>()
        val hostRepository = mockk<HostRepository>()
        val transactionRepository = mockk<TransactionRepository>(relaxed = true)
        val ledgerDao = mockk<LedgerDao>(relaxed = true)
        val cardanoRepository = mockk<CardanoRepository>()

        every { nodeRepository.findDefault() } returns defaultNode
        every { fileRepository.findById(10L) } returns Optional.of(shelleyFile)
        every { hostRepository.findById(1L) } returns Optional.of(testHost)
        every { cardanoRepository.getEra(any(), any(), any(), any()) } returns "conway"
        every { transactionRepository.save(any<com.swiftmako.jormanager.entities.Transaction>()) } answers { firstArg() }
        coEvery { ledgerDao.updateLiveLedgerState(any(), any()) } returns Unit

        return WalletController(
            walletUtils = walletUtils,
            walletRepository = walletRepository,
            fileRepository = fileRepository,
            nodeRepository = nodeRepository,
            hostRepository = hostRepository,
            transactionRepository = transactionRepository,
            queryTipAdapter = config.getQueryTipAdapter(moshi),
            shelleyGenesisAdapter = config.getShelleyGenesisAdapter(moshi),
            protocolParamsAdapter = config.getProtocolParametersAdapter(moshi),
            webSocketTemplate = webSocketTemplate,
            txSignedAdapter = config.getTxSignedAdapter(moshi),
            ledgerDao = ledgerDao,
            cardanoRepository = cardanoRepository,
            transactionPlannerService = TransactionPlannerService(),
        )
    }

    @Test
    fun `calculateTxFee uses local SDK planner with zero CLI fee or build-raw dry-runs`() {
        val walletRepository = mockk<WalletRepository>()
        val walletUtils = mockk<WalletUtils>()
        val webSocketTemplate = mockk<SimpMessagingTemplate>(relaxed = true)

        val senderWallet = WalletEntry(id = 1L, name = "ops", type = "payment", paymentAddr = senderAddr)
        val recipientWallet = WalletEntry(id = 2L, name = "dest", type = "address", paymentAddr = recipientAddr)
        every { walletRepository.findById(1L) } returns Optional.of(senderWallet)
        every { walletRepository.findById(2L) } returns Optional.of(recipientWallet)

        val utxos =
            (1..200).map { idx ->
                Utxo(txHash(idx), 0, BigInteger.valueOf(5_000_000L + idx * 100_000L), emptyList())
            }
        every { walletUtils.getUtxos(any(), any(), any(), any(), any(), senderAddr) } returns utxos

        val executedCommands = mutableListOf<String>()
        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().command(any<String>()) } answers {
                firstArg<String>().also(executedCommands::add).let { cmd ->
                    when {
                        "query protocol-parameters" in cmd -> "{}"
                        else -> ""
                    }
                }
            }

            val controller = createController(walletUtils, walletRepository, webSocketTemplate)
            controller.calculateTxFee(
                CalculateFeeRequest(
                    uuid = "uuid-standard-test",
                    fromId = 1L,
                    txOut = 2,
                    isClaim = false,
                    metadata = null,
                    toAccounts =
                        listOf(
                            ToAccountFeeRequest(
                                currency = "ada",
                                account = 2L,
                                address = "",
                                amount = BigInteger.valueOf(12_000_000L),
                                type = "amount",
                            )
                        ),
                    selectionMode = "STANDARD",
                )
            )

            assertThat(executedCommands.none { "calculate-min-fee" in it }).isTrue()
            assertThat(executedCommands.none { "calculate-min-required-utxo" in it }).isTrue()
            assertThat(executedCommands.none { "transaction build-raw" in it }).isTrue()

            val payloadSlot = slot<SocketResponse<CalculateFeeResponse>>()
            verify { webSocketTemplate.convertAndSend(eq("/topic/messages"), capture(payloadSlot)) }
            val success = payloadSlot.captured as SocketResponse.Success<CalculateFeeResponse>
            assertThat(success.data.uuid).isEqualTo("uuid-standard-test")
            assertThat(success.data.selectionMode).isEqualTo("STANDARD")
            assertThat(success.data.inputCount).isAtLeast(1)
            assertThat(success.data.inputCount).isLessThan(10)
            assertThat(success.data.outputCount).isEqualTo(2)
            assertThat(success.data.estimatedTxSize).isAtMost(16_384)
            assertThat(success.data.txFee).isGreaterThan(BigInteger.valueOf(150_000L))
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test
    fun `submitTransaction supports STANDARD and DEFRAGMENT modes and blocks oversized signed envelopes`() {
        val walletRepository = mockk<WalletRepository>()
        val walletUtils = mockk<WalletUtils>()
        val webSocketTemplate = mockk<SimpMessagingTemplate>(relaxed = true)

        val paymentSkey = File(id = 50L, name = "ops.skey", content = "encrypted")
        val senderWallet =
            WalletEntry(id = 1L, name = "ops", type = "payment", paymentAddr = senderAddr, paymentSkey = paymentSkey)
        val recipientWallet = WalletEntry(id = 2L, name = "dest", type = "address", paymentAddr = recipientAddr)
        every { walletRepository.findById(1L) } returns Optional.of(senderWallet)
        every { walletRepository.findById(2L) } returns Optional.of(recipientWallet)
        every { walletUtils.isValidSpendingPassword(any()) } returns true
        every { walletUtils.getSKeyContent(any(), any()) } returns
            """{"type":"PaymentSigningKeyShelley_ed25519","description":"","cborHex":"5820"}"""

        val utxos =
            (1..80).map { idx ->
                Utxo(txHash(idx), 0, BigInteger.valueOf(2_000_000L), emptyList())
            } +
                (81..90).map { idx ->
                    Utxo(
                        txHash(idx),
                        1,
                        BigInteger.valueOf(1_600_000L),
                        listOf(NativeAsset("4e45574d", policyId, BigInteger.TEN)),
                    )
                }
        every { walletUtils.getUtxos(any(), any(), any(), any(), any(), senderAddr) } returns utxos

        val executedCommands = mutableListOf<String>()
        var signedTxCborHex = "a0".repeat(320)

        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().commandWriteFile(any(), any()) } returns ""
            every { anyConstructed<HostConnection>().commandReadFile("/tmp/transaction.txsigned") } answers {
                """{"type":"Tx ConwayEra","description":"","cborHex":"$signedTxCborHex"}"""
            }
            every { anyConstructed<HostConnection>().command(any<String>()) } answers {
                firstArg<String>().also(executedCommands::add).let { cmd ->
                    when {
                        "query protocol-parameters" in cmd -> "{}"
                        "query tip" in cmd -> """{"era":"Conway","slot":50000}"""
                        "transaction txid" in cmd -> txHash(999)
                        else -> ""
                    }
                }
            }

            val controller = createController(walletUtils, walletRepository, webSocketTemplate)

            // 1. STANDARD mode: compact UTxO selection, single build-raw, zero calculate-min-fee
            controller.submitTransaction(
                SubmitTransactionRequest(
                    spendingPassword = "secret",
                    fromId = 1L,
                    txFee = BigInteger.valueOf(180_000L),
                    tokenKeepFee = BigInteger.ZERO,
                    isClaim = false,
                    metadata = null,
                    toAccounts =
                        listOf(
                            ToAccount(
                                currency = "ada",
                                account = 2L,
                                address = "",
                                amount = BigInteger.valueOf(5_000_000L),
                                percent = null,
                                tokenFee = BigInteger.ZERO,
                            )
                        ),
                    selectionMode = "STANDARD",
                )
            )

            val standardBuildCmds = executedCommands.filter { "transaction build-raw" in it }
            assertThat(standardBuildCmds).hasSize(1)
            val standardTxInCount = Regex("--tx-in ").findAll(standardBuildCmds.single()).count()
            assertThat(standardTxInCount).isLessThan(10)
            assertThat(executedCommands.none { "calculate-min-fee" in it }).isTrue()
            assertThat(executedCommands.count { "transaction submit" in it }).isEqualTo(1)

            // 2. DEFRAGMENT mode: sweeps all 90 UTxOs into clean consolidated outputs
            executedCommands.clear()
            controller.submitTransaction(
                SubmitTransactionRequest(
                    spendingPassword = "secret",
                    fromId = 1L,
                    txFee = BigInteger.valueOf(350_000L),
                    tokenKeepFee = BigInteger.valueOf(1_500_000L),
                    isClaim = false,
                    metadata = null,
                    toAccounts =
                        listOf(
                            ToAccount(
                                currency = "ada",
                                account = 1L,
                                address = "",
                                amount = BigInteger.ZERO,
                                percent = 100,
                                tokenFee = BigInteger.ZERO,
                            )
                        ),
                    selectionMode = "DEFRAGMENT",
                )
            )

            val defragBuildCmds = executedCommands.filter { "transaction build-raw" in it }
            assertThat(defragBuildCmds).hasSize(1)
            val defragTxInCount = Regex("--tx-in ").findAll(defragBuildCmds.single()).count()
            assertThat(defragTxInCount).isEqualTo(90)
            assertThat(executedCommands.count { "transaction submit" in it }).isEqualTo(1)

            // 3. Oversized signed envelope (> 16,384 bytes) is rejected prior to submission
            executedCommands.clear()
            signedTxCborHex = "ff".repeat(16_400)
            val ex =
                assertThrows<RuntimeException> {
                    controller.submitTransaction(
                        SubmitTransactionRequest(
                            spendingPassword = "secret",
                            fromId = 1L,
                            txFee = BigInteger.valueOf(180_000L),
                            tokenKeepFee = BigInteger.ZERO,
                            isClaim = false,
                            metadata = null,
                            toAccounts =
                                listOf(
                                    ToAccount(
                                        currency = "ada",
                                        account = 2L,
                                        address = "",
                                        amount = BigInteger.valueOf(5_000_000L),
                                        percent = null,
                                        tokenFee = BigInteger.ZERO,
                                    )
                                ),
                            selectionMode = "STANDARD",
                        )
                    )
                }
            assertThat(ex.cause).isInstanceOf(IOException::class.java)
            assertThat(ex.cause).hasMessageThat().contains("maxTxSize")
            assertThat(executedCommands.none { "transaction submit" in it }).isTrue()
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test
    fun `updateStakingAddress selects compact UTxOs without CLI fee calculation`() {
        val walletRepository = mockk<WalletRepository>()
        val walletUtils = mockk<WalletUtils>()
        val webSocketTemplate = mockk<SimpMessagingTemplate>(relaxed = true)

        val feeKey = File(id = 51L, name = "fee.skey", content = "enc-fee")
        val stakeSkey = File(id = 52L, name = "stake.skey", content = "enc-stake")
        val stakeVkey = File(id = 53L, name = "stake.vkey", content = "vkey-stake")
        val stakeCert =
            File(
                id = 54L,
                name = "stake.cert",
                content = """{"type":"CertificateConway","description":"Stake Address Registration Certificate","cborHex":"8307"}""",
            )
        val feePayer =
            WalletEntry(id = 1L, name = "feepayer", type = "payment", paymentAddr = senderAddr, paymentSkey = feeKey)
        val stakingAccount =
            WalletEntry(
                id = 2L,
                name = "owner",
                type = "stake",
                paymentAddr = recipientAddr,
                stakingAddr = TransactionPlannerService.DEFAULT_DUMMY_STAKE_ADDRESS,
                stakingSkey = stakeSkey,
                stakingVkey = stakeVkey,
                stakingRegCert = stakeCert,
            )

        every { walletRepository.findById(1L) } returns Optional.of(feePayer)
        every { walletRepository.findById(2L) } returns Optional.of(stakingAccount)
        every { walletUtils.isValidSpendingPassword(any()) } returns true
        every { walletUtils.getSKeyContent(any(), any()) } returns "decrypted"
        every { walletUtils.getWalletItem(any(), any(), any(), any(), any(), stakingAccount) } returns
            WalletItem(
                id = 2L,
                name = "owner",
                type = "stake",
                hasPaymentKeys = false,
                paymentAddr = recipientAddr,
                paymentAddrUtxoCount = 0L,
                paymentAddrLovelace = BigInteger.ZERO,
                stakingAddr = TransactionPlannerService.DEFAULT_DUMMY_STAKE_ADDRESS,
                stakingAddrRegistered = false,
                stakingAddrLovelace = BigInteger.ZERO,
                nativeAssetMap = emptyMap(),
            )

        val utxos =
            (1..150).map { idx ->
                Utxo(txHash(idx), 0, BigInteger.valueOf(4_000_000L), emptyList())
            }
        every { walletUtils.getUtxos(any(), any(), any(), any(), any(), senderAddr) } returns utxos

        val executedCommands = mutableListOf<String>()
        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().commandWriteFile(any(), any()) } returns ""
            every { anyConstructed<HostConnection>().commandReadFile("/tmp/transaction.txsigned") } returns
                """{"type":"Tx ConwayEra","description":"","cborHex":"${"a0".repeat(280)}"}"""
            every { anyConstructed<HostConnection>().command(any<String>()) } answers {
                firstArg<String>().also(executedCommands::add).let { cmd ->
                    when {
                        "query protocol-parameters" in cmd -> "{}"
                        "query tip" in cmd -> """{"era":"Conway","slot":70000}"""
                        "transaction txid" in cmd -> txHash(777)
                        else -> ""
                    }
                }
            }

            val controller = createController(walletUtils, walletRepository, webSocketTemplate)
            controller.updateStakingAddress(
                UpdateStakingAddressRequest(
                    id = 2L,
                    spendingPassword = "secret",
                    stakingFeesAccount = 1L,
                    isRegistration = true,
                )
            )

            val buildCmds = executedCommands.filter { "transaction build-raw" in it }
            assertThat(buildCmds).hasSize(1)
            val txInCount = Regex("--tx-in ").findAll(buildCmds.single()).count()
            assertThat(txInCount).isLessThan(10)
            assertThat(executedCommands.none { "calculate-min-fee" in it }).isTrue()
            assertThat(executedCommands.count { "transaction submit" in it }).isEqualTo(1)
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }
}
