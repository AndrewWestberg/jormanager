package com.swiftmako.jormanager.controllers

import com.google.common.truth.Truth.assertThat
import com.muquit.libsodiumjna.SodiumLibrary
import com.swiftmako.jormanager.controllers.utils.BlockUtils
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.ChainBlock
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.LeaderLogsRequest
import com.swiftmako.jormanager.repositories.BlockRepository
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.ChainRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.services.LeaderLogCalculator
import com.swiftmako.jormanager.spring.config.Configuration
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import org.springframework.messaging.simp.SimpMessagingTemplate

class BlockControllerFilteringTest {
    private val config = Configuration()
    private val moshi = config.getMoshi()

    @Test
    fun `calculateLeaderLogs excludes draft deleted blank-poolId relay and cross-network nodes`() {
        val blockRepository = mockk<BlockRepository>(relaxed = true)
        val nodeRepository = mockk<NodeRepository>()
        val hostRepository = mockk<HostRepository>()
        val fileRepository = mockk<FileRepository>()
        val chainRepository = mockk<ChainRepository>()
        val cardanoRepository = mockk<CardanoRepository>()
        val walletUtils = mockk<WalletUtils>()
        val blockUtils = mockk<BlockUtils>()
        val leaderLogCalculator = mockk<LeaderLogCalculator>()
        val websocket = mockk<SimpMessagingTemplate>()

        val host =
            Host(
                id = 1L,
                type = "local",
                cardanoCliPath = "cardano-cli",
                cardanoNodePath = "cardano-node",
                hostname = "localhost",
                sshUser = "user",
                nodeHomePath = "/nodes",
                jcliPath = null,
            )
        val activePool =
            Node(
                id = 1L,
                hostId = 1L,
                color = "#000000",
                type = "core",
                processorThreads = 2,
                name = "active-pool",
                listen = "127.0.0.1",
                port = 3001,
                promPort = 12798,
                genesisByronFileId = 1L,
                genesisShelleyFileId = 2L,
                genesisAlonzoFileId = 3L,
                genesisConwayFileId = 4L,
                configFileId = 5L,
                poolId = "active-pool-id",
                isDefault = true,
                vrfSKeyId = 6L,
            )
        val draftPool = activePool.copy(id = 2L, name = "draft-pool", poolId = "draft-pool-id", isDefault = false, isDraft = true, vrfSKeyId = -1L)
        val blankPool = activePool.copy(id = 3L, name = "blank-pool", poolId = "   ", isDefault = false, vrfSKeyId = -1L)
        val deletedPool = activePool.copy(id = 4L, name = "deleted-pool", poolId = "deleted-pool-id", isDefault = false, isDeleted = true, vrfSKeyId = -1L)
        val otherNetPool = activePool.copy(id = 5L, name = "other-net-pool", poolId = "other-net-pool-id", isDefault = false, genesisShelleyFileId = 999L, vrfSKeyId = -1L)
        val relayNode = activePool.copy(id = 6L, name = "relay-node", type = "relay", poolId = null, isDefault = false, vrfSKeyId = -1L)

        val byron =
            File(
                id = 1L,
                name = "byron",
                content = """{"startTime":0,"protocolConsts":{"k":1},"blockVersionData":{"slotDuration":1000}}""",
            )
        val shelley =
            File(
                id = 2L,
                name = "shelley",
                content =
                    """{"activeSlotsCoeff":0.05,"networkId":"Testnet","networkMagic":2,"slotLength":1,"epochLength":3600,"slotsPerKESPeriod":3600,"systemStart":"1970-01-01T00:00:00Z","maxKESEvolutions":62}""",
            )
        val vrfKey =
            File(
                id = 6L,
                name = "vrf.skey",
                content = """{"type":"VrfSigningKey_PraosVRF","description":"","cborHex":"5840${"00".repeat(64)}"}""",
            )
        val protocolJson =
            """{"stakePoolDeposit":0,"protocolVersion":{"major":9,"minor":0},"decentralization":null,"maxTxSize":1,"minPoolCost":0,"txFeePerByte":0,"maxBlockBodySize":1,"txFeeFixed":0,"poolRetireMaxEpoch":1,"maxBlockHeaderSize":1,"stakeAddressDeposit":0,"stakePoolTargetNum":1,"monetaryExpansion":0,"treasuryCut":0,"poolPledgeInfluence":0}"""

        every { walletUtils.isValidSpendingPassword("secret") } returns true
        every { walletUtils.getSKeyContent(vrfKey, "secret") } returns vrfKey.content
        every { nodeRepository.findDefault() } returns activePool
        every { nodeRepository.findAll() } returns listOf(activePool, draftPool, blankPool, deletedPool, otherNetPool, relayNode)
        every { cardanoRepository.networkKey(any()) } answers { "network:${firstArg<Node>().genesisShelleyFileId}" }
        every { cardanoRepository.getEra(any(), any(), any(), any()) } returns "conway"
        every { hostRepository.findById(1L) } returns Optional.of(host)
        every { fileRepository.findById(any()) } answers {
            val id = firstArg<Long>()
            assertThat(id).isNotEqualTo(-1L)
            when (id) {
                1L -> Optional.of(byron)
                2L -> Optional.of(shelley)
                6L -> Optional.of(vrfKey)
                else -> Optional.empty()
            }
        }
        every { blockUtils.getFirstSlotOfEpoch(any(), any(), any()) } returns 10_000_800L
        every { chainRepository.findFirstBeforeSlot(any(), any()) } returns
            listOf(
                ChainBlock(
                    blockNumber = 1L,
                    slotNumber = 1L,
                    hash = "00".repeat(32),
                    prevHash = "00".repeat(32),
                    etaV = "00".repeat(32),
                    poolId = "active-pool-id",
                    leaderVrf = "",
                ),
            )

        val poolsSlot = slot<List<LeaderLogCalculator.PoolInput>>()
        coEvery {
            leaderLogCalculator.calculate(
                firstSlot = any(),
                slotCount = any(),
                mode = any(),
                decentralization = any(),
                epochNonce = any(),
                pools = capture(poolsSlot),
                onChunk = any(),
            )
        } returns Unit

        val done = CompletableFuture<Unit>()
        val errors = CopyOnWriteArrayList<SocketResponse.PublicFailure>()
        val messages = CopyOnWriteArrayList<String>()
        every { websocket.convertAndSend("/topic/messages", any<Any>()) } answers {
            when (val payload = secondArg<Any>()) {
                is SocketResponse.Error -> {
                    errors += payload.exception
                    done.complete(Unit)
                }
                is SocketResponse.Success<*> -> {
                    if (payload.type == "leaderlogs") {
                        messages += payload.data.toString()
                        if (payload.data == "Leader Logs calculation complete!") {
                            done.complete(Unit)
                        }
                    }
                }
            }
        }

        val queriedPoolIds = CopyOnWriteArrayList<String>()
        mockkConstructor(HostConnection::class)
        mockkStatic(SodiumLibrary::class)
        try {
            every { SodiumLibrary.cryptoBlake2bHash(any(), any()) } returns ByteArray(32)
            every { anyConstructed<HostConnection>().command(any<String>()) } answers {
                val cmd = firstArg<String>()
                when {
                    "query tip" in cmd -> """{"era":"Conway","slot":10000900}"""
                    "query stake-snapshot" in cmd -> {
                        Regex("--stake-pool-id\\s+([^\\s]+)")
                            .findAll(cmd)
                            .map { it.groupValues[1] }
                            .forEach(queriedPoolIds::add)
                        """{"pools":{"active-pool-id":{"stakeSet":1,"stakeMark":2,"stakeGo":0}},"total":{"stakeSet":3,"stakeMark":3,"stakeGo":3}}"""
                    }
                    "query protocol-parameters" in cmd -> protocolJson
                    else -> error("Unexpected command: $cmd")
                }
            }

            val controller =
                BlockController(
                    buildProperties = mockk(relaxed = true),
                    blockRepository = blockRepository,
                    nodeRepository = nodeRepository,
                    hostRepository = hostRepository,
                    fileRepository = fileRepository,
                    blockUtils = blockUtils,
                    leaderLogCalculator = leaderLogCalculator,
                    walletUtils = walletUtils,
                    webSocketTemplate = websocket,
                    byronGenesisAdapter = config.getByronGenesisAdapter(moshi),
                    shelleyGenesisAdapter = config.getShelleyGenesisAdapter(moshi),
                    protocolParamsAdapter = config.getProtocolParametersAdapter(moshi),
                    queryTipAdapter = config.getQueryTipAdapter(moshi),
                    keyAdapter = config.getKeyAdapter(moshi),
                    stakeSnapshotAdapter = config.getStakeSnapshotAdapter(moshi),
                    chainRepository = chainRepository,
                    mp = false,
                    pastEpochsToShow = 2,
                    nodeController = mockk(relaxed = true),
                    cardanoRepository = cardanoRepository,
                )

            controller.calculateLeaderLogs(LeaderLogsRequest("secret", "currentEpoch"))
            done.get(15, TimeUnit.SECONDS)

            assertThat(errors).isEmpty()
            assertThat(queriedPoolIds).containsExactly("active-pool-id")
            assertThat(poolsSlot.captured).hasSize(1)
            assertThat(messages).contains("Leader Logs calculation complete!")
        } finally {
            unmockkStatic(SodiumLibrary::class)
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test
    fun `calculateLeaderLogs emits clean error when only draft or cross-network pools exist`() {
        val nodeRepository = mockk<NodeRepository>()
        val cardanoRepository = mockk<CardanoRepository>()
        val walletUtils = mockk<WalletUtils>()
        val websocket = mockk<SimpMessagingTemplate>()

        val defaultRelay =
            Node(
                id = 1L,
                hostId = 1L,
                color = "#000000",
                type = "relay",
                processorThreads = 2,
                name = "default-relay",
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
        val draftCore = defaultRelay.copy(id = 2L, name = "draft-core", type = "core", poolId = "pool-draft", isDefault = false, isDraft = true)
        val otherNetCore = defaultRelay.copy(id = 3L, name = "other-net", type = "core", poolId = "pool-other", isDefault = false, genesisShelleyFileId = 999L)

        every { walletUtils.isValidSpendingPassword("secret") } returns true
        every { nodeRepository.findDefault() } returns defaultRelay
        every { nodeRepository.findAll() } returns listOf(defaultRelay, draftCore, otherNetCore)
        every { cardanoRepository.networkKey(any()) } answers { "network:${firstArg<Node>().genesisShelleyFileId}" }

        val done = CompletableFuture<SocketResponse.PublicFailure>()
        every { websocket.convertAndSend("/topic/messages", any<Any>()) } answers {
            val payload = secondArg<Any>()
            if (payload is SocketResponse.Error) {
                done.complete(payload.exception)
            }
        }

        val controller =
            BlockController(
                buildProperties = mockk(relaxed = true),
                blockRepository = mockk(relaxed = true),
                nodeRepository = nodeRepository,
                hostRepository = mockk(relaxed = true),
                fileRepository = mockk(relaxed = true),
                blockUtils = mockk(relaxed = true),
                leaderLogCalculator = mockk(relaxed = true),
                walletUtils = walletUtils,
                webSocketTemplate = websocket,
                byronGenesisAdapter = config.getByronGenesisAdapter(moshi),
                shelleyGenesisAdapter = config.getShelleyGenesisAdapter(moshi),
                protocolParamsAdapter = config.getProtocolParametersAdapter(moshi),
                queryTipAdapter = config.getQueryTipAdapter(moshi),
                keyAdapter = config.getKeyAdapter(moshi),
                stakeSnapshotAdapter = config.getStakeSnapshotAdapter(moshi),
                chainRepository = mockk(relaxed = true),
                mp = false,
                pastEpochsToShow = 2,
                nodeController = mockk(relaxed = true),
                cardanoRepository = cardanoRepository,
            )

        controller.calculateLeaderLogs(LeaderLogsRequest("secret", "currentEpoch"))
        val failure = done.get(15, TimeUnit.SECONDS)
        assertThat(failure.operation).isEqualTo("leaderlogs")
    }
}
