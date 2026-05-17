package com.swiftmako.jormanager.tracing

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.time.Instant
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class TracingConnectionManagerTest {
    @Test
    fun startSeedsEligibleNodesAndConnects() =
        runBlocking {
            val attempts = AtomicInteger(0)
            val closed = AtomicReference(false)
            val node = coreNode()
            val manager =
                createManager(
                    nodes = listOf(node),
                    hostById = mapOf(node.hostId to host()),
                    sessionClientFactory =
                        TraceForwardSessionClientFactory {
                            object : TraceForwardSessionClient {
                                override suspend fun runSession(
                                    hostname: String,
                                    port: Int,
                                    onMessage: suspend (TraceForwardMessage) -> Unit,
                                ) {
                                    attempts.incrementAndGet()
                                    awaitCancellation()
                                }

                                override fun close() {
                                    closed.set(true)
                                }
                            }
                        },
                )

            manager.start()
            waitUntil { attempts.get() == 1 }

            assertThat(manager.managedNodeIds()).containsExactly(node.id)

            manager.stopAndWait()
            assertThat(closed.get()).isTrue()
        }

    @Test
    fun reconnectsAfterTraceSessionDisconnect() =
        runBlocking {
            val attempts = AtomicInteger(0)
            val manager =
                createManager(
                    nodes = listOf(coreNode()),
                    hostById = mapOf(1L to host()),
                    reconnectDelayMillis = 50L,
                    sessionClientFactory =
                        TraceForwardSessionClientFactory {
                            object : TraceForwardSessionClient {
                                override suspend fun runSession(
                                    hostname: String,
                                    port: Int,
                                    onMessage: suspend (TraceForwardMessage) -> Unit,
                                ) {
                                    attempts.incrementAndGet()
                                    throw IOException("synthetic disconnect")
                                }

                                override fun close() {
                                }
                            }
                        },
                )

            manager.start()
            waitUntil { attempts.get() >= 2 }
            manager.stopAndWait()
        }

    @Test
    fun deletingNodeTearsDownActiveTracingSession() =
        runBlocking {
            val nodesChannel = MutableSharedFlow<Node>(extraBufferCapacity = 8)
            val closed = AtomicReference(false)
            val attempts = AtomicInteger(0)
            val node = coreNode()
            val manager =
                createManager(
                    nodes = listOf(node),
                    hostById = mapOf(node.hostId to host()),
                    nodesChannel = nodesChannel,
                    reconnectDelayMillis = 50L,
                    sessionClientFactory =
                        TraceForwardSessionClientFactory {
                            object : TraceForwardSessionClient {
                                override suspend fun runSession(
                                    hostname: String,
                                    port: Int,
                                    onMessage: suspend (TraceForwardMessage) -> Unit,
                                ) {
                                    attempts.incrementAndGet()
                                    awaitCancellation()
                                }

                                override fun close() {
                                    closed.set(true)
                                }
                            }
                        },
                )

            manager.start()
            waitUntil { attempts.get() == 1 }
            nodesChannel.emit(node.copy(isDeleted = true))
            waitUntil { closed.get() }

            assertThat(closed.get()).isTrue()
            assertThat(manager.managedNodeIds()).isEmpty()
            manager.stopAndWait()
        }

    @Test
    fun stopPreventsReconnectAfterSessionEnds() =
        runBlocking {
            val attempts = AtomicInteger(0)
            val manager =
                createManager(
                    nodes = listOf(coreNode()),
                    hostById = mapOf(1L to host()),
                    reconnectDelayMillis = 100L,
                    sessionClientFactory =
                        TraceForwardSessionClientFactory {
                            object : TraceForwardSessionClient {
                                override suspend fun runSession(
                                    hostname: String,
                                    port: Int,
                                    onMessage: suspend (TraceForwardMessage) -> Unit,
                                ) {
                                    attempts.incrementAndGet()
                                    throw IOException("synthetic disconnect")
                                }

                                override fun close() {
                                }
                            }
                        },
                )

            manager.start()
            waitUntil { attempts.get() == 1 }

            manager.stopAndWait()
            Thread.sleep(200)

            assertThat(attempts.get()).isEqualTo(1)
        }

    @Test
    fun idleSessionDoesNotReconnectWhileSocketStaysOpen() =
        runBlocking {
            val attempts = AtomicInteger(0)
            val closed = AtomicReference(false)
            val manager =
                createManager(
                    nodes = listOf(coreNode()),
                    hostById = mapOf(1L to host()),
                    reconnectDelayMillis = 50L,
                    sessionClientFactory =
                        TraceForwardSessionClientFactory {
                            object : TraceForwardSessionClient {
                                override suspend fun runSession(
                                    hostname: String,
                                    port: Int,
                                    onMessage: suspend (TraceForwardMessage) -> Unit,
                                ) {
                                    attempts.incrementAndGet()
                                    awaitCancellation()
                                }

                                override fun close() {
                                    closed.set(true)
                                }
                            }
                        },
                )

            manager.start()
            waitUntil { attempts.get() == 1 }
            Thread.sleep(350)

            assertThat(attempts.get()).isEqualTo(1)

            manager.stopAndWait()
            assertThat(closed.get()).isTrue()
        }

    @Test
    fun unifiedIngressCapturesMetricsTraceObjectsAndDataPoints() =
        runBlocking {
            val node = coreNode()
            val captureService = createRawCaptureService(node)
            val manager =
                createManager(
                    nodes = listOf(node),
                    hostById = mapOf(1L to host()),
                    messageSink = captureService,
                    sessionClientFactory =
                        TraceForwardSessionClientFactory {
                            object : TraceForwardSessionClient {
                                override suspend fun runSession(
                                    hostname: String,
                                    port: Int,
                                    onMessage: suspend (TraceForwardMessage) -> Unit,
                                ) {
                                    onMessage(
                                        TraceForwardMessage.MetricsReply(
                                            metrics =
                                                mapOf(
                                                    "counter" to TracingRawMetricValue.Counter(3),
                                                    "gauge" to TracingRawMetricValue.IntGauge(7),
                                                    "label" to TracingRawMetricValue.Label("hello"),
                                                ),
                                            rawJson = "metrics-raw",
                                        )
                                    )
                                    onMessage(dataPointsReply())
                                    onMessage(traceObjectsReply())
                                    awaitCancellation()
                                }

                                override fun close() {
                                }
                            }
                        },
                )

            manager.start()
            waitUntil {
                captureService.latestMetricSnapshot(node.id!!)?.metrics?.size == 3 &&
                    captureService.latestDataPointSnapshot(node.id)?.dataPoints?.size() == 1 &&
                    captureService.recentTraceObjectBatches(node.id).isNotEmpty()
            }

            val metricSnapshot = captureService.latestMetricSnapshot(node.id!!)
            assertThat(metricSnapshot?.metrics?.get("counter")).isEqualTo(TracingRawMetricValue.Counter(3))
            assertThat(metricSnapshot?.metrics?.get("gauge")).isEqualTo(TracingRawMetricValue.IntGauge(7))
            assertThat(metricSnapshot?.metrics?.get("label")).isEqualTo(TracingRawMetricValue.Label("hello"))
            assertThat(captureService.latestDataPointSnapshot(node.id)?.toMessage()?.dataPoints?.size()).isEqualTo(1)
            assertThat(captureService.recentTraceObjectBatches(node.id)).hasSize(1)

            manager.stopAndWait()

            assertThat(captureService.latestMetricSnapshot(node.id)).isNull()
            assertThat(captureService.latestDataPointSnapshot(node.id)).isNull()
            assertThat(captureService.recentTraceObjectBatches(node.id)).isEmpty()
        }

    @Test
    fun clearNodeEvictsFreshSnapshotsAndTraceBatches() =
        runBlocking {
            val node = coreNode()
            val captureService = createRawCaptureService(node)

            captureService.recordMetricSnapshotForTest(
                node.id!!,
                TracingRawMetricSnapshot(
                    nodeId = node.id,
                    capturedAt = Instant.now(),
                    metrics = mapOf("counter" to TracingRawMetricValue.Counter(1)),
                    rawJson = "metrics",
                ),
            )
            captureService.recordDataPointSnapshotForTest(
                node.id,
                TracingRawDataPointSnapshot(
                    nodeId = node.id,
                    capturedAt = Instant.now(),
                    dataPoints = dataPointsReply().dataPoints,
                ),
            )
            captureService.recordTraceObjectBatchForTest(
                node.id,
                TracingRawTraceObjectBatch(
                    nodeId = node.id,
                    capturedAt = Instant.now(),
                    traceObjects = traceObjectsReply().traceObjects,
                ),
            )

            captureService.clearNode(node.id)

            assertThat(captureService.latestMetricSnapshot(node.id)).isNull()
            assertThat(captureService.latestDataPointSnapshot(node.id)).isNull()
            assertThat(captureService.recentTraceObjectBatches(node.id)).isEmpty()
        }

    @Test
    fun stopClearsRawCaptureForManagedNode() =
        runBlocking {
            val node = coreNode()
            val captureService = createRawCaptureService(node)
            val manager =
                createManager(
                    nodes = listOf(node),
                    hostById = mapOf(node.hostId to host()),
                    messageSink = captureService,
                    sessionClientFactory =
                        TraceForwardSessionClientFactory {
                            object : TraceForwardSessionClient {
                                override suspend fun runSession(
                                    hostname: String,
                                    port: Int,
                                    onMessage: suspend (TraceForwardMessage) -> Unit,
                                ) {
                                    onMessage(dataPointsReply())
                                    awaitCancellation()
                                }

                                override fun close() {
                                }
                            }
                        },
                )

            manager.start()
            waitUntil { captureService.latestDataPointSnapshot(node.id!!) != null }

            manager.stopAndWait()

            assertThat(captureService.latestDataPointSnapshot(node.id!!)).isNull()
            assertThat(captureService.recentTraceObjectBatches(node.id!!)).isEmpty()
        }

    @Test
    fun directStopShutsDownActiveConnections() =
        runBlocking {
            val closed = AtomicReference(false)
            val manager =
                createManager(
                    nodes = listOf(coreNode()),
                    hostById = mapOf(1L to host()),
                    sessionClientFactory =
                        TraceForwardSessionClientFactory {
                            object : TraceForwardSessionClient {
                                override suspend fun runSession(
                                    hostname: String,
                                    port: Int,
                                    onMessage: suspend (TraceForwardMessage) -> Unit,
                                ) {
                                    awaitCancellation()
                                }

                                override fun close() {
                                    closed.set(true)
                                }
                            }
                        },
                )

            manager.start()
            manager.stop()
            waitUntil { !manager.isRunning() }

            assertThat(closed.get()).isTrue()
            assertThat(manager.managedNodeIds()).isEmpty()
        }

    @Test
    fun ineligibleNodesNeverOpenTracingSessions() =
        runBlocking {
            val attempts = AtomicInteger(0)
            val manager =
                createManager(
                    nodes = listOf(
                        coreNode(type = "relay", tracingPort = null),
                        coreNode(type = "pool", tracingPort = 12791, id = 2L),
                        coreNode(tracingPort = null, id = 3L),
                    ),
                    hostById = mapOf(1L to host(), 2L to host(), 3L to host()),
                    sessionClientFactory =
                        TraceForwardSessionClientFactory {
                            object : TraceForwardSessionClient {
                                override suspend fun runSession(
                                    hostname: String,
                                    port: Int,
                                    onMessage: suspend (TraceForwardMessage) -> Unit,
                                ) {
                                    attempts.incrementAndGet()
                                }

                                override fun close() {
                                }
                            }
                        },
                )

            manager.start()
            Thread.sleep(100)

            assertThat(attempts.get()).isEqualTo(0)
            assertThat(manager.managedNodeIds()).isEmpty()

            manager.stopAndWait()
        }

    private fun createManager(
        nodes: List<Node>,
        hostById: Map<Long, Host>,
        nodesChannel: MutableSharedFlow<Node> = MutableSharedFlow(extraBufferCapacity = 8),
        reconnectDelayMillis: Long = 50L,
        sessionClientFactory: TraceForwardSessionClientFactory = SocketTraceForwardSessionClientFactory(),
        messageSink: TracingRawCaptureService = createRawCaptureService(coreNode()),
    ): TracingConnectionManager {
        val nodeRepository = mockk<NodeRepository>()
        val hostRepository = mockk<HostRepository>()

        every { nodeRepository.findAll() } returns nodes
        hostById.forEach { (configuredHostId, host) ->
            every { hostRepository.findById(configuredHostId) } returns Optional.of(host)
        }

        return TracingConnectionManager(
            nodeRepository = nodeRepository,
            hostRepository = hostRepository,
            nodesChannel = nodesChannel,
            sessionClientFactory = sessionClientFactory,
            messageSink = messageSink,
            reconnectDelayMillis = reconnectDelayMillis,
        )
    }

    private fun createRawCaptureService(node: Node): TracingRawCaptureService {
        val nodeRepository = mockk<NodeRepository>()
        val hostRepository = mockk<HostRepository>()
        val blockService = mockk<TracingBlockPersistenceService>(relaxed = true)
        every { nodeRepository.findById(node.id!!) } returns Optional.of(node)
        every { hostRepository.findById(node.hostId) } returns Optional.of(host())
        return TracingRawCaptureService(
            tracingBlockMessageSink =
                TracingBlockMessageSink(
                    nodeRepository = nodeRepository,
                    hostRepository = hostRepository,
                    tracingBlockPersistenceService = blockService,
                )
        )
    }

    private fun dataPointsReply() =
        TraceForwardMessage.DataPointsReply(
            com.google.iot.cbor.CborArray.create().apply {
                add(
                    com.google.iot.cbor.CborArray.create().apply {
                        add(com.google.iot.cbor.CborTextString.create("NodeInfo"))
                        add(com.google.iot.cbor.CborArray.create())
                    }
                )
            }
        )

    private fun traceObjectsReply() =
        TraceForwardMessage.TraceObjectsReply(
            com.google.iot.cbor.CborArray.create().apply {
                add(com.google.iot.cbor.CborTextString.create("{\"toNamespace\":[\"Forge\",\"AdoptedBlock\"],\"toMachine\":{\"kind\":\"TraceAdoptedBlock\",\"slot\":1,\"blockHash\":\"abc\"},\"toHostname\":\"host\",\"toTimestamp\":\"2026-05-12T00:00:00Z\"}"))
            }
        )

    private fun coreNode(
        id: Long = 1L,
        type: String = "core",
        tracingPort: Int? = 12790,
        hostId: Long = 1L,
    ) =
        Node(
            id = id,
            hostId = hostId,
            color = "#123456",
            type = type,
            processorThreads = 1,
            name = "core-a",
            listen = "0.0.0.0",
            port = 3001,
            promPort = 12789,
            tracingHost = "0.0.0.0",
            genesisByronFileId = 1L,
            genesisShelleyFileId = 2L,
            genesisAlonzoFileId = 3L,
            genesisConwayFileId = 4L,
            configFileId = 5L,
            isDefault = false,
            tracingPort = tracingPort,
        )

    private fun host() =
        Host(
            id = 1L,
            type = "remote",
            cardanoCliPath = "/usr/bin/cardano-cli",
            cardanoNodePath = "/usr/bin/cardano-node",
            hostname = "127.0.0.1",
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
