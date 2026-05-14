package com.swiftmako.jormanager.monitors

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.model.NodeStats
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.tracing.DataPointSessionClient
import com.swiftmako.jormanager.tracing.DataPointSessionClientFactory
import com.swiftmako.jormanager.tracing.TraceForwardMessage
import com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixtures
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.messaging.simp.SimpMessagingTemplate

class NodeMonitorTest {
    private val moshi = Moshi.Builder().build()
    private val shelleyGenesisAdapter = moshi.adapter(GenesisShelley::class.java)

    @Test
    fun startSelfSeedsEligibleCoreNodesAndPublishesGroupedNodeStats() =
        runBlocking {
            val coreNode = node(isDefault = true)
            val latestNodeStats = AtomicReference<NodeStats>(null)
            val sentMessages = CopyOnWriteArrayList<Pair<String, SocketResponse.Success<*>>>()
            val sessionAttempts = AtomicInteger(0)
            val monitor =
                createMonitor(
                    nodes = listOf(coreNode),
                    latestNodeStats = latestNodeStats,
                    sessionClientFactory =
                        sessionClientFactory {
                            sessionAttempts.incrementAndGet()
                            emitDataPointReply(it)
                        },
                    onSend = { destination, payload ->
                        sentMessages += destination to payload
                    },
                )

            monitor.start()
            waitUntil {
                latestNodeStats.get()?.blockHeight == 7_403_221L && sentMessages.isNotEmpty()
            }
            monitor.stopAndWait()

            assertThat(sessionAttempts.get()).isAtLeast(1)
            assertThat(latestNodeStats.get()?.slot).isEqualTo(7_403_221L)
            val (destination, payload) = sentMessages.last()
            assertThat(destination).isEqualTo("/topic/messages")
            assertThat(payload.type).isEqualTo("nodestats")
            @Suppress("UNCHECKED_CAST")
            val nodeStatsEvents = payload.data as List<NodeStats>
            assertThat(nodeStatsEvents).isNotEmpty()
            assertThat(nodeStatsEvents.last()).isEqualTo(
                NodeStats(
                    isDefault = true,
                    timestamp = nodeStatsEvents.last().timestamp,
                    nodeName = coreNode.name,
                    color = coreNode.color,
                    peers = 12,
                    incomingPeers = 7,
                    blockHeight = 7_403_221L,
                    remainingKESPeriods = 36,
                    epoch = 490L,
                    slot = 7_403_221L,
                    slotInEpoch = 321L,
                    txsProcessed = 123_456L,
                    epochLength = 432_000L,
                )
            )
        }

    @Test
    fun nodeStateFailurePublishesNullValuedFallback() =
        runBlocking {
            val coreNode = node(isDefault = true)
            val sentMessages = CopyOnWriteArrayList<SocketResponse.Success<*>>()
            val monitor =
                createMonitor(
                    nodes = listOf(coreNode),
                    sessionClientFactory =
                        sessionClientFactory {
                            throw IOException("synthetic failure")
                        },
                    onSend = { _, payload -> sentMessages += payload },
                )

            monitor.start()
            waitUntil { sentMessages.isNotEmpty() }
            monitor.stopAndWait()

            @Suppress("UNCHECKED_CAST")
            val nodeStatsEvents = sentMessages.last().data as List<NodeStats>
            assertThat(nodeStatsEvents.last()).isEqualTo(
                NodeStats(
                    isDefault = true,
                    timestamp = nodeStatsEvents.last().timestamp,
                    nodeName = coreNode.name,
                    color = coreNode.color,
                    peers = null,
                    incomingPeers = null,
                    blockHeight = null,
                    remainingKESPeriods = null,
                    epoch = null,
                    slot = null,
                    slotInEpoch = null,
                    txsProcessed = null,
                    epochLength = 432_000L,
                )
            )
        }

    @Test
    fun defaultRelayDoesNotRefreshLatestNodeStats() =
        runBlocking {
            val defaultRelay = node(id = 1L, name = "relay-a", type = "relay", isDefault = true, tracingPort = null)
            val coreNode = node(id = 2L, name = "core-a", isDefault = false)
            val latestNodeStats = AtomicReference(
                NodeStats(
                    isDefault = true,
                    timestamp = 1L,
                    nodeName = "old-default",
                    color = "#000000",
                    peers = 1,
                    incomingPeers = 1,
                    blockHeight = 1L,
                    remainingKESPeriods = 1,
                    epoch = 1L,
                    slot = 1L,
                    slotInEpoch = 1L,
                    txsProcessed = 1L,
                    epochLength = 432_000L,
                )
            )
            val sessionAttempts = AtomicInteger(0)
            val monitor =
                createMonitor(
                    nodes = listOf(defaultRelay, coreNode),
                    latestNodeStats = latestNodeStats,
                    sessionClientFactory =
                        sessionClientFactory {
                            sessionAttempts.incrementAndGet()
                            emitDataPointReply(it)
                        },
                )

            monitor.start()
            waitUntil { sessionAttempts.get() >= 1 }
            monitor.stopAndWait()

            assertThat(latestNodeStats.get()?.nodeName).isEqualTo("old-default")
        }

    @Test
    fun relayAndPoolNodesDoNotStartDirectMonitoringSessions() =
        runBlocking {
            val relayNode = node(id = 1L, name = "relay-a", type = "relay", tracingPort = null)
            val poolNode = node(id = 2L, name = "pool-a", type = "pool", tracingPort = null)
            val sessionAttempts = AtomicInteger(0)
            val sentMessages = CopyOnWriteArrayList<SocketResponse.Success<*>>()
            val monitor =
                createMonitor(
                    nodes = listOf(relayNode, poolNode),
                    sessionClientFactory =
                        sessionClientFactory {
                            sessionAttempts.incrementAndGet()
                            emitDataPointReply(it)
                        },
                    onSend = { _, payload -> sentMessages += payload },
                )

            monitor.start()
            Thread.sleep(200)
            monitor.stopAndWait()

            assertThat(sessionAttempts.get()).isEqualTo(0)
            assertThat(sentMessages).isEmpty()
        }

    private fun createMonitor(
        nodes: List<Node>,
        latestNodeStats: AtomicReference<NodeStats> = AtomicReference(null),
        sessionClientFactory: DataPointSessionClientFactory,
        onSend: (String, SocketResponse.Success<*>) -> Unit = { _, _ -> },
    ): NodeMonitor {
        val hostRepository = mockk<HostRepository>()
        val nodeRepository = mockk<NodeRepository>()
        val fileRepository = mockk<FileRepository>()
        val webSocketTemplate = mockk<SimpMessagingTemplate>(relaxed = true)
        val nodesById = nodes.associateBy { it.id!! }
        val hostsById = nodes.associate { it.hostId to host(it.hostId) }
        val genesisFile = genesisShelleyFile()
        val defaultNode = nodes.firstOrNull(Node::isDefault)

        every { nodeRepository.findAll() } returns nodes
        every { nodeRepository.findDefault() } returns defaultNode
        nodesById.forEach { (id, node) ->
            every { nodeRepository.findById(id) } returns Optional.of(node)
        }
        hostsById.forEach { (id, host) ->
            every { hostRepository.findById(id) } returns Optional.of(host)
        }
        every { fileRepository.findById(any()) } returns Optional.of(genesisFile)
        every { webSocketTemplate.convertAndSend(any<String>(), any<SocketResponse.Success<*>>()) } answers {
            @Suppress("UNCHECKED_CAST")
            onSend(firstArg(), secondArg() as SocketResponse.Success<*>)
        }

        return NodeMonitor(
            hostRepository = hostRepository,
            nodeRepository = nodeRepository,
            fileRepository = fileRepository,
            webSocketTemplate = webSocketTemplate,
            shelleyGenesisAdapter = shelleyGenesisAdapter,
            moshi = moshi,
            nodesChannel = MutableSharedFlow(extraBufferCapacity = 8),
            latestNodeStats = latestNodeStats,
            dataPointSessionClientFactory = sessionClientFactory,
            startupDelayMillis = 0L,
            sampleIntervalMillis = 50L,
            publishDelayMillis = 10L,
        )
    }

    private fun sessionClientFactory(
        runSession: suspend (suspend (TraceForwardMessage) -> Unit) -> Unit,
    ): DataPointSessionClientFactory =
        DataPointSessionClientFactory {
            object : DataPointSessionClient {
                override suspend fun runSession(
                    hostname: String,
                    port: Int,
                    onMessage: suspend (TraceForwardMessage) -> Unit,
                ) {
                    runSession(onMessage)
                }

                override fun close() {
                }
            }
        }

    private suspend fun emitDataPointReply(onMessage: suspend (TraceForwardMessage) -> Unit) {
        onMessage(TraceForwardFixtures.pinnedNodeStateReply().toDataPointsReply())
    }

    private fun ByteArray.toDataPointsReply(): TraceForwardMessage.DataPointsReply =
        ByteArrayInputStream(this).use { input ->
            val payload = com.google.iot.cbor.CborReader.createFromInputStream(input).readDataItem() as com.google.iot.cbor.CborArray
            TraceForwardMessage.DataPointsReply(payload.elementAt(1) as com.google.iot.cbor.CborArray)
        }

    private fun genesisShelleyFile() =
        File(
            id = 2L,
            name = "genesis-shelley.json",
            content =
                """
                {
                  "activeSlotsCoeff": 0.05,
                  "networkId": "Mainnet",
                  "networkMagic": 764824073,
                  "slotLength": 1,
                  "epochLength": 432000,
                  "slotsPerKESPeriod": 129600,
                  "systemStart": "2017-09-23T21:44:51Z",
                  "maxKESEvolutions": 62
                }
                """.trimIndent(),
        )

    private fun host(hostId: Long) =
        Host(
            id = hostId,
            type = "remote",
            cardanoCliPath = "/usr/bin/cardano-cli",
            cardanoNodePath = "/usr/bin/cardano-node",
            hostname = "127.0.0.1",
            sshUser = "westbam",
            sshPemPath = "/tmp/key.pem",
            nodeHomePath = "/srv/cardano",
            jcliPath = null,
        )

    private fun node(
        id: Long = 1L,
        name: String = "core-a",
        type: String = "core",
        isDefault: Boolean = false,
        tracingPort: Int? = 12790,
    ) =
        Node(
            id = id,
            hostId = id,
            color = "#123456",
            type = type,
            processorThreads = 1,
            name = name,
            listen = "0.0.0.0",
            port = 3001,
            ekgPort = 12788,
            promPort = 12789,
            genesisByronFileId = 1L,
            genesisShelleyFileId = 2L,
            genesisAlonzoFileId = 3L,
            genesisConwayFileId = 4L,
            configFileId = 5L,
            poolPledge = java.math.BigInteger.ONE,
            poolCost = java.math.BigInteger.ONE,
            poolMargin = "0.01",
            isDefault = isDefault,
            tracingPort = tracingPort,
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

    private suspend fun NodeMonitor.stopAndWait() {
        val stopped = AtomicReference(false)
        stop { stopped.set(true) }
        waitUntil(timeoutMillis = 5_000L) { stopped.get() }
    }
}
