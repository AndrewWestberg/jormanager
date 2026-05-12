package com.swiftmako.jormanager.tracing

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.tracing.fixtures.ScriptedTraceForwardServer
import com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixtures
import com.swiftmako.jormanager.tracing.fixtures.TraceForwardSessionScript
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class TracingConnectionManagerTest {
    @Test
    fun startSeedsEligibleCoreNodesAndConnects() =
        runBlocking {
            ScriptedTraceForwardServer.start(
                TraceForwardSessionScript(
                    expectedClientMessages = listOf(TraceForwardFixtures.msgTraceObjectsRequest(blocking = true, count = 25)),
                    serverResponses = listOf(TraceForwardFixtures.msgTraceObjectsReplyEmpty()),
                    closeAfterResponses = false,
                    awaitClientDisconnectAfterResponses = true,
                )
            ).use { server ->
                val node = coreNode(tracingPort = server.port)
                val manager =
                    createManager(
                        nodes = listOf(node),
                        hostById = mapOf(node.hostId to host()),
                    )

                manager.start()
                server.awaitAcceptedSessions(1)

                assertThat(manager.managedNodeIds()).containsExactly(node.id)

                manager.stopAndWait()
                server.awaitCompletion()
            }
        }

    @Test
    fun reconnectsAfterTraceSessionDisconnect() =
        runBlocking {
            ScriptedTraceForwardServer.start(
                TraceForwardSessionScript(
                    expectedClientMessages = listOf(TraceForwardFixtures.msgTraceObjectsRequest(blocking = true, count = 25)),
                    serverResponses = listOf(TraceForwardFixtures.msgTraceObjectsReplyEmpty()),
                ),
                TraceForwardSessionScript(
                    expectedClientMessages = listOf(TraceForwardFixtures.msgTraceObjectsRequest(blocking = true, count = 25)),
                    serverResponses = listOf(TraceForwardFixtures.msgTraceObjectsReplyEmpty()),
                ),
            ).use { server ->
                val node = coreNode(tracingPort = server.port)
                val manager =
                    createManager(
                        nodes = listOf(node),
                        hostById = mapOf(node.hostId to host()),
                        reconnectDelayMillis = 50L,
                    )

                manager.start()
                server.awaitCompletion()

                manager.stopAndWait()
            }
        }

    @Test
    fun deletingNodeTearsDownActiveTracingSession() =
        runBlocking {
            val nodesChannel = MutableSharedFlow<Node>(extraBufferCapacity = 8)
            ScriptedTraceForwardServer.start(
                TraceForwardSessionScript(
                    expectedClientMessages = listOf(TraceForwardFixtures.msgTraceObjectsRequest(blocking = true, count = 25)),
                    serverResponses = listOf(TraceForwardFixtures.msgTraceObjectsReplyEmpty()),
                    closeAfterResponses = false,
                    awaitClientDisconnectAfterResponses = true,
                )
            ).use { server ->
                val node = coreNode(tracingPort = server.port)
                val manager =
                    createManager(
                        nodes = listOf(node),
                        hostById = mapOf(node.hostId to host()),
                        nodesChannel = nodesChannel,
                        reconnectDelayMillis = 50L,
                    )

                manager.start()
                server.awaitAcceptedSessions(1)

                nodesChannel.emit(node.copy(isDeleted = true))
                server.awaitCompletion()

                assertThat(manager.managedNodeIds()).isEmpty()
                manager.stopAndWait()
            }
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
    fun directStopShutsDownActiveConnections() =
        runBlocking {
            ScriptedTraceForwardServer.start(
                TraceForwardSessionScript(
                    expectedClientMessages = listOf(TraceForwardFixtures.msgTraceObjectsRequest(blocking = true, count = 25)),
                    serverResponses = listOf(TraceForwardFixtures.msgTraceObjectsReplyEmpty()),
                    closeAfterResponses = false,
                    awaitClientDisconnectAfterResponses = true,
                )
            ).use { server ->
                val node = coreNode(tracingPort = server.port)
                val manager =
                    createManager(
                        nodes = listOf(node),
                        hostById = mapOf(node.hostId to host()),
                    )

                manager.start()
                server.awaitAcceptedSessions(1)

                manager.stop()
                server.awaitCompletion()

                assertThat(manager.isRunning()).isFalse()
                assertThat(manager.managedNodeIds()).isEmpty()
            }
        }

    @Test
    fun ineligibleNodesNeverOpenTracingSessions() =
        runBlocking {
            val attempts = AtomicInteger(0)
            val manager =
                createManager(
                    nodes = listOf(coreNode(type = "relay"), coreNode(tracingPort = null)),
                    hostById = mapOf(1L to host()),
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
            reconnectDelayMillis = reconnectDelayMillis,
        )
    }

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
            ekgPort = 12788,
            promPort = 12789,
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
