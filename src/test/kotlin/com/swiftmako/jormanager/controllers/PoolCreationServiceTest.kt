package com.swiftmako.jormanager.controllers

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.KeyInstallation
import com.swiftmako.jormanager.entities.LeiosVotingKey
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.Relay as RelayEntity
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.model.CreateNodeRequest
import com.swiftmako.jormanager.model.Metadata
import com.swiftmako.jormanager.model.Relay
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.KeyInstallationRepository
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.repositories.LeiosKeyRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.ProtocolParametersRepository
import com.swiftmako.jormanager.repositories.RelayRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.services.NodeConfigurationFiles
import com.swiftmako.jormanager.services.PoolDeploymentService
import com.swiftmako.jormanager.services.WalletTransactionBuilder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import java.math.BigInteger
import java.util.Optional
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import tools.jackson.databind.json.JsonMapper

class PoolCreationServiceTest {
    private val json = JsonMapper.builder().build()

    private val host =
        Host(
            id = 2L,
            type = "local",
            cardanoCliPath = "/bin/cardano-cli",
            cardanoNodePath = "/bin/cardano-node",
            hostname = "localhost",
            sshUser = "operator",
            nodeHomePath = "/nodes",
            jcliPath = null,
        )

    private val observer =
        Node(
            id = 1L,
            hostId = 2L,
            color = "#000000",
            type = "relay",
            processorThreads = 2,
            name = "observer",
            listen = "127.0.0.1",
            port = 3000,
            promPort = 12789,
            genesisByronFileId = 1L,
            genesisShelleyFileId = 2L,
            genesisAlonzoFileId = 3L,
            genesisConwayFileId = 4L,
            configFileId = 5L,
            isDefault = true,
        )

    private val parentCore =
        observer.copy(
            id = 10L,
            name = "parent-core",
            type = "core",
            port = 3001,
            promPort = 12798,
            tracingPort = 12799,
            isDefault = false,
            poolId = "aa".repeat(28),
        )

    private val shelleyGenesisJson =
        """{"networkId":"Testnet","networkMagic":42,"slotsPerKESPeriod":129600,"maxKESEvolutions":62,"slotLength":1}"""

    private val protocolParamsJson =
        """{"minPoolCost":"340000000","stakePoolDeposit":"500000000","stakeAddressDeposit":"2000000","maxTxSize":"16384"}"""

    private val poolIdHex = "ab".repeat(28)
    private val txIdHex = "cd".repeat(32)
    private val coldKeyCbor = "5820${"11".repeat(32)}"
    private val vrfKeyCbor = "5820${"22".repeat(32)}"
    private val kesKeyCbor = "5820${"33".repeat(32)}"
    private val coldCounterInitialCbor = "8200$coldKeyCbor"
    private val coldCounterAdvancedCbor = "8201$coldKeyCbor"
    private val blsSkeyEnvelope =
        """{"type":"BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size","description":"","cborHex":"5820${"44".repeat(32)}"}"""
    private val blsVkeyCbor = "5860${"55".repeat(96)}"
    private val blsProofCbor = "5830${"66".repeat(48)}"
    private val dijkstraPoolCertCbor = "8b03000082$blsVkeyCbor${blsProofCbor}00000000000000"

    private inner class Harness(
        val era: String = "conway",
        val failMetadata: Boolean = false,
        val failSubmit: Boolean = false,
        val failDeploy: Boolean = false,
        val failDbPersistence: Boolean = false,
    ) {
        val nodes = mockk<NodeRepository>()
        val hosts = mockk<HostRepository>()
        val files = mockk<FileRepository>()
        val wallets = mockk<WalletRepository>()
        val relays = mockk<RelayRepository>()
        val keys = mockk<LeiosKeyRepository>()
        val installations = mockk<KeyInstallationRepository>()
        val walletUtils = mockk<WalletUtils>()
        val configFiles = mockk<NodeConfigurationFiles>()
        val eras = mockk<CardanoRepository>()
        val protocolParams = mockk<ProtocolParametersRepository>()
        val txBuilder = mockk<WalletTransactionBuilder>()
        val ledger = mockk<LedgerDao>()
        val nodeController = mockk<NodeController>()
        val installer = mockk<PoolDeploymentService>()
        val nodesChannel = MutableSharedFlow<Node>(extraBufferCapacity = 4)

        val savedFiles = mutableListOf<File>()
        val savedNodes = mutableListOf<Node>()
        val savedRelays = mutableListOf<RelayEntity>()
        val savedKeys = mutableListOf<LeiosVotingKey>()
        val savedInstallations = mutableListOf<KeyInstallation>()
        val remoteFiles = mutableMapOf<String, String>()
        val commands = mutableListOf<List<String>>()
        val events = mutableListOf<String>()
        var rollbackCalled = false
        var cleanupCalled = false

        private val txManager =
            object : PlatformTransactionManager {
                override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()

                override fun commit(status: TransactionStatus) {}

                override fun rollback(status: TransactionStatus) {
                    savedFiles.clear()
                    savedNodes.clear()
                    savedRelays.clear()
                    savedKeys.clear()
                    savedInstallations.clear()
                }
            }

        val service =
            PoolCreationService(
                nodes = nodes,
                hosts = hosts,
                files = files,
                wallets = wallets,
                relays = relays,
                keys = keys,
                installations = installations,
                walletUtils = walletUtils,
                configurationFiles = configFiles,
                eras = eras,
                protocolParameters = protocolParams,
                txBuilder = txBuilder,
                ledger = ledger,
                nodeController = nodeController,
                installer = installer,
                json = json,
                nodesChannel = nodesChannel,
                transactionManager = txManager,
            )

        val eraObserver =
            if (era == "dijkstra") observer.copy(genesisDijkstraFileId = 6L) else observer
        val eraParent =
            if (era == "dijkstra") parentCore.copy(genesisDijkstraFileId = 6L) else parentCore

        val assets: Map<String, ByteArray> =
            buildMap {
                put("byron-genesis.json", "{}".toByteArray())
                put("shelley-genesis.json", shelleyGenesisJson.toByteArray())
                put("alonzo-genesis.json", "{}".toByteArray())
                put("conway-genesis.json", "{}".toByteArray())
                if (era == "dijkstra") put("dijkstra-genesis.json", "{}".toByteArray())
                put("config.json", "{}".toByteArray())
                put("topology.json", "{}".toByteArray())
            }

        private val catalogFiles =
            mapOf(
                1L to File(1L, "byron-genesis.json", "{}"),
                2L to File(2L, "shelley-genesis.json", shelleyGenesisJson),
                3L to File(3L, "alonzo-genesis.json", "{}"),
                4L to File(4L, "conway-genesis.json", "{}"),
                5L to File(5L, "config.json", "{}"),
                6L to File(6L, "dijkstra-genesis.json", "{}"),
            )

        init {
            val payer =
                WalletEntry(
                    id = 1L,
                    name = "payer",
                    type = "payment",
                    paymentAddr = "addr_test1payer",
                    paymentSkey = File(101L, "pay.skey", """{"cborHex":"5820${"a1".repeat(32)}"}"""),
                    paymentVkey = File(102L, "pay.vkey", """{"cborHex":"5820${"a2".repeat(32)}"}"""),
                )
            val owner =
                WalletEntry(
                    id = 2L,
                    name = "owner",
                    type = "stake",
                    paymentAddr = "addr_test1owner",
                    stakingAddr = "stake_test1owner",
                    stakingSkey = File(103L, "owner.skey", """{"cborHex":"5820${"b1".repeat(32)}"}"""),
                    stakingVkey = File(104L, "owner.vkey", """{"cborHex":"5820${"b2".repeat(32)}"}"""),
                )
            val reward =
                WalletEntry(
                    id = 3L,
                    name = "reward",
                    type = "stake",
                    paymentAddr = "addr_test1reward",
                    stakingAddr = "stake_test1reward",
                    stakingSkey = File(105L, "reward.skey", """{"cborHex":"5820${"c1".repeat(32)}"}"""),
                    stakingVkey = File(106L, "reward.vkey", """{"cborHex":"5820${"c2".repeat(32)}"}"""),
                )

            every { walletUtils.isValidSpendingPassword("password") } returns true
            every { walletUtils.encryptSKeyContent(any(), "password") } answers { "enc:${firstArg<String>()}" }
            every { walletUtils.getSKeyContent(any(), "password") } answers { firstArg<File>().content }
            every { walletUtils.getUtxos(any(), any(), any(), any(), any(), "addr_test1payer") } returns
                listOf(Utxo("ee".repeat(32), 0L, BigInteger.valueOf(2_000_000_000L), emptyList()))
            every { walletUtils.queryReferenceScriptSize(any(), any(), any(), any(), any(), any()) } returns 0L

            every { wallets.findById(1L) } returns Optional.of(payer)
            every { wallets.findById(2L) } returns Optional.of(owner)
            every { wallets.findById(3L) } returns Optional.of(reward)

            every { hosts.findById(2L) } returns Optional.of(host)
            every { nodes.findDefault() } returns eraObserver
            every { nodes.findById(10L) } returns Optional.of(eraParent)
            every { nodes.findByPoolId(poolIdHex) } returns null
            every { files.findById(any()) } answers { Optional.ofNullable(catalogFiles[firstArg()]) }

            every { eras.networkKey(any()) } returns "testnet-42"
            every { eras.getEra(host, eraObserver, "--testnet-magic 42", "--socket-path /nodes/observer/db/socket") } returns era
            every { eras.evidence(eraObserver) } returns
                CardanoRepository.EraEvidence("testnet-42", era, "current", "host:2/node:1", generation = 1L)

            every { protocolParams.query(host, eraObserver, any(), era, "--testnet-magic 42", "--socket-path /nodes/observer/db/socket") } returns
                protocolParamsJson
            every { configFiles.resolve(any()) } returns assets
            every { configFiles.validate(any()) } returns Unit

            every { nodeController.allocatePrometheusPort(2L, any(), any()) } returns 12798
            every { nodeController.allocateTracingPort(12798, any()) } returns 12799
            every { nodeController.renderManagedConfig(any(), any(), any(), any(), any(), any()) } returns "{}"
            every { nodeController.publishPoolMetadataMaterial(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                events += "metadata"
                if (failMetadata) throw IllegalStateException("metadata upload failed")
                NodeController.PreparedPoolMetadata(
                    url = "https://cardanostakehouse.com/pool.json",
                    hash = "ff".repeat(32),
                    extendedUrl = "https://cardanostakehouse.com/ext.json",
                    encryptedItnPrivateKey = null,
                    itnPublicKey = null,
                )
            }

            every { ledger.withSubmissionLock(any<() -> String>()) } answers { firstArg<() -> String>().invoke() }
            every { ledger.recordSubmitted(host, eraObserver, era, txIdHex, any()) } answers {
                events += "recordSubmitted"
            }
            every { txBuilder.selectAdminInputs(any(), any(), any(), any(), any(), any(), any()) } answers { firstArg() }
            every { txBuilder.validateSignedEnvelopeSize(any(), any()) } returns Unit

            every {
                txBuilder.build(
                    host = any(),
                    connection = any(),
                    era = eq(era),
                    inputs = any(),
                    outputs = any(),
                    changeAddress = any(),
                    withdrawals = any(),
                    deposits = any(),
                    refunds = any(),
                    extraArgs = any(),
                    validFrom = any(),
                    validUntil = any(),
                    protocolParameters = any(),
                    protocolPath = any(),
                    witnessCount = any(),
                    bodyPath = any(),
                    referenceScriptSize = any(),
                    outputsForFee = any(),
                    changeLayout = any(),
                )
            } returns WalletTransactionBuilder.CliBody("""{"cborHex":"00"}""", txIdHex)

            every { installer.isValidBlsSigningEnvelope(any()) } returns true
            every { installer.validateTarget(any(), any(), any(), any(), any()) } returns null
            every { installer.deploy(any(), any(), any(), any(), any(), any()) } answers {
                events += "deploy"
                if (failDeploy) throw IllegalStateException("host deployment failed")
                PoolDeploymentService.DeploymentResult(
                    configurationFingerprint = "cfg-fp",
                    installedFingerprint = if (era == "dijkstra") "bls-fp" else null,
                    kesExpireTimeSec = 1_800_000_000L,
                    kesExpireDate = "2027-01-15 08:00am UTC",
                    rollback = { rollbackCalled = true },
                    cleanup = { cleanupCalled = true },
                )
            }

            var nextFileId = 500L
            every { files.save(any<File>()) } answers {
                events += "saveFile"
                val saved = firstArg<File>().copy(id = nextFileId++)
                savedFiles += saved
                saved
            }
            every { nodes.save(any<Node>()) } answers {
                events += "saveNode"
                if (failDbPersistence) throw IllegalStateException("database write failure")
                val saved = firstArg<Node>().copy(id = 42L)
                savedNodes += saved
                saved
            }
            every { relays.saveAll(any<Iterable<RelayEntity>>()) } answers {
                events += "saveRelays"
                val items = firstArg<Iterable<RelayEntity>>().mapIndexed { i, r -> r.copy(id = 700L + i) }
                savedRelays += items
                items
            }
            every { keys.save(any<LeiosVotingKey>()) } answers {
                events += "saveKey"
                val arg = firstArg<LeiosVotingKey>()
                val saved =
                    LeiosVotingKey(
                        id = 90L,
                        networkKey = arg.networkKey,
                        poolId = arg.poolId,
                        publicKey = arg.publicKey,
                        proof = arg.proof,
                        signingFileId = arg.signingFileId,
                        preparedAt = arg.preparedAt,
                        validationProvenance = arg.validationProvenance,
                    )
                savedKeys += saved
                saved
            }
            every { installations.save(any<KeyInstallation>()) } answers {
                events += "saveInstallation"
                val arg = firstArg<KeyInstallation>()
                val saved =
                    KeyInstallation(
                        id = 91L,
                        keyId = arg.keyId,
                        nodeId = arg.nodeId,
                        hostId = arg.hostId,
                        configurationFingerprint = arg.configurationFingerprint,
                        installedFingerprint = arg.installedFingerprint,
                        loadedFingerprint = arg.loadedFingerprint,
                        permissionResult = arg.permissionResult,
                        state = arg.state,
                        observedAt = arg.observedAt,
                        sanitizedError = arg.sanitizedError,
                    )
                savedInstallations += saved
                saved
            }

            remoteFiles["/nodes/parent-core/config.json"] = "{}"
            remoteFiles["/nodes/parent-core/topology.json"] = "{}"
        }

        fun installHostConnectionMocks() {
            mockkConstructor(HostConnection::class)
            every { anyConstructed<HostConnection>().commandFileExists(any()) } answers { remoteFiles.containsKey(firstArg()) }
            every { anyConstructed<HostConnection>().commandReadFile(any()) } answers { remoteFiles.getValue(firstArg()) }
            every { anyConstructed<HostConnection>().commandWriteFile(any(), any()) } answers {
                remoteFiles[firstArg()] = secondArg()
                ""
            }
            every { anyConstructed<HostConnection>().commandWriteSecret(any(), any()) } answers {
                remoteFiles[firstArg()] = secondArg()
            }
            every { anyConstructed<HostConnection>().commandArgs(any()) } answers {
                val args = firstArg<List<String>>()
                commands += args
                when {
                    args.take(2) == listOf("mktemp", "-d") -> "/tmp/jormanager-pool-test123456"
                    args.first() == "ss" -> ""
                    args.first() == "cat" -> remoteFiles.getValue(args[1])
                    args.first() == "chmod" -> ""
                    args.first() == "rm" -> {
                        val recursive = "-rf" in args
                        args.filter { !it.startsWith("-") && it != "rm" }.forEach { path ->
                            remoteFiles.remove(path)
                            if (recursive) {
                                remoteFiles.keys
                                    .filter { it.startsWith("$path/") }
                                    .toList()
                                    .forEach(remoteFiles::remove)
                            }
                        }
                        ""
                    }
                    "tip" in args -> """{"slot":259200,"epoch":2,"era":"${era.replaceFirstChar { it.uppercase() }}","hash":"${"aa".repeat(32)}"}"""
                    "pool-state" in args -> "{}"
                    "stake-address-info" in args -> """[{"address":"stake_test1"}]"""
                    args.contains("key-gen") -> {
                        val vkey = args[args.indexOf("--verification-key-file") + 1]
                        val skey = args[args.indexOf("--signing-key-file") + 1]
                        val counter = args[args.indexOf("--operational-certificate-issue-counter") + 1]
                        remoteFiles[vkey] = """{"cborHex":"$coldKeyCbor"}"""
                        remoteFiles[skey] = """{"cborHex":"$coldKeyCbor"}"""
                        remoteFiles[counter] = """{"cborHex":"$coldCounterInitialCbor"}"""
                        ""
                    }
                    args.contains("key-gen-VRF") -> {
                        val vkey = args[args.indexOf("--verification-key-file") + 1]
                        val skey = args[args.indexOf("--signing-key-file") + 1]
                        remoteFiles[vkey] = """{"cborHex":"$vrfKeyCbor"}"""
                        remoteFiles[skey] = """{"cborHex":"$vrfKeyCbor"}"""
                        ""
                    }
                    args.contains("key-gen-KES") -> {
                        val vkey = args[args.indexOf("--verification-key-file") + 1]
                        val skey = args[args.indexOf("--signing-key-file") + 1]
                        remoteFiles[vkey] = """{"cborHex":"$kesKeyCbor"}"""
                        remoteFiles[skey] = """{"cborHex":"$kesKeyCbor"}"""
                        ""
                    }
                    args.contains("key-gen-BLS") -> {
                        val vkey = args[args.indexOf("--verification-key-file") + 1]
                        val skey = args[args.indexOf("--signing-key-file") + 1]
                        remoteFiles[vkey] = """{"cborHex":"$blsVkeyCbor"}"""
                        remoteFiles[skey] = blsSkeyEnvelope
                        ""
                    }
                    args.contains("verification-key") -> {
                        val skeyPath = args[args.indexOf("--signing-key-file") + 1]
                        val derivedPath = args[args.indexOf("--verification-key-file") + 1]
                        remoteFiles[derivedPath] =
                            when {
                                skeyPath.endsWith("bls.skey") -> """{"cborHex":"$blsVkeyCbor"}"""
                                skeyPath.endsWith("cold.skey") -> """{"cborHex":"$coldKeyCbor"}"""
                                skeyPath.endsWith("vrf.skey") -> """{"cborHex":"$vrfKeyCbor"}"""
                                else -> """{"cborHex":"$kesKeyCbor"}"""
                            }
                        ""
                    }
                    args.contains("stake-pool") && args.contains("id") -> poolIdHex
                    args.contains("registration-certificate") && args.contains("stake-pool") -> {
                        val out = args[args.indexOf("--out-file") + 1]
                        remoteFiles[out] =
                            if (era == "dijkstra") """{"cborHex":"$dijkstraPoolCertCbor"}""" else """{"cborHex":"8a03"}"""
                        ""
                    }
                    args.contains("stake-delegation-certificate") -> {
                        val out = args[args.indexOf("--out-file") + 1]
                        remoteFiles[out] = """{"cborHex":"00"}"""
                        ""
                    }
                    args.contains("issue-op-cert") -> {
                        val counter = args[args.indexOf("--operational-certificate-issue-counter") + 1]
                        val out = args[args.indexOf("--out-file") + 1]
                        remoteFiles[counter] = """{"cborHex":"$coldCounterAdvancedCbor"}"""
                        remoteFiles[out] = """{"cborHex":"5820${"77".repeat(32)}"}"""
                        ""
                    }
                    args.contains("transaction") && args.contains("sign") -> {
                        val out = args[args.indexOf("--out-file") + 1]
                        remoteFiles[out] = """{"cborHex":"84a0a0f5f6"}"""
                        ""
                    }
                    args.contains("transaction") && args.contains("submit") -> {
                        events += "submitTx"
                        if (failSubmit) throw IllegalStateException("node rejected transaction")
                        ""
                    }
                    else -> ""
                }
            }
        }
    }

    private fun createRequest(
        type: String = "core",
        parentId: Long? = null,
        era: String = "conway",
        poolCost: BigInteger = BigInteger.valueOf(340_000_000L),
        spendingPassword: String = "password",
    ) = CreateNodeRequest(
        requestId = UUID.randomUUID().toString(),
        spendingPassword = spendingPassword,
        sudoPassword = "sudo",
        color = "#123456",
        hostId = 2L,
        parentId = parentId,
        name = if (type == "pool") "child1" else "core1",
        isDefault = false,
        type = type,
        processorThreads = 2,
        listen = "0.0.0.0",
        port = 3001,
        prometheusListen = "127.0.0.1",
        enableTracingListener = true,
        tracingListen = "127.0.0.1",
        genesisByronFileId = if (parentId == null) 1L else 0L,
        genesisShelleyFileId = if (parentId == null) 2L else 0L,
        genesisAlonzoFileId = if (parentId == null) 3L else 0L,
        genesisConwayFileId = if (parentId == null) 4L else 0L,
        genesisDijkstraFileId = if (parentId == null && era == "dijkstra") 6L else null,
        configFileId = if (parentId == null) 5L else 0L,
        topologyFileId = if (parentId == null) 5L else 0L,
        generateColdKeys = true,
        coldSKey = null,
        coldVKey = null,
        coldCounter = null,
        generateVRFKeys = true,
        vrfSKey = null,
        vrfVKey = null,
        generateKESKeys = true,
        kesSKey = null,
        kesVKey = null,
        registrationFeesAccount = 1L,
        ownerStakingAccount = 2L,
        rewardsStakingAccount = 3L,
        poolPledge = BigInteger.valueOf(100_000_000L),
        poolCost = poolCost,
        poolMargin = "0.05",
        relays = listOf(Relay("relay1.example.org", 3001)),
        metadata = Metadata("POOL", "Test Pool", "Test pool description", "https://example.org", null),
    )

    @Test
    fun `invalid password fails pre-flight before any queries or mutations`() {
        val harness = Harness()
        every { harness.walletUtils.isValidSpendingPassword("wrong") } returns false
        assertThrows<IllegalArgumentException> {
            harness.service.create(createRequest(spendingPassword = "wrong"))
        }
        verify(exactly = 0) { harness.nodes.findDefault() }
        verify(exactly = 0) { harness.files.save(any()) }
        verify(exactly = 0) { harness.nodes.save(any()) }
    }

    @Test
    fun `pre-flight failure when pool cost is below minPoolCost leaves zero DB rows and zero side effects`() {
        val harness = Harness("conway")
        harness.installHostConnectionMocks()
        try {
            val error =
                assertThrows<IllegalArgumentException> {
                    harness.service.create(createRequest(poolCost = BigInteger.valueOf(100_000_000L)))
                }
            assertThat(error.message).contains("Pool cost is below the current minimum")
            assertThat(harness.savedFiles).isEmpty()
            assertThat(harness.savedNodes).isEmpty()
            assertThat(harness.savedRelays).isEmpty()
            verify(exactly = 0) { harness.nodeController.publishPoolMetadataMaterial(any(), any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 0) { harness.installer.deploy(any(), any(), any(), any(), any(), any()) }
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test
    fun `core creation in conway and dijkstra eras persists records only after registration and host deployment succeed`() {
        for (era in listOf("conway", "dijkstra")) {
            val harness = Harness(era)
            harness.installHostConnectionMocks()
            try {
                val created = harness.service.create(createRequest(type = "core", era = era))
                assertThat(created.id).isEqualTo(42L)
                assertThat(created.poolId).isEqualTo(poolIdHex)
                assertThat(created.relays).hasSize(1)
                assertThat(harness.cleanupCalled).isTrue()
                assertThat(harness.rollbackCalled).isFalse()

                // Temp key directory was removed in finally
                assertThat(harness.remoteFiles.keys.filter { it.startsWith("/tmp/jormanager-pool-") }).isEmpty()

                // Strict ordering: metadata -> submitTx -> recordSubmitted -> deploy -> DB saves
                val metadataIdx = harness.events.indexOf("metadata")
                val submitIdx = harness.events.indexOf("submitTx")
                val recordIdx = harness.events.indexOf("recordSubmitted")
                val deployIdx = harness.events.indexOf("deploy")
                val firstSaveIdx = harness.events.indexOf("saveFile")
                assertThat(metadataIdx).isAtLeast(0)
                assertThat(submitIdx).isGreaterThan(metadataIdx)
                assertThat(recordIdx).isGreaterThan(submitIdx)
                assertThat(deployIdx).isGreaterThan(recordIdx)
                assertThat(firstSaveIdx).isGreaterThan(deployIdx)

                if (era == "dijkstra") {
                    assertThat(harness.savedKeys).hasSize(1)
                    assertThat(harness.savedInstallations).hasSize(1)
                    assertThat(harness.savedInstallations.single().keyId).isEqualTo(90L)
                } else {
                    assertThat(harness.savedKeys).isEmpty()
                    assertThat(harness.savedInstallations).isEmpty()
                }
            } finally {
                unmockkConstructor(HostConnection::class)
            }
        }
    }

    @Test
    fun `child pool creation in dijkstra era inherits parent configuration and persists parentId`() {
        val harness = Harness("dijkstra")
        harness.installHostConnectionMocks()
        try {
            val created = harness.service.create(createRequest(type = "pool", parentId = 10L, era = "dijkstra"))
            assertThat(created.id).isEqualTo(42L)
            assertThat(created.type).isEqualTo("pool")
            assertThat(created.parentId).isEqualTo(10L)
            assertThat(harness.savedKeys).hasSize(1)
            assertThat(harness.savedInstallations).hasSize(1)
            // Pre-flight + candidate VRF duplicate check both ran before registration
            verify(exactly = 2) { harness.installer.validateTarget(any(), any(), any(), any(), any()) }
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test
    fun `metadata publication failure aborts before registration or deployment and leaves zero DB rows`() {
        val harness = Harness("dijkstra", failMetadata = true)
        harness.installHostConnectionMocks()
        try {
            assertThrows<IllegalStateException> {
                harness.service.create(createRequest(type = "core", era = "dijkstra"))
            }
            assertThat(harness.events).doesNotContain("submitTx")
            assertThat(harness.events).doesNotContain("deploy")
            assertThat(harness.savedFiles).isEmpty()
            assertThat(harness.savedNodes).isEmpty()
            assertThat(harness.savedRelays).isEmpty()
            assertThat(harness.savedKeys).isEmpty()
            assertThat(harness.remoteFiles.keys.filter { it.startsWith("/tmp/jormanager-pool-") }).isEmpty()
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test
    fun `registration submission failure aborts before host deployment and leaves zero DB rows`() {
        val harness = Harness("conway", failSubmit = true)
        harness.installHostConnectionMocks()
        try {
            assertThrows<IllegalStateException> {
                harness.service.create(createRequest(type = "core", era = "conway"))
            }
            assertThat(harness.events).doesNotContain("recordSubmitted")
            assertThat(harness.events).doesNotContain("deploy")
            assertThat(harness.savedFiles).isEmpty()
            assertThat(harness.savedNodes).isEmpty()
            assertThat(harness.savedRelays).isEmpty()
            assertThat(harness.remoteFiles.keys.filter { it.startsWith("/tmp/jormanager-pool-registration-") }).isEmpty()
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test
    fun `host deployment failure throws PoolCreationFailure and leaves zero DB rows`() {
        val harness = Harness("dijkstra", failDeploy = true)
        harness.installHostConnectionMocks()
        try {
            val error =
                assertThrows<PoolCreationFailure> {
                    harness.service.create(createRequest(type = "core", era = "dijkstra"))
                }
            assertThat(error.message).contains(txIdHex)
            assertThat(error.message).contains("rolled back")
            assertThat(harness.savedFiles).isEmpty()
            assertThat(harness.savedNodes).isEmpty()
            assertThat(harness.savedRelays).isEmpty()
            assertThat(harness.savedKeys).isEmpty()
            assertThat(harness.savedInstallations).isEmpty()
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test
    fun `database persistence failure rolls back host deployment cleans up backups and leaves zero committed DB rows`() {
        val harness = Harness("dijkstra", failDbPersistence = true)
        harness.installHostConnectionMocks()
        try {
            val error =
                assertThrows<PoolCreationFailure> {
                    harness.service.create(createRequest(type = "core", era = "dijkstra"))
                }
            assertThat(error.message).contains("database persistence failed")
            assertThat(harness.rollbackCalled).isTrue()
            assertThat(harness.cleanupCalled).isTrue()
            assertThat(harness.savedFiles).isEmpty()
            assertThat(harness.savedNodes).isEmpty()
            assertThat(harness.savedRelays).isEmpty()
            assertThat(harness.savedKeys).isEmpty()
            assertThat(harness.savedInstallations).isEmpty()
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }
}
