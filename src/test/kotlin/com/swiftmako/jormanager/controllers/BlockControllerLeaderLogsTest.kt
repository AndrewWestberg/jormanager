package com.swiftmako.jormanager.controllers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.controllers.utils.BlockUtils
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.Block
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
import com.swiftmako.jormanager.spring.config.Configuration
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Test
import org.springframework.messaging.simp.SimpMessagingTemplate

class BlockControllerLeaderLogsTest {
    private val config = Configuration()
    private val moshi = config.getMoshi()
    private val schedules: JsonNode =
        requireNotNull(javaClass.getResourceAsStream("/leaderlog/node-schedules.json")).use {
            ObjectMapper().readTree(it)["cases"]
        }

    private fun schedule(firstSlot: Long, fDenominator: String = "20"): JsonNode =
        schedules.single {
            it["mode"].asText() == "praos" &&
                it["firstSlot"].asLong() == firstSlot &&
                it["fDenominator"].asText() == fDenominator
        }

    @Test
    fun `current stakeSet schedule matches node and existing pool slot is not duplicated`() {
        val oracle = schedule(FIRST_SLOT)
        val expected = oracle["assignedSlots"].map { it.asLong() }
        assertThat(expected).isNotEmpty()
        val existing = existingBlock(expected.first())
        val result = calculate(existing = existing)

        assertThat(result.saved.map { it.slot }.sorted()).containsExactlyElementsIn(expected.drop(1)).inOrder()
        assertThat((result.saved.map { it.slot } + existing.slot).sorted()).containsExactlyElementsIn(expected).inOrder()
        assertPendingBlocks(result.saved, FIRST_SLOT)
        assertThat(result.chainQueries).containsExactly(FIRST_SLOT - 60, FIRST_SLOT - EPOCH_LENGTH).inOrder()
    }

    @Test
    fun `future epoch uses stakeMark instead of stakeSet and matches node schedule`() {
        val oracle = schedule(FIRST_SLOT + EPOCH_LENGTH)
        val result = calculate(requestType = "futureEpoch")

        assertThat(result.saved.map { it.slot }.sorted())
            .containsExactlyElementsIn(oracle["assignedSlots"].map { it.asLong() }).inOrder()
        assertPendingBlocks(result.saved, FIRST_SLOT + EPOCH_LENGTH)
        assertThat(result.chainQueries)
            .containsExactly(FIRST_SLOT + EPOCH_LENGTH - 60, FIRST_SLOT).inOrder()
    }

    @Test
    fun `f one elects zero stake for every slot as node does`() {
        val oracle = schedule(FIRST_SLOT, "1")
        val result = calculate(f = "1", stakeSet = "0", stakeMark = "0")

        assertThat(result.saved.map { it.slot }.sorted())
            .containsExactlyElementsIn(oracle["assignedSlots"].map { it.asLong() }).inOrder()
        assertPendingBlocks(result.saved, FIRST_SLOT)
    }

    @Test
    fun `zero stake below f one produces no leaders in either VRF era`() {
        for (era in listOf("Alonzo", "Babbage", "Conway")) {
            val result = calculate(era = era, stakeSet = "0", stakeMark = "0")
            assertThat(result.saved).isEmpty()
        }
    }

    @Test
    fun `exact decimal overlay remains nonzero through protocol JSON and controller`() {
        // With d=1/2500 the rational ceiling changes at offsets 0 and 2500.
        // f=1 makes the expected schedule independent of VRF output while still exercising it.
        val result = calculate(era = "Alonzo", f = "1", d = "4e-4", stakeSet = "0", stakeMark = "0")
        val expected = (FIRST_SLOT until FIRST_SLOT + EPOCH_LENGTH).filter {
            it != FIRST_SLOT && it != FIRST_SLOT + 2500
        }
        assertThat(result.saved.map { it.slot }.sorted()).containsExactlyElementsIn(expected).inOrder()
        assertThat(result.saved.map { it.slot }).contains(FIRST_SLOT + 2499)
        assertPendingBlocks(result.saved, FIRST_SLOT)
    }

    @Test
    fun `stability window ceilings preserve original decimal around integer boundary`() {
        for ((era, multiplier) in listOf("Alonzo" to 3L, "Conway" to 4L)) {
            for ((f, expectedWindow) in listOf(
                "0.0999999999999999999" to multiplier * 10 + 1,
                "0.1" to multiplier * 10,
                "0.1000000000000000001" to multiplier * 10,
            )) {
                val result = calculate(era = era, f = f, stakeSet = "0", stakeMark = "0")
                assertThat(result.chainQueries)
                    .containsExactly(FIRST_SLOT - expectedWindow, FIRST_SLOT - EPOCH_LENGTH).inOrder()
                assertThat(result.saved).isEmpty()
            }
        }
    }

    private fun assertPendingBlocks(blocks: List<Block>, firstSlot: Long) {
        blocks.forEach {
            assertThat(it.pool).isEqualTo("synthetic")
            assertThat(it.host).isEqualTo("synthetic-host")
            assertThat(it.status).isEqualTo("pending")
            assertThat(it.hash).isEmpty()
            assertThat(it.epoch).isEqualTo(firstSlot / EPOCH_LENGTH)
            assertThat(it.slotInEpoch).isEqualTo(it.slot - firstSlot)
        }
    }

    private data class Result(val saved: List<Block>, val chainQueries: List<Long>)

    private fun calculate(
        requestType: String = "currentEpoch",
        era: String = "Babbage",
        f: String = "0.05",
        d: String? = null,
        stakeSet: String = "1",
        stakeMark: String = "2",
        existing: Block? = null,
    ): Result {
        val blockRepository = mockk<BlockRepository>()
        val nodeRepository = mockk<NodeRepository>()
        val hostRepository = mockk<HostRepository>()
        val fileRepository = mockk<FileRepository>()
        val chainRepository = mockk<ChainRepository>()
        val cardanoRepository = mockk<CardanoRepository>()
        val walletUtils = mockk<WalletUtils>()
        val websocket = mockk<SimpMessagingTemplate>()
        val saved = CopyOnWriteArrayList<Block>()
        val chainQueries = CopyOnWriteArrayList<Long>()
        val completion = CompletableFuture<Unit>()
        val host = Host(
            id = 1, type = "local", cardanoCliPath = "cardano-cli", cardanoNodePath = "cardano-node",
            hostname = "synthetic-host", sshUser = "unused", nodeHomePath = "/unused", jcliPath = null,
        )
        val node = Node(
            id = 1, hostId = 1, color = "#000000", type = "core", processorThreads = 1,
            name = "synthetic", listen = "127.0.0.1", port = 3001, promPort = 12798,
            genesisByronFileId = 1, genesisShelleyFileId = 2, genesisAlonzoFileId = 3,
            genesisConwayFileId = 4, configFileId = 5, poolId = POOL_ID, isDefault = true, vrfSKeyId = 6,
        )
        val byron = File(id = 1, name = "byron", content =
            """{"startTime":0,"protocolConsts":{"k":1},"blockVersionData":{"slotDuration":1000}}""")
        val shelley = File(id = 2, name = "shelley", content =
            """{"activeSlotsCoeff":$f,"networkId":"Testnet","networkMagic":2,"slotLength":1,"epochLength":3600,"slotsPerKESPeriod":3600,"systemStart":"1970-01-01T00:00:00Z","maxKESEvolutions":62}""")
        val syntheticKey = schedule(FIRST_SLOT)["vrfSkeyHex"].asText()
        val keyFile = File(id = 6, name = "public-synthetic-vrf", content =
            """{"type":"VrfSigningKey_PraosVRF","description":"Public all-zero-seed test key","cborHex":"5840$syntheticKey"}""")
        val stakeJson = """{"pools":{"$POOL_ID":{"stakeSet":$stakeSet,"stakeMark":$stakeMark,"stakeGo":0}},"total":{"stakeSet":3,"stakeMark":3,"stakeGo":3}}"""
        val protocolJson = """{"stakePoolDeposit":0,"protocolVersion":{"major":9,"minor":0},"decentralization":${d ?: "null"},"maxTxSize":1,"minPoolCost":0,"txFeePerByte":0,"maxBlockBodySize":1,"txFeeFixed":0,"poolRetireMaxEpoch":1,"maxBlockHeaderSize":1,"stakeAddressDeposit":0,"stakePoolTargetNum":1,"monetaryExpansion":0,"treasuryCut":0,"poolPledgeInfluence":0}"""

        every { nodeRepository.findDefault() } returns node
        every { nodeRepository.findAll() } returns listOf(node)
        every { hostRepository.findById(1) } returns Optional.of(host)
        every { fileRepository.findById(1) } returns Optional.of(byron)
        every { fileRepository.findById(2) } returns Optional.of(shelley)
        every { fileRepository.findById(6) } returns Optional.of(keyFile)
        every { walletUtils.isValidSpendingPassword("synthetic-password") } returns true
        every { walletUtils.getSKeyContent(keyFile, "synthetic-password") } returns keyFile.content
        every { cardanoRepository.getEra(any(), any(), any(), any()) } returns era.lowercase()
        every { blockRepository.findByPoolAndSlot("synthetic", any()) } answers {
            existing?.takeIf { it.slot == secondArg<Long>() }
        }
        every { blockRepository.findBySlot(any()) } returns emptyList()
        every { blockRepository.hint(Block::class).save(any<Block>()) } answers {
            firstArg<Block>().also { saved.add(it) }
        }
        every { blockRepository.findLatestBlocks(any()) } answers { saved.toList() }
        every { chainRepository.findFirstBeforeSlot(any(), any()) } answers {
            val slot = firstArg<Long>()
            chainQueries.add(slot)
            listOf(ChainBlock(
                blockNumber = slot - 1, slotNumber = slot - 1, hash = ZERO_HASH,
                prevHash = ZERO_HASH, etaV = ZERO_HASH, poolId = POOL_ID, leaderVrf = "",
            ))
        }
        every { websocket.convertAndSend("/topic/messages", any<Any>()) } answers {
            when (val response = secondArg<Any>()) {
                is SocketResponse.Error -> completion.completeExceptionally(response.exception)
                is SocketResponse.Success<*> ->
                    if (response.type == "leaderlogs" && response.data == "Leader Logs calculation complete!") {
                        completion.complete(Unit)
                    }
            }
            Unit
        }

        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().command(any<String>()) } answers {
                when {
                    "query tip" in firstArg<String>() -> """{"era":"$era","slot":${FIRST_SLOT + 100}}"""
                    "query stake-snapshot" in firstArg<String>() -> stakeJson
                    "query protocol-parameters" in firstArg<String>() -> protocolJson
                    else -> error("Unexpected external command: ${firstArg<String>()}")
                }
            }
            val target = BlockController(
                buildProperties = mockk(relaxed = true), blockRepository = blockRepository,
                nodeRepository = nodeRepository, hostRepository = hostRepository, fileRepository = fileRepository,
                blockUtils = BlockUtils(AtomicReference(), "/usr/local/lib/libsodium.so"),
                walletUtils = walletUtils, webSocketTemplate = websocket,
                byronGenesisAdapter = config.getByronGenesisAdapter(moshi),
                shelleyGenesisAdapter = config.getShelleyGenesisAdapter(moshi),
                protocolParamsAdapter = config.getProtocolParametersAdapter(moshi),
                queryTipAdapter = config.getQueryTipAdapter(moshi), keyAdapter = config.getKeyAdapter(moshi),
                stakeSnapshotAdapter = config.getStakeSnapshotAdapter(moshi), chainRepository = chainRepository,
                mp = false, pastEpochsToShow = 2, nodeController = mockk(relaxed = true),
                cardanoRepository = cardanoRepository,
            )
            target.calculateLeaderLogs(LeaderLogsRequest("synthetic-password", requestType))
            // The final websocket event follows deferreds.awaitAll(), not merely launch/start.
            completion.get(60, TimeUnit.SECONDS)
            return Result(saved.toList(), chainQueries.toList())
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    private fun existingBlock(slot: Long) = Block(
        id = 1, at = "existing", pool = "synthetic", host = "synthetic-host", slot = slot,
        epoch = slot / EPOCH_LENGTH, slotInEpoch = slot % EPOCH_LENGTH, hash = "", status = "pending",
    )

    companion object {
        private const val FIRST_SLOT = 10_000_800L
        private const val EPOCH_LENGTH = 3600L
        private const val POOL_ID = "public-synthetic-pool"
        private val ZERO_HASH = "00".repeat(32)
    }
}
