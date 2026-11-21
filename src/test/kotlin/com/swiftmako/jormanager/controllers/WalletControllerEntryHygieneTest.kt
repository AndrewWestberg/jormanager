package com.swiftmako.jormanager.controllers

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.model.CreateWalletEntryRequest
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.ProtocolParametersRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.services.WalletTransactionBuilder
import com.swiftmako.jormanager.spring.config.Configuration
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.messaging.simp.SimpMessagingTemplate
import tools.jackson.databind.ObjectMapper

class WalletControllerEntryHygieneTest {
    private val config = Configuration()
    private val moshi = config.getMoshi()

    @ParameterizedTest
    @CsvSource(
        "conway,true",
        "dijkstra,true",
        "babbage,false",
    )
    fun createStakeWalletEntryUsesUuidTempPathsChmod600NoProtocolTempFileAndEraDepositGuard(
        era: String,
        expectsDepositArg: Boolean,
    ) {
        val walletUtils = mockk<WalletUtils>()
        val walletRepository = mockk<WalletRepository>()
        val fileRepository = mockk<FileRepository>()
        val nodeRepository = mockk<NodeRepository>()
        val hostRepository = mockk<HostRepository>()
        val cardanoRepository = mockk<CardanoRepository>()
        val protocolSnapshots = mockk<ProtocolParametersRepository>()

        val defaultNode =
            Node(
                id = 1L,
                hostId = 1L,
                color = "#000000",
                type = "core",
                processorThreads = 2,
                name = "core",
                listen = "127.0.0.1",
                port = 3001,
                promPort = 12798,
                genesisByronFileId = 1L,
                genesisShelleyFileId = 2L,
                genesisAlonzoFileId = 3L,
                genesisConwayFileId = 4L,
                configFileId = 5L,
                isDefault = true,
            )
        val defaultHost =
            Host(
                id = 1L,
                type = "local",
                cardanoCliPath = "/bin/cardano-cli",
                cardanoNodePath = "/bin/cardano-node",
                hostname = "localhost",
                sshUser = "user",
                nodeHomePath = "/nodes",
                jcliPath = null,
            )
        val shelleyGenesis =
            File(
                id = 2L,
                name = "shelley-genesis.json",
                content =
                    """{"activeSlotsCoeff":0.05,"networkId":"Testnet","networkMagic":2,"slotLength":1,"epochLength":3600,"slotsPerKESPeriod":3600,"systemStart":"1970-01-01T00:00:00Z","maxKESEvolutions":62}""",
            )
        val protocolJson =
            """{"stakePoolDeposit":500000000,"protocolVersion":{"major":9,"minor":0},"decentralization":null,"maxTxSize":16384,"minPoolCost":340000000,"txFeePerByte":44,"maxBlockBodySize":65536,"txFeeFixed":155381,"poolRetireMaxEpoch":18,"maxBlockHeaderSize":1100,"stakeAddressDeposit":2000000,"stakePoolTargetNum":500,"monetaryExpansion":0,"treasuryCut":0,"poolPledgeInfluence":0}"""

        every { walletUtils.isValidSpendingPassword("secret") } returns true
        every { walletUtils.encryptSKeyContent(any(), "secret") } answers { "encrypted:${firstArg<String>()}" }
        every { nodeRepository.findDefault() } returns defaultNode
        every { fileRepository.findById(2L) } returns Optional.of(shelleyGenesis)
        every { fileRepository.save(any<File>()) } answers { firstArg<File>().copy(id = 10L) }
        every { hostRepository.findById(1L) } returns Optional.of(defaultHost)
        every { cardanoRepository.getEra(any(), any(), any(), any()) } returns era
        every { protocolSnapshots.query(any(), any(), any(), any(), any(), any()) } returns protocolJson
        every { walletRepository.save(any<WalletEntry>()) } answers { firstArg() }

        val writtenFiles = mutableMapOf<String, String>()
        val commands = mutableListOf<String>()
        val commandArgsCalls = mutableListOf<List<String>>()
        val remoteFiles = mutableMapOf<String, String>()

        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().commandWriteFile(any(), any()) } answers {
                val path = firstArg<String>()
                val content = secondArg<String>()
                writtenFiles[path] = content
                remoteFiles[path] = content
                ""
            }
            every { anyConstructed<HostConnection>().commandReadFile(any()) } answers {
                remoteFiles.getValue(firstArg<String>())
            }
            every { anyConstructed<HostConnection>().command(any<String>()) } answers {
                val cmd = firstArg<String>()
                commands += cmd
                when {
                    "address key-gen" in cmd -> {
                        val vkey = Regex("--verification-key-file\\s+([^\\s]+)").find(cmd)!!.groupValues[1]
                        val skey = Regex("--signing-key-file\\s+([^\\s]+)").find(cmd)!!.groupValues[1]
                        remoteFiles[vkey] = "pvkey-content"
                        remoteFiles[skey] = "pskey-content"
                        ""
                    }
                    "stake-address key-gen" in cmd -> {
                        val vkey = Regex("--verification-key-file\\s+([^\\s]+)").find(cmd)!!.groupValues[1]
                        val skey = Regex("--signing-key-file\\s+([^\\s]+)").find(cmd)!!.groupValues[1]
                        remoteFiles[vkey] = "svkey-content"
                        remoteFiles[skey] = "sskey-content"
                        ""
                    }
                    "registration-certificate" in cmd -> {
                        val out = Regex("--out-file\\s+([^\\s]+)").find(cmd)!!.groupValues[1]
                        remoteFiles[out] = "regcert-content"
                        ""
                    }
                    "stake-address build" in cmd -> "stake_test1_built"
                    "address build" in cmd -> "addr_test1_built"
                    else -> ""
                }
            }
            every { anyConstructed<HostConnection>().commandArgs(any()) } answers {
                val args = firstArg<List<String>>()
                commandArgsCalls += args
                if (args.firstOrNull() == "rm") {
                    args.drop(2).forEach(remoteFiles::remove)
                }
                ""
            }

            val controller =
                WalletController(
                    walletUtils = walletUtils,
                    walletRepository = walletRepository,
                    fileRepository = fileRepository,
                    nodeRepository = nodeRepository,
                    hostRepository = hostRepository,
                    queryTipAdapter = config.getQueryTipAdapter(moshi),
                    shelleyGenesisAdapter = config.getShelleyGenesisAdapter(moshi),
                    protocolParamsAdapter = config.getProtocolParametersAdapter(moshi),
                    webSocketTemplate = mockk<SimpMessagingTemplate>(relaxed = true),
                    cardanoRepository = cardanoRepository,
                    protocolSnapshots = protocolSnapshots,
                    ledgerDao = mockk<LedgerDao>(relaxed = true),
                    transactionBuilder = mockk<WalletTransactionBuilder>(relaxed = true),
                    json = ObjectMapper(),
                )

            val response =
                controller.createWalletEntry(
                    CreateWalletEntryRequest(
                        spendingPassword = "secret",
                        name = "owner-$era",
                        type = "stake",
                        generateKeys = true,
                        paymentAddr = "",
                        paymentSKey = null,
                        paymentVKey = null,
                        stakingSKey = null,
                        stakingVKey = null,
                    ),
                )

            assertThat(response).isInstanceOf(SocketResponse.Success::class.java)
            assertThat(writtenFiles.keys).doesNotContain("/tmp/protocol-parameters.json")
            assertThat(writtenFiles.keys.none { it.startsWith("/tmp/jormanager-pskey") || it.startsWith("/tmp/jormanager-sskey") }).isTrue()

            val chmodPaths = commandArgsCalls.filter { it.take(2) == listOf("chmod", "600") }.map { it[2] }
            assertThat(chmodPaths).hasSize(2)
            chmodPaths.forEach { path ->
                assertThat(path).startsWith("/tmp/jormanager-wallet-")
                val uuidPart = path.removePrefix("/tmp/jormanager-wallet-").substringBefore('.')
                UUID.fromString(uuidPart)
            }

            val regCertCmd = commands.single { "registration-certificate" in it }
            if (expectsDepositArg) {
                assertThat(regCertCmd).contains("--key-reg-deposit-amt 2000000")
            } else {
                assertThat(regCertCmd).doesNotContain("--key-reg-deposit-amt")
            }

            assertThat(remoteFiles).isEmpty()
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test
    fun createPaymentWalletEntryUsesUuidTempPathsChmod600AndCleansUp() {
        val walletUtils = mockk<WalletUtils>()
        val walletRepository = mockk<WalletRepository>()
        val fileRepository = mockk<FileRepository>()
        val nodeRepository = mockk<NodeRepository>()
        val hostRepository = mockk<HostRepository>()
        val cardanoRepository = mockk<CardanoRepository>()

        val defaultNode =
            Node(
                id = 1L,
                hostId = 1L,
                color = "#000000",
                type = "core",
                processorThreads = 2,
                name = "core",
                listen = "127.0.0.1",
                port = 3001,
                promPort = 12798,
                genesisByronFileId = 1L,
                genesisShelleyFileId = 2L,
                genesisAlonzoFileId = 3L,
                genesisConwayFileId = 4L,
                configFileId = 5L,
                isDefault = true,
            )
        val defaultHost =
            Host(
                id = 1L,
                type = "local",
                cardanoCliPath = "/bin/cardano-cli",
                cardanoNodePath = "/bin/cardano-node",
                hostname = "localhost",
                sshUser = "user",
                nodeHomePath = "/nodes",
                jcliPath = null,
            )
        val shelleyGenesis =
            File(
                id = 2L,
                name = "shelley-genesis.json",
                content =
                    """{"activeSlotsCoeff":0.05,"networkId":"Mainnet","networkMagic":764824073,"slotLength":1,"epochLength":432000,"slotsPerKESPeriod":129600,"systemStart":"2017-09-23T21:44:51Z","maxKESEvolutions":62}""",
            )

        every { walletUtils.isValidSpendingPassword("secret") } returns true
        every { walletUtils.encryptSKeyContent(any(), "secret") } answers { "encrypted:${firstArg<String>()}" }
        every { nodeRepository.findDefault() } returns defaultNode
        every { fileRepository.findById(2L) } returns Optional.of(shelleyGenesis)
        every { fileRepository.save(any<File>()) } answers { firstArg<File>().copy(id = 11L) }
        every { hostRepository.findById(1L) } returns Optional.of(defaultHost)
        every { cardanoRepository.getEra(any(), any(), any(), any()) } returns "dijkstra"
        every { walletRepository.save(any<WalletEntry>()) } answers { firstArg() }

        val commandArgsCalls = mutableListOf<List<String>>()
        val remoteFiles = mutableMapOf<String, String>()

        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().commandWriteFile(any(), any()) } answers {
                remoteFiles[firstArg()] = secondArg()
                ""
            }
            every { anyConstructed<HostConnection>().commandReadFile(any()) } answers {
                remoteFiles.getValue(firstArg())
            }
            every { anyConstructed<HostConnection>().command(any<String>()) } answers {
                val cmd = firstArg<String>()
                when {
                    "address key-gen" in cmd -> {
                        val vkey = Regex("--verification-key-file\\s+([^\\s]+)").find(cmd)!!.groupValues[1]
                        val skey = Regex("--signing-key-file\\s+([^\\s]+)").find(cmd)!!.groupValues[1]
                        remoteFiles[vkey] = "pvkey-content"
                        remoteFiles[skey] = "pskey-content"
                        ""
                    }
                    "address build" in cmd -> "addr1_built"
                    else -> ""
                }
            }
            every { anyConstructed<HostConnection>().commandArgs(any()) } answers {
                val args = firstArg<List<String>>()
                commandArgsCalls += args
                if (args.firstOrNull() == "rm") {
                    args.drop(2).forEach(remoteFiles::remove)
                }
                ""
            }

            val controller =
                WalletController(
                    walletUtils = walletUtils,
                    walletRepository = walletRepository,
                    fileRepository = fileRepository,
                    nodeRepository = nodeRepository,
                    hostRepository = hostRepository,
                    queryTipAdapter = config.getQueryTipAdapter(moshi),
                    shelleyGenesisAdapter = config.getShelleyGenesisAdapter(moshi),
                    protocolParamsAdapter = config.getProtocolParametersAdapter(moshi),
                    webSocketTemplate = mockk<SimpMessagingTemplate>(relaxed = true),
                    cardanoRepository = cardanoRepository,
                    protocolSnapshots = mockk<ProtocolParametersRepository>(relaxed = true),
                    ledgerDao = mockk<LedgerDao>(relaxed = true),
                    transactionBuilder = mockk<WalletTransactionBuilder>(relaxed = true),
                    json = ObjectMapper(),
                )

            val response =
                controller.createWalletEntry(
                    CreateWalletEntryRequest(
                        spendingPassword = "secret",
                        name = "fees",
                        type = "payment",
                        generateKeys = true,
                        paymentAddr = "",
                        paymentSKey = null,
                        paymentVKey = null,
                        stakingSKey = null,
                        stakingVKey = null,
                    ),
                )

            assertThat(response).isInstanceOf(SocketResponse.Success::class.java)
            val chmodCall = commandArgsCalls.single { it.take(2) == listOf("chmod", "600") }
            assertThat(chmodCall[2]).startsWith("/tmp/jormanager-wallet-")
            assertThat(chmodCall[2]).endsWith(".pskey")
            assertThat(remoteFiles).isEmpty()
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }
}
