package com.swiftmako.jormanager.monitors

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.entities.Block
import com.swiftmako.jormanager.entities.ChainBlock
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.repositories.BlockRepository
import com.swiftmako.jormanager.repositories.ChainRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.messaging.simp.SimpMessagingTemplate

class BlockMonitorTest {
    @Test
    fun startSeedsPersistedNonDeletedNodesOntoSharedChannel() =
        runBlocking {
            val firstNode = node(id = 1L, name = "core-a")
            val secondNode = node(id = 2L, name = "relay-a", type = "relay")
            val deletedNode = node(id = 3L, name = "deleted-a", isDeleted = true)
            val nodesChannel = MutableSharedFlow<Node>(replay = 2, extraBufferCapacity = 8)

            val monitor =
                createMonitor(
                    nodes = listOf(firstNode, secondNode, deletedNode),
                    nodesChannel = nodesChannel,
                )

            monitor.start()
            waitUntil { nodesChannel.replayCache.size == 2 }
            monitor.stopAndWait()

            assertThat(nodesChannel.replayCache).containsExactly(firstNode, secondNode).inOrder()
        }

    @Test
    fun validatesForgedBlockWhenChainHashMatches() =
        runBlocking {
            val pendingBlock = candidateBlock(hash = "abcd", status = "completed")
            val savedBlock = AtomicReference<Block?>(null)
            val chainBlock = chainBlock(hash = "abcd1234", poolId = "pool1xyz")

            val monitor =
                createMonitor(
                    nodes = listOf(node(poolId = "pool1xyz")),
                    syncedTip = pendingBlock.slot + 200,
                    unvalidatedBlocks = listOf(pendingBlock),
                    chainBySlot = mapOf(pendingBlock.slot to chainBlock),
                    poolNameByPoolId = mapOf("pool1xyz" to "POOL"),
                    saveAnswer = { savedBlock.set(it) },
                )

            monitor.start()
            waitUntil { savedBlock.get()?.status == "forged" }
            monitor.stopAndWait()

            assertThat(savedBlock.get()).isEqualTo(
                pendingBlock.copy(hash = chainBlock.hash, pool = "POOL", status = "forged")
            )
        }

    @Test
    fun validatesMissedBlockWhenHashIsEmpty() =
        runBlocking {
            val pendingBlock = candidateBlock(hash = "", status = "completed")
            val savedBlock = AtomicReference<Block?>(null)

            val monitor =
                createMonitor(
                    syncedTip = pendingBlock.slot + 200,
                    unvalidatedBlocks = listOf(pendingBlock),
                    saveAnswer = { savedBlock.set(it) },
                )

            monitor.start()
            waitUntil { savedBlock.get()?.status == "missed" }
            monitor.stopAndWait()

            assertThat(savedBlock.get()).isEqualTo(pendingBlock.copy(pool = "---", status = "missed"))
        }

    @Test
    fun validatesOrphanedBlockWhenChainHashDiffers() =
        runBlocking {
            val pendingBlock = candidateBlock(hash = "abcd", status = "completed")
            val savedBlock = AtomicReference<Block?>(null)

            val monitor =
                createMonitor(
                    nodes = listOf(node(poolId = "pool1other")),
                    syncedTip = pendingBlock.slot + 200,
                    unvalidatedBlocks = listOf(pendingBlock),
                    chainBySlot = mapOf(pendingBlock.slot to chainBlock(hash = "ffff9999", poolId = "pool1xyz")),
                    poolNameByPoolId = mapOf("pool1xyz" to "POOL"),
                    saveAnswer = { savedBlock.set(it) },
                )

            monitor.start()
            waitUntil { savedBlock.get()?.status == "orphaned" }
            monitor.stopAndWait()

            assertThat(savedBlock.get()).isEqualTo(pendingBlock.copy(pool = "POOL", status = "orphaned"))
        }

    private fun createMonitor(
        nodes: List<Node> = emptyList(),
        nodesChannel: MutableSharedFlow<Node> = MutableSharedFlow(extraBufferCapacity = 8),
        syncedTip: Long = 0L,
        unvalidatedBlocks: List<Block> = emptyList(),
        chainBySlot: Map<Long, ChainBlock?> = emptyMap(),
        poolNameByPoolId: Map<String, String?> = emptyMap(),
        saveAnswer: (Block) -> Unit = {},
    ): BlockMonitor {
        val blockRepository = mockk<BlockRepository>()
        val chainRepository = mockk<ChainRepository>()
        val nodeRepository = mockk<NodeRepository>()
        val webSocketTemplate = mockk<SimpMessagingTemplate>(relaxed = true)

        every { nodeRepository.findAll() } returns nodes
        every { nodeRepository.findPoolIds() } returns nodes.mapNotNull(Node::poolId)
        every { nodeRepository.findByPoolId(any()) } answers { poolNameByPoolId[args[0] as String] }
        every { chainRepository.findSyncedTip() } returns syncedTip
        every { blockRepository.findUnvalidatedBlocksOlderThan(any()) } returns unvalidatedBlocks
        every { chainRepository.findBySlot(any()) } answers { chainBySlot[args[0] as Long] }
        every { blockRepository.save(any()) } answers {
            (args[0] as Block).also(saveAnswer)
        }

        return BlockMonitor(
            blockRepository = blockRepository,
            chainRepository = chainRepository,
            nodeRepository = nodeRepository,
            webSocketTemplate = webSocketTemplate,
            nodesChannel = nodesChannel,
        )
    }

    private fun candidateBlock(
        slot: Long = 500L,
        hash: String = "abcd",
        status: String = "completed",
    ) =
        Block(
            id = 1L,
            at = "2026-05-14T00:00:00Z",
            pool = "---",
            host = "managed-host.example",
            slot = slot,
            epoch = 123L,
            slotInEpoch = 45L,
            hash = hash,
            status = status,
        )

    private fun chainBlock(
        slot: Long = 500L,
        hash: String = "abcd1234",
        poolId: String = "pool1xyz",
    ) =
        ChainBlock(
            id = 1L,
            blockNumber = 99L,
            slotNumber = slot,
            hash = hash,
            prevHash = "prev",
            etaV = "eta",
            poolId = poolId,
            leaderVrf = "vrf",
        )

    private fun node(
        id: Long = 1L,
        name: String = "core-a",
        type: String = "core",
        poolId: String? = null,
        isDeleted: Boolean = false,
    ) =
        Node(
            id = id,
            hostId = 1L,
            color = "#123456",
            type = type,
            processorThreads = 1,
            name = name,
            listen = "0.0.0.0",
            port = 3001,
            promPort = 12789,
            tracingHost = "0.0.0.0",
            genesisByronFileId = 1L,
            genesisShelleyFileId = 2L,
            genesisAlonzoFileId = 3L,
            genesisConwayFileId = 4L,
            configFileId = 5L,
            poolId = poolId,
            isDefault = false,
            isDeleted = isDeleted,
            tracingPort = 12790,
        )

    private fun waitUntil(
        timeoutMillis: Long = 2_000L,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) {
                return
            }
            Thread.sleep(10)
        }

        check(condition()) { "Timed out waiting for test condition" }
    }

    private suspend fun BlockMonitor.stopAndWait() {
        val stopped = AtomicReference(false)
        stop { stopped.set(true) }
        waitUntil(timeoutMillis = 5_000L) { stopped.get() }
    }
}
