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
import com.swiftmako.jormanager.repositories.ChainRepository
import com.swiftmako.jormanager.tracing.TraceForwardMessage
import com.swiftmako.jormanager.tracing.TracingDashboardSignalService
import com.swiftmako.jormanager.tracing.TracingRawMetricSnapshot
import com.swiftmako.jormanager.tracing.TracingRawMetricValue
import com.swiftmako.jormanager.tracing.TracingMetricDecoder
import com.swiftmako.jormanager.tracing.TracingRawCaptureService
import com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixtures
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
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
            val rawCapture = rawCaptureService(coreNode)
            val monitor =
                createMonitor(
                    nodes = listOf(coreNode),
                    latestNodeStats = latestNodeStats,
                    tracingRawCaptureService = rawCapture,
                    onSend = { destination, payload ->
                        sentMessages += destination to payload
                    },
                )

            rawCapture.onMessage(coreNode.id!!, TraceForwardFixtures.pinnedNodeStateReply().toDataPointsReply())
            monitor.start()
            waitUntil {
                latestNodeStats.get()?.blockHeight == 7_403_221L && sentMessages.isNotEmpty()
            }
            monitor.stopAndWait()

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
            val rawCapture = rawCaptureService(coreNode)
            val monitor =
                createMonitor(
                    nodes = listOf(coreNode),
                    tracingRawCaptureService = rawCapture,
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
    fun staleRawSnapshotPublishesNullValuedFallback() =
        runBlocking {
            val coreNode = node(isDefault = true)
            val sentMessages = CopyOnWriteArrayList<SocketResponse.Success<*>>()
            val rawCapture = rawCaptureService(coreNode)
            rawCapture.recordDataPointSnapshotForTest(
                nodeId = coreNode.id!!,
                snapshot =
                    com.swiftmako.jormanager.tracing.TracingRawDataPointSnapshot(
                        nodeId = coreNode.id,
                        capturedAt = Instant.now().minusSeconds(60),
                        dataPoints = TraceForwardFixtures.pinnedNodeStateReply().toDataPointsReply().dataPoints,
                    ),
            )
            val monitor =
                createMonitor(
                    nodes = listOf(coreNode),
                    tracingRawCaptureService = rawCapture,
                    onSend = { _, payload -> sentMessages += payload },
                )

            monitor.start()
            waitUntil { sentMessages.isNotEmpty() }
            monitor.stopAndWait()

            @Suppress("UNCHECKED_CAST")
            val nodeStatsEvents = sentMessages.last().data as List<NodeStats>
            assertThat(nodeStatsEvents.last().blockHeight).isNull()
            assertThat(nodeStatsEvents.last().slot).isNull()
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
            val rawCapture = rawCaptureService(coreNode)
            val monitor =
                createMonitor(
                    nodes = listOf(defaultRelay, coreNode),
                    latestNodeStats = latestNodeStats,
                    tracingRawCaptureService = rawCapture,
                )

            rawCapture.onMessage(coreNode.id!!, TraceForwardFixtures.pinnedNodeStateReply().toDataPointsReply())
            monitor.start()
            waitUntil { latestNodeStats.get()?.nodeName == "old-default" }
            monitor.stopAndWait()

            assertThat(latestNodeStats.get()?.nodeName).isEqualTo("old-default")
        }

    @Test
    fun tracedRelayDoesNotStartMonitoring() =
        runBlocking {
            val relayNode = node(id = 1L, name = "relay-a", type = "relay", tracingPort = 12790)
            val sentMessages = CopyOnWriteArrayList<SocketResponse.Success<*>>()
            val rawCapture = rawCaptureService(relayNode)
            val monitor =
                createMonitor(
                    nodes = listOf(relayNode),
                    tracingRawCaptureService = rawCapture,
                    onSend = { _, payload -> sentMessages += payload },
                )

            rawCapture.onMessage(relayNode.id!!, TraceForwardFixtures.pinnedNodeStateReply().toDataPointsReply())
            monitor.start()
            Thread.sleep(150)
            monitor.stopAndWait()

            assertThat(sentMessages).isEmpty()
        }

    @Test
    fun traceObjectsCanSupplyConnectionCountersWhenDatapointsDoNot() =
        runBlocking {
            val coreNode = node(isDefault = true)
            val latestNodeStats = AtomicReference<NodeStats>(null)
            val sentMessages = CopyOnWriteArrayList<Pair<String, SocketResponse.Success<*>>>()
            val rawCapture = rawCaptureService(coreNode)
            val monitor =
                createMonitor(
                    nodes = listOf(coreNode),
                    latestNodeStats = latestNodeStats,
                    tracingRawCaptureService = rawCapture,
                    onSend = { destination, payload ->
                        sentMessages += destination to payload
                    },
                )

            rawCapture.onMessage(
                coreNode.id!!,
                TraceForwardFixtures
                    .msgTraceObjectsReply(
                        TraceForwardFixtures.connectionManagerCountersTraceObject(outbound = 2, inbound = 3),
                        TraceForwardFixtures.nodeStateTraceObject(),
                    ).toTraceObjectsReply(),
            )
            monitor.start()
            waitUntil {
                latestNodeStats.get()?.peers == 2 && latestNodeStats.get()?.incomingPeers == 3 && sentMessages.isNotEmpty()
            }
            monitor.stopAndWait()

            assertThat(latestNodeStats.get()?.peers).isEqualTo(2)
            assertThat(latestNodeStats.get()?.incomingPeers).isEqualTo(3)
            assertThat(latestNodeStats.get()?.blockHeight).isEqualTo(7_403_221L)
        }

    @Test
    fun freshProtocol1MetricsWinOverDatapointsForOverlappingFields() =
        runBlocking {
            val coreNode = node(isDefault = true)
            val latestNodeStats = AtomicReference<NodeStats>(null)
            val rawCapture = rawCaptureService(coreNode)
            val monitor =
                createMonitor(
                    nodes = listOf(coreNode),
                    latestNodeStats = latestNodeStats,
                    tracingRawCaptureService = rawCapture,
                )

            rawCapture.recordMetricSnapshotForTest(
                coreNode.id!!,
                metricSnapshot(
                    nodeId = coreNode.id,
                    blockNum = 8_888_888L,
                    slotNum = 8_888_889L,
                    slotInEpoch = 222L,
                    epoch = 500L,
                    remainingKesPeriods = 40L,
                    txsProcessedNum = 999_999L,
                )
            )
            rawCapture.onMessage(coreNode.id, TraceForwardFixtures.pinnedNodeStateReply().toDataPointsReply())
            monitor.start()
            waitUntil { latestNodeStats.get()?.blockHeight == 8_888_888L }
            monitor.stopAndWait()

            assertThat(latestNodeStats.get()?.blockHeight).isEqualTo(8_888_888L)
            assertThat(latestNodeStats.get()?.slot).isEqualTo(8_888_889L)
            assertThat(latestNodeStats.get()?.slotInEpoch).isEqualTo(222L)
            assertThat(latestNodeStats.get()?.epoch).isEqualTo(500L)
            assertThat(latestNodeStats.get()?.remainingKESPeriods).isEqualTo(40)
            assertThat(latestNodeStats.get()?.txsProcessed).isEqualTo(999_999L)
            assertThat(latestNodeStats.get()?.peers).isEqualTo(12)
            assertThat(latestNodeStats.get()?.incomingPeers).isEqualTo(7)
        }

    @Test
    fun staleProtocol1MetricsFallBackToDatapoints() =
        runBlocking {
            val coreNode = node(isDefault = true)
            val latestNodeStats = AtomicReference<NodeStats>(null)
            val rawCapture = rawCaptureService(coreNode)
            val monitor =
                createMonitor(
                    nodes = listOf(coreNode),
                    latestNodeStats = latestNodeStats,
                    tracingRawCaptureService = rawCapture,
                )

            rawCapture.recordMetricSnapshotForTest(
                coreNode.id!!,
                metricSnapshot(
                    nodeId = coreNode.id,
                    blockNum = 8_888_888L,
                    capturedAt = Instant.now().minusSeconds(60),
                )
            )
            rawCapture.onMessage(coreNode.id, TraceForwardFixtures.pinnedNodeStateReply().toDataPointsReply())
            monitor.start()
            waitUntil { latestNodeStats.get()?.blockHeight == 7_403_221L }
            monitor.stopAndWait()

            assertThat(latestNodeStats.get()?.blockHeight).isEqualTo(7_403_221L)
            assertThat(latestNodeStats.get()?.remainingKESPeriods).isEqualTo(36)
        }

    @Test
    fun protocol3ExtractorBackedDatapointsStillDriveFallbackStats() =
        runBlocking {
            val coreNode = node(isDefault = true)
            val latestNodeStats = AtomicReference<NodeStats>(null)
            val rawCapture = rawCaptureService(coreNode)
            val monitor =
                createMonitor(
                    nodes = listOf(coreNode),
                    latestNodeStats = latestNodeStats,
                    tracingRawCaptureService = rawCapture,
                )

            rawCapture.onMessage(coreNode.id!!, TraceForwardFixtures.pinnedNodeStateWithStartupReply().toDataPointsReply())
            monitor.start()
            waitUntil { latestNodeStats.get()?.blockHeight == 7_403_221L }
            monitor.stopAndWait()

            assertThat(latestNodeStats.get()?.blockHeight).isEqualTo(7_403_221L)
            assertThat(latestNodeStats.get()?.slot).isEqualTo(7_403_221L)
            assertThat(latestNodeStats.get()?.remainingKESPeriods).isEqualTo(36)
        }

    @Test
    fun freshProtocol2PeerCountersOverrideProtocol3PeerValuesWithoutProtocol1Metrics() =
        runBlocking {
            val coreNode = node(isDefault = true)
            val latestNodeStats = AtomicReference<NodeStats>(null)
            val rawCapture = rawCaptureService(coreNode)
            val monitor =
                createMonitor(
                    nodes = listOf(coreNode),
                    latestNodeStats = latestNodeStats,
                    tracingRawCaptureService = rawCapture,
                )

            rawCapture.onMessage(
                coreNode.id!!,
                TraceForwardFixtures
                    .msgTraceObjectsReply(
                        TraceForwardFixtures.connectionManagerCountersTraceObject(outbound = 5, inbound = 6),
                    ).toTraceObjectsReply(),
            )
            rawCapture.onMessage(coreNode.id, TraceForwardFixtures.pinnedNodeStateReply().toDataPointsReply())
            monitor.start()
            waitUntil { latestNodeStats.get()?.blockHeight == 7_403_221L }
            monitor.stopAndWait()

            assertThat(latestNodeStats.get()?.peers).isEqualTo(5)
            assertThat(latestNodeStats.get()?.incomingPeers).isEqualTo(6)
            assertThat(latestNodeStats.get()?.blockHeight).isEqualTo(7_403_221L)
        }

    @Test
    fun incompleteProtocol1MetricsFallBackToDatapoints() =
        runBlocking {
            val coreNode = node(isDefault = true)
            val latestNodeStats = AtomicReference<NodeStats>(null)
            val rawCapture = rawCaptureService(coreNode)
            val monitor =
                createMonitor(
                    nodes = listOf(coreNode),
                    latestNodeStats = latestNodeStats,
                    tracingRawCaptureService = rawCapture,
                )

            rawCapture.recordMetricSnapshotForTest(
                coreNode.id!!,
                metricSnapshot(
                    nodeId = coreNode.id,
                    metrics =
                        fullProtocol1MetricMap(
                            blockNum = 8_888_888L,
                            txsProcessedNum = null,
                        )
                )
            )
            rawCapture.onMessage(coreNode.id, TraceForwardFixtures.pinnedNodeStateReply().toDataPointsReply())
            monitor.start()
            waitUntil { latestNodeStats.get()?.blockHeight == 7_403_221L }
            monitor.stopAndWait()

            assertThat(latestNodeStats.get()?.blockHeight).isEqualTo(7_403_221L)
            assertThat(latestNodeStats.get()?.txsProcessed).isEqualTo(123_456L)
        }

    @Test
    fun freshProtocol1MetricsStillUseProtocol2PeerCountersWhenAvailable() =
        runBlocking {
            val coreNode = node(isDefault = true)
            val latestNodeStats = AtomicReference<NodeStats>(null)
            val rawCapture = rawCaptureService(coreNode)
            val monitor =
                createMonitor(
                    nodes = listOf(coreNode),
                    latestNodeStats = latestNodeStats,
                    tracingRawCaptureService = rawCapture,
                )

            rawCapture.recordMetricSnapshotForTest(
                coreNode.id!!,
                metricSnapshot(nodeId = coreNode.id)
            )
            rawCapture.onMessage(
                coreNode.id,
                TraceForwardFixtures
                    .msgTraceObjectsReply(
                        TraceForwardFixtures.connectionManagerCountersTraceObject(outbound = 5, inbound = 6),
                    ).toTraceObjectsReply(),
            )
            monitor.start()
            waitUntil { latestNodeStats.get()?.peers == 5 && latestNodeStats.get()?.incomingPeers == 6 }
            monitor.stopAndWait()

            assertThat(latestNodeStats.get()?.blockHeight).isEqualTo(7_403_221L)
            assertThat(latestNodeStats.get()?.peers).isEqualTo(5)
            assertThat(latestNodeStats.get()?.incomingPeers).isEqualTo(6)
        }

    @Test
    fun staleTraceObjectsDoNotSupplyPeerCountersWhenProtocol1MetricsAreFresh() =
        runBlocking {
            val coreNode = node(isDefault = true)
            val latestNodeStats = AtomicReference<NodeStats>(null)
            val rawCapture = rawCaptureService(coreNode)
            val monitor =
                createMonitor(
                    nodes = listOf(coreNode),
                    latestNodeStats = latestNodeStats,
                    tracingRawCaptureService = rawCapture,
                )

            rawCapture.recordMetricSnapshotForTest(coreNode.id!!, metricSnapshot(nodeId = coreNode.id))
            rawCapture.recordTraceObjectBatchForTest(
                nodeId = coreNode.id,
                batch =
                    com.swiftmako.jormanager.tracing.TracingRawTraceObjectBatch(
                        nodeId = coreNode.id,
                        capturedAt = Instant.now().minusSeconds(60),
                        traceObjects = TraceForwardFixtures.traceObjectsArray(
                            TraceForwardFixtures.connectionManagerCountersTraceObject(outbound = 5, inbound = 6),
                        ),
                    ),
            )
            monitor.start()
            waitUntil { latestNodeStats.get()?.blockHeight == 7_403_221L }
            monitor.stopAndWait()

            assertThat(latestNodeStats.get()?.peers).isEqualTo(0)
            assertThat(latestNodeStats.get()?.incomingPeers).isEqualTo(0)
        }

    @Test
    fun staleTraceObjectsDoNotSupplyChainFallbackWithoutOtherFreshSnapshots() =
        runBlocking {
            val coreNode = node(isDefault = true)
            val sentMessages = CopyOnWriteArrayList<SocketResponse.Success<*>>()
            val rawCapture = rawCaptureService(coreNode)
            val monitor =
                createMonitor(
                    nodes = listOf(coreNode),
                    tracingRawCaptureService = rawCapture,
                    onSend = { _, payload -> sentMessages += payload },
                )

            rawCapture.recordTraceObjectBatchForTest(
                nodeId = coreNode.id!!,
                batch =
                    com.swiftmako.jormanager.tracing.TracingRawTraceObjectBatch(
                        nodeId = coreNode.id,
                        capturedAt = Instant.now().minusSeconds(60),
                        traceObjects = TraceForwardFixtures.traceObjectsArray(TraceForwardFixtures.nodeStateTraceObject()),
                    ),
            )
            monitor.start()
            waitUntil { sentMessages.isNotEmpty() }
            monitor.stopAndWait()

            @Suppress("UNCHECKED_CAST")
            val nodeStatsEvents = sentMessages.last().data as List<NodeStats>
            assertThat(nodeStatsEvents.last().blockHeight).isNull()
            assertThat(nodeStatsEvents.last().slot).isNull()
        }

    @Test
    fun startBackfillsTracingSettingsForLegacyCoreNodes() =
        runBlocking {
            val legacyCoreNode =
                node(
                    id = 1L,
                    name = "core-a",
                    type = "core",
                    isDefault = true,
                    tracingPort = null,
                ).copy(tracingHost = null)
            val nodeRepository = mockk<NodeRepository>(relaxed = true)
            val monitor =
                createMonitor(
                    nodes = listOf(legacyCoreNode),
                    nodeRepositoryOverride = nodeRepository,
                    tracingRawCaptureService = rawCaptureService(legacyCoreNode),
                )

            monitor.start()
            monitor.stopAndWait()

            verify {
                nodeRepository.save(
                    withArg { savedNode ->
                        assertThat(savedNode.id).isEqualTo(legacyCoreNode.id)
                        assertThat(savedNode.tracingHost).isEqualTo("0.0.0.0")
                        assertThat(savedNode.tracingPort).isEqualTo(legacyCoreNode.promPort + 1)
                    }
                )
            }
        }

    @Test
    fun startDoesNotBackfillTracingSettingsForLegacyRelayNodes() =
        runBlocking {
            val legacyRelayNode =
                node(
                    id = 1L,
                    name = "relay-a",
                    type = "relay",
                    isDefault = true,
                    tracingPort = null,
                ).copy(tracingHost = null)
            val nodeRepository = mockk<NodeRepository>(relaxed = true)
            val monitor =
                createMonitor(
                    nodes = listOf(legacyRelayNode),
                    nodeRepositoryOverride = nodeRepository,
                    tracingRawCaptureService = rawCaptureService(legacyRelayNode),
                )

            monitor.start()
            monitor.stopAndWait()

            verify(exactly = 0) {
                nodeRepository.save(any())
            }
        }

    @Test
    fun startClearsLegacyPoolTracingSettings() =
        runBlocking {
            val legacyPool =
                node(
                    id = 2L,
                    name = "pool-a",
                    type = "pool",
                    tracingPort = 12791,
                )
            val nodeRepository = mockk<NodeRepository>(relaxed = true)
            val monitor =
                createMonitor(
                    nodes = listOf(legacyPool),
                    nodeRepositoryOverride = nodeRepository,
                    tracingRawCaptureService = rawCaptureService(legacyPool),
                )

            monitor.start()
            monitor.stopAndWait()

            verify {
                nodeRepository.save(
                    withArg { savedNode ->
                        assertThat(savedNode.id).isEqualTo(legacyPool.id)
                        assertThat(savedNode.tracingHost).isNull()
                        assertThat(savedNode.tracingPort).isNull()
                    }
                )
            }
        }

    private fun createMonitor(
        nodes: List<Node>,
        tracingRawCaptureService: TracingRawCaptureService,
        latestNodeStats: AtomicReference<NodeStats> = AtomicReference(null),
        nodeRepositoryOverride: NodeRepository? = null,
        onSend: (String, SocketResponse.Success<*>) -> Unit = { _, _ -> },
    ): NodeMonitor {
        val hostRepository = mockk<HostRepository>()
        val nodeRepository = nodeRepositoryOverride ?: mockk<NodeRepository>()
        val fileRepository = mockk<FileRepository>()
        val chainRepository = mockk<ChainRepository>()
        val webSocketTemplate = mockk<SimpMessagingTemplate>(relaxed = true)
        val nodesById = nodes.associateBy { it.id!! }
        val hostsById = nodes.associate { it.hostId to host(it.hostId) }
        val genesisFile = genesisShelleyFile()
        val defaultNode = nodes.firstOrNull(Node::isDefault)

        every { nodeRepository.findAll() } returns nodes
        every { nodeRepository.findDefault() } returns defaultNode
        every { chainRepository.findTipBlockNumber() } returns 7_403_221L
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
            chainRepository = chainRepository,
            webSocketTemplate = webSocketTemplate,
            shelleyGenesisAdapter = shelleyGenesisAdapter,
            moshi = moshi,
            nodesChannel = MutableSharedFlow(extraBufferCapacity = 8),
            latestNodeStats = latestNodeStats,
            tracingDashboardSignalService = TracingDashboardSignalService(tracingRawCaptureService),
            startupDelayMillis = 0L,
            sampleIntervalMillis = 50L,
            publishDelayMillis = 10L,
            rawSnapshotMaxAge = Duration.ofSeconds(15),
        )
    }

    private fun ByteArray.toDataPointsReply(): TraceForwardMessage.DataPointsReply =
        ByteArrayInputStream(this).use { input ->
            val payload = com.google.iot.cbor.CborReader.createFromInputStream(input).readDataItem() as com.google.iot.cbor.CborArray
            TraceForwardMessage.DataPointsReply(payload.elementAt(1) as com.google.iot.cbor.CborArray)
        }

    private fun ByteArray.toTraceObjectsReply(): TraceForwardMessage.TraceObjectsReply =
        ByteArrayInputStream(this).use { input ->
            val payload = com.google.iot.cbor.CborReader.createFromInputStream(input).readDataItem() as com.google.iot.cbor.CborArray
            TraceForwardMessage.TraceObjectsReply(payload.elementAt(1) as com.google.iot.cbor.CborArray)
        }

    private fun rawCaptureService(node: Node): TracingRawCaptureService =
        TracingRawCaptureService(
            tracingBlockMessageSink =
                com.swiftmako.jormanager.tracing.TracingBlockMessageSink(
                    nodeRepository = mockk<NodeRepository>().apply {
                        every { findById(node.id!!) } returns Optional.of(node)
                    },
                    hostRepository = mockk<HostRepository>().apply {
                        every { findById(node.hostId) } returns Optional.of(host(node.hostId))
                    },
                    tracingBlockPersistenceService = mockk(relaxed = true),
                )
        )

    private fun metricSnapshot(
        nodeId: Long,
        blockNum: Long = 7_403_221L,
        slotNum: Long = 7_403_221L,
        slotInEpoch: Long = 321L,
        epoch: Long = 490L,
        remainingKesPeriods: Long = 36L,
        txsProcessedNum: Long? = 123_456L,
        capturedAt: Instant = Instant.now(),
        metrics: Map<String, TracingRawMetricValue> =
            fullProtocol1MetricMap(
                blockNum = blockNum,
                slotNum = slotNum,
                slotInEpoch = slotInEpoch,
                epoch = epoch,
                remainingKesPeriods = remainingKesPeriods,
                txsProcessedNum = txsProcessedNum,
            ),
    ) =
        TracingRawMetricSnapshot(
            nodeId = nodeId,
            capturedAt = capturedAt,
            metrics = metrics,
            rawJson = "metrics",
        )

    private fun fullProtocol1MetricMap(
        blockNum: Long = 7_403_221L,
        slotNum: Long = 7_403_221L,
        slotInEpoch: Long = 321L,
        epoch: Long = 490L,
        remainingKesPeriods: Long = 36L,
        txsProcessedNum: Long? = 123_456L,
    ): Map<String, TracingRawMetricValue> =
        buildMap {
            put(TracingMetricDecoder.BLOCK_NUM, TracingRawMetricValue.IntGauge(blockNum))
            put(TracingMetricDecoder.SLOT_NUM, TracingRawMetricValue.IntGauge(slotNum))
            put(TracingMetricDecoder.SLOT_IN_EPOCH, TracingRawMetricValue.IntGauge(slotInEpoch))
            put(TracingMetricDecoder.EPOCH, TracingRawMetricValue.IntGauge(epoch))
            put(TracingMetricDecoder.DENSITY, TracingRawMetricValue.Label("0.55"))
            put(TracingMetricDecoder.TIP_BLOCK, TracingRawMetricValue.Label("hash-123"))
            put(TracingMetricDecoder.FORGING_ENABLED, TracingRawMetricValue.IntGauge(1L))
            put(TracingMetricDecoder.FORGE_ABOUT_TO_LEAD, TracingRawMetricValue.Counter(2L))
            put(TracingMetricDecoder.FORGE_NODE_NOT_LEADER, TracingRawMetricValue.Counter(3L))
            put(TracingMetricDecoder.FORGE_NODE_IS_LEADER, TracingRawMetricValue.Counter(4L))
            put(TracingMetricDecoder.FORGED_SLOT_LAST, TracingRawMetricValue.IntGauge(slotNum))
            put(TracingMetricDecoder.FORGE_FORGED, TracingRawMetricValue.Counter(5L))
            put(TracingMetricDecoder.FORGE_ADOPTED, TracingRawMetricValue.Counter(6L))
            put(TracingMetricDecoder.OPERATIONAL_CERTIFICATE_START_KES_PERIOD, TracingRawMetricValue.IntGauge(10L))
            put(TracingMetricDecoder.OPERATIONAL_CERTIFICATE_EXPIRY_KES_PERIOD, TracingRawMetricValue.IntGauge(72L))
            put(TracingMetricDecoder.CURRENT_KES_PERIOD, TracingRawMetricValue.IntGauge(36L))
            put(TracingMetricDecoder.REMAINING_KES_PERIODS, TracingRawMetricValue.IntGauge(remainingKesPeriods))
            put(TracingMetricDecoder.TXS_IN_MEMPOOL, TracingRawMetricValue.IntGauge(8L))
            put(TracingMetricDecoder.MEMPOOL_BYTES, TracingRawMetricValue.IntGauge(9L))
            txsProcessedNum?.let {
                put(TracingMetricDecoder.TXS_PROCESSED_NUM, TracingRawMetricValue.Counter(it))
            }
            put(TracingMetricDecoder.TXS_SYNC_DURATION, TracingRawMetricValue.IntGauge(10L))
            put(TracingMetricDecoder.TXS_SYNC_DURATION_TOTAL, TracingRawMetricValue.Counter(11L))
            put(TracingMetricDecoder.TXS_MEMPOOL_TIMEOUT_SOFT, TracingRawMetricValue.Counter(12L))
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
        promPort: Int = 12789,
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
            promPort = promPort,
            tracingHost = "0.0.0.0",
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
