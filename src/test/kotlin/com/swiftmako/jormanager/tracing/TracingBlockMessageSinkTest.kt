package com.swiftmako.jormanager.tracing

import com.google.common.truth.Truth.assertThat
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborTextString
import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.controllers.utils.BlockUtils
import com.swiftmako.jormanager.entities.Block
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.GenesisByron
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.repositories.BlockRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixtures
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.messaging.simp.SimpMessagingTemplate

class TracingBlockMessageSinkTest {
    private val moshi = Moshi.Builder().build()
    private val byronGenesisAdapter = moshi.adapter(GenesisByron::class.java)
    private val shelleyGenesisAdapter = moshi.adapter(GenesisShelley::class.java)

    @Test
    fun managerDeliveredTraceObjectsReplyPersistsCandidateBlock() =
        runBlocking {
            val node = coreNode()
            val host = host()
            val blockRepository = mockk<BlockRepository>()
            val fileRepository = mockk<FileRepository>()
            val nodeRepository = mockk<NodeRepository>()
            val hostRepository = mockk<HostRepository>()
            val blockUtils = mockk<BlockUtils>()
            val webSocketTemplate = mockk<SimpMessagingTemplate>(relaxed = true)
            val savedBlock = AtomicReference<Block?>(null)

            every { nodeRepository.findAll() } returns listOf(node)
            every { nodeRepository.findById(node.id!!) } returns Optional.of(node)
            every { hostRepository.findById(node.hostId) } returns Optional.of(host)
            every { fileRepository.findById(node.genesisByronFileId) } returns Optional.of(byronGenesisFile())
            every { fileRepository.findById(node.genesisShelleyFileId) } returns Optional.of(shelleyGenesisFile())
            every { blockUtils.getEpochAndSlot(any(), any(), 7_403_221L) } returns Pair(490L, 321L)
            every { blockRepository.findBySlot(7_403_221L) } returns emptyList()
            every { blockRepository.save(any()) } answers {
                firstArg<Block>().also { savedBlock.set(it) }
            }

            val sink =
                TracingBlockMessageSink(
                    nodeRepository = nodeRepository,
                    hostRepository = hostRepository,
                    tracingBlockPersistenceService =
                        TracingBlockPersistenceService(
                            blockRepository = blockRepository,
                            fileRepository = fileRepository,
                            webSocketTemplate = webSocketTemplate,
                            blockUtils = blockUtils,
                            byronGenesisAdapter = byronGenesisAdapter,
                            shelleyGenesisAdapter = shelleyGenesisAdapter,
                        ),
                )
            val manager =
                TracingConnectionManager(
                    nodeRepository = nodeRepository,
                    hostRepository = hostRepository,
                    nodesChannel = MutableSharedFlow(extraBufferCapacity = 8),
                    sessionClientFactory =
                        TraceForwardSessionClientFactory {
                            object : TraceForwardSessionClient {
                                override suspend fun runSession(
                                    hostname: String,
                                    port: Int,
                                    onMessage: suspend (TraceForwardMessage) -> Unit,
                                ) {
                                    onMessage(adoptedBlockReply())
                                    awaitCancellation()
                                }

                                override fun close() {
                                }
                            }
                        },
                    messageSink = sink,
                    reconnectDelayMillis = 50L,
                )

            manager.start()
            waitUntil { savedBlock.get() != null }
            manager.stopAndWait()

            assertThat(savedBlock.get()).isEqualTo(
                Block(
                    at = "2026-05-12T00:00:00Z",
                    pool = "---",
                    host = host.hostname,
                    slot = 7_403_221L,
                    epoch = 490L,
                    slotInEpoch = 321L,
                    hash = "6dc4f778bf6ff15f8f3c7c3d98e6c6c8321df6e3e97e2cb7f1f1d6ca0b5c4abc",
                    status = "completed",
                )
            )
            verify { webSocketTemplate.convertAndSend("/topic/messages", any<SocketResponse.Success<Block>>()) }
        }

    @Test
    fun persistsSentinelEpochWhenBlockUtilsCannotResolveEpochYet() =
        runBlocking {
        val blockRepository = mockk<BlockRepository>()
        val fileRepository = mockk<FileRepository>()
        val blockUtils = mockk<BlockUtils>()
        val webSocketTemplate = mockk<SimpMessagingTemplate>(relaxed = true)
        val service =
            TracingBlockPersistenceService(
                blockRepository = blockRepository,
                fileRepository = fileRepository,
                webSocketTemplate = webSocketTemplate,
                blockUtils = blockUtils,
                byronGenesisAdapter = byronGenesisAdapter,
                shelleyGenesisAdapter = shelleyGenesisAdapter,
            )
        val node = coreNode()
        val host = host()

        every { fileRepository.findById(node.genesisByronFileId) } returns Optional.of(byronGenesisFile())
        every { fileRepository.findById(node.genesisShelleyFileId) } returns Optional.of(shelleyGenesisFile())
        every { blockUtils.getEpochAndSlot(any(), any(), 7_403_221L) } returns Pair(-1L, -1L)
        every { blockRepository.findBySlot(7_403_221L) } returns emptyList()
        every { blockRepository.save(any()) } answers { firstArg() }

        val savedBlock = service.persistTracingCandidateBlock(node, host, adoptedBlockEvent())

        assertThat(savedBlock?.epoch).isEqualTo(-1L)
        assertThat(savedBlock?.slotInEpoch).isEqualTo(-1L)
        }

    @Test
    fun duplicateTracingEventsDoNotCreateDuplicateBlocks() =
        runBlocking {
        val blockRepository = mockk<BlockRepository>()
        val fileRepository = mockk<FileRepository>()
        val blockUtils = mockk<BlockUtils>()
        val webSocketTemplate = mockk<SimpMessagingTemplate>(relaxed = true)
        val service =
            TracingBlockPersistenceService(
                blockRepository = blockRepository,
                fileRepository = fileRepository,
                webSocketTemplate = webSocketTemplate,
                blockUtils = blockUtils,
                byronGenesisAdapter = byronGenesisAdapter,
                shelleyGenesisAdapter = shelleyGenesisAdapter,
            )
        val node = coreNode()
        val host = host()

        every { fileRepository.findById(node.genesisByronFileId) } returns Optional.of(byronGenesisFile())
        every { fileRepository.findById(node.genesisShelleyFileId) } returns Optional.of(shelleyGenesisFile())
        every { blockUtils.getEpochAndSlot(any(), any(), 7_403_221L) } returns Pair(490L, 321L)
        every { blockRepository.findBySlot(7_403_221L) } returns listOf(existingBlock())

        val savedBlock = service.persistTracingCandidateBlock(node, host, adoptedBlockEvent())

        assertThat(savedBlock).isNull()
        verify(exactly = 0) { blockRepository.save(any()) }
        }

    @Test
    fun sharedPersistenceGatePreventsInterleavedSameSlotDuplicateSaves() =
        runBlocking {
            val blockRepository = mockk<BlockRepository>()
            val fileRepository = mockk<FileRepository>()
            val blockUtils = mockk<BlockUtils>()
            val webSocketTemplate = mockk<SimpMessagingTemplate>(relaxed = true)
            val service =
                TracingBlockPersistenceService(
                    blockRepository = blockRepository,
                    fileRepository = fileRepository,
                    webSocketTemplate = webSocketTemplate,
                    blockUtils = blockUtils,
                    byronGenesisAdapter = byronGenesisAdapter,
                    shelleyGenesisAdapter = shelleyGenesisAdapter,
                )

            val savedBlocks = mutableListOf<Block>()
            every { blockRepository.findBySlot(7_403_221L) } answers {
                if (savedBlocks.isEmpty()) {
                    emptyList()
                } else {
                    savedBlocks.toList()
                }
            }
            every { blockRepository.save(any()) } answers {
                firstArg<Block>().also(savedBlocks::add)
            }

            val candidate =
                Block(
                    at = "2026-05-12T00:00:00Z",
                    pool = "---",
                    host = "managed-host.example",
                    slot = 7_403_221L,
                    epoch = 490L,
                    slotInEpoch = 321L,
                    hash = "6dc4f778bf6ff15f8f3c7c3d98e6c6c8321df6e3e97e2cb7f1f1d6ca0b5c4abc",
                    status = "completed",
                )

            val first = launch { service.persistCandidateBlock(candidate) }
            val second = launch { service.persistCandidateBlock(candidate.copy(host = "legacy-host.example")) }
            first.join()
            second.join()

            assertThat(savedBlocks).hasSize(1)
            assertThat(savedBlocks.single().host).isEqualTo("managed-host.example")
            verify(exactly = 1) { blockRepository.save(any()) }
        }

    @Test
    fun missingNodeIsIgnoredSafely() =
        runBlocking {
            val nodeRepository = mockk<NodeRepository>()
            val hostRepository = mockk<HostRepository>()
            val blockRepository = mockk<BlockRepository>()
            val fileRepository = mockk<FileRepository>()
            val blockUtils = mockk<BlockUtils>()
            val sink =
                TracingBlockMessageSink(
                    nodeRepository = nodeRepository,
                    hostRepository = hostRepository,
                    tracingBlockPersistenceService =
                        TracingBlockPersistenceService(
                            blockRepository = blockRepository,
                            fileRepository = fileRepository,
                            webSocketTemplate = mockk(relaxed = true),
                            blockUtils = blockUtils,
                            byronGenesisAdapter = byronGenesisAdapter,
                            shelleyGenesisAdapter = shelleyGenesisAdapter,
                        ),
                )

            every { nodeRepository.findById(1L) } returns Optional.empty()

            sink.onMessage(1L, adoptedBlockReply())

            verify(exactly = 0) { blockRepository.save(any()) }
        }

    private fun adoptedBlockReply(): TraceForwardMessage.TraceObjectsReply =
        TraceForwardMessage.TraceObjectsReply(
            CborArray.create().apply {
                add(CborTextString.create(TraceForwardFixtures.adoptedBlockTraceObject().traceObjectJson))
            }
        )

    private fun adoptedBlockEvent() =
        ForwardedAdoptedBlockEvent(
            slot = 7_403_221L,
            blockHash = "6dc4f778bf6ff15f8f3c7c3d98e6c6c8321df6e3e97e2cb7f1f1d6ca0b5c4abc",
            timestamp = "2026-05-12T00:00:00Z",
            hostname = "trace-wrapper-host",
        )

    private fun existingBlock() =
        Block(
            id = 99L,
            at = "2026-05-12T00:00:00Z",
            pool = "pool1",
            host = "existing-host",
            slot = 7_403_221L,
            epoch = 490L,
            slotInEpoch = 321L,
            hash = "existing-hash",
            status = "completed",
        )

    private fun byronGenesisFile() =
        File(
            id = 11L,
            name = "byron.json",
            content = """{"startTime":1506203091,"protocolConsts":{"k":2160},"blockVersionData":{"slotDuration":20000}}""",
        )

    private fun shelleyGenesisFile() =
        File(
            id = 12L,
            name = "shelley.json",
            content = """{"activeSlotsCoeff":0.05,"networkId":"mainnet","slotLength":1,"epochLength":432000,"slotsPerKESPeriod":129600,"systemStart":"2017-09-23T21:44:51Z","maxKESEvolutions":62}""",
        )

    private fun coreNode() =
        Node(
            id = 1L,
            hostId = 1L,
            color = "#123456",
            type = "core",
            processorThreads = 1,
            name = "core-a",
            listen = "0.0.0.0",
            port = 3001,
            ekgPort = 12788,
            promPort = 12789,
            genesisByronFileId = 11L,
            genesisShelleyFileId = 12L,
            genesisAlonzoFileId = 13L,
            genesisConwayFileId = 14L,
            configFileId = 15L,
            isDefault = false,
            tracingPort = 12790,
        )

    private fun host() =
        Host(
            id = 1L,
            type = "remote",
            cardanoCliPath = "/usr/bin/cardano-cli",
            cardanoNodePath = "/usr/bin/cardano-node",
            hostname = "managed-host.example",
            sshUser = "westbam",
            sshPemPath = "/tmp/key.pem",
            nodeHomePath = "/srv/cardano",
            jcliPath = null,
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
}
