package com.swiftmako.jormanager.monitors

import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.ktx.ignoreExceptions
import com.swiftmako.jormanager.model.NodeStats
import com.swiftmako.jormanager.model.ekg.EkgMetrics
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.services.EkgService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import retrofit2.Retrofit
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random


@Component("nodeMonitor")
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
@Lazy(false)
class NodeMonitor @Autowired constructor(
        private val hostRepository: HostRepository,
        private val nodeRepository: NodeRepository,
        private val retrofit: Retrofit,
        private val webSocketTemplate: SimpMessagingTemplate,
        @Qualifier("nodesChannel") private val nodesChannel: BroadcastChannel<Node>,
        @Qualifier("newBlockChannel") private val newBlockChannel: BroadcastChannel<Long>
) : SmartLifecycle, CoroutineScope {

    private val log = LoggerFactory.getLogger(NodeMonitor::class.java)

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            log.error("Uncaught coroutine exception!", throwable)
        }
    }

    private val mutex = Mutex()
    private val monitorJobMap: MutableMap<Long, Job> = mutableMapOf()

    override fun isAutoStartup() = true

    override fun isRunning(): Boolean {
        val isRunning = job.isActive && !job.isCompleted && job.children.count() > 0
        log.info("NodeMonitor isRunning: $isRunning")
        return isRunning
    }

    override fun start() {
        log.info("Starting NodeMonitor...")

        // Don't need to do this as the BlockMonitor already gets our nodes from the db and offers them on the channel
//        launch {
//            nodeRepository.findAll().forEach { node ->
//                nodesChannel.offer(node)
//            }
//        }

        monitorNodes()
    }

    override fun stop() {
        job.cancelChildren()
        log.info("NodeMonitor stopped.")
    }

    private fun monitorNodes() {
        launch {
            nodesChannel.openSubscription().consumeEach { node ->
                mutex.withLock {

                    val existingJob = monitorJobMap[node.id]
                    if (existingJob?.isActive == true) {
                        // Respawn the monitoring job in case something changed like the port
                        existingJob.cancel()
                        monitorJobMap.remove(node.id)
                    }

                    // Start a new monitoring job for this node
                    hostRepository.findByIdOrNull(node.hostId)?.let { host ->
                        val monitoringJob = launch {
                            when (host.type) {
                                "local" -> {
                                    monitorNodeLocal(node)
                                }
                                "remote" -> {
                                    monitorNodeRemote(host, node)
                                }
                            }
                        }
                        monitorJobMap[node.id!!] = monitoringJob
                    } ?: log.error("Host not found for id ${node.hostId}")
                }
            }
        }
    }

    private suspend fun monitorNodeLocal(node: Node) {
        val ekgService = retrofit.newBuilder().baseUrl("http://127.0.0.1:${node.ekgPort}").build().create(EkgService::class.java)
        monitorNode(node, ekgService)
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun monitorNodeRemote(host: Host, node: Node) {
        var retry = true
        while (retry) {
            retry = false
            val ssh = SSHClient()
            ssh.loadKnownHosts()
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            var localPortForwarder: LocalPortForwarder? = null
            try {
                ssh.connect(host.hostname, host.sshPort)
                ssh.authPublickey(host.sshUser, host.sshPemPath)

                val localPort = availableLocalPort()
                val params = Parameters("127.0.0.1", localPort, "127.0.0.1", node.ekgPort)
                val serverSocket = ServerSocket().apply {
                    reuseAddress = true
                }
                serverSocket.bind(InetSocketAddress(params.localHost, params.localPort))
                localPortForwarder = ssh.newLocalPortForwarder(params, serverSocket)

                object : Thread("port forward $localPort") {
                    override fun run() {
                        localPortForwarder.listen()
                    }
                }.start()

                val ekgService = retrofit.newBuilder().baseUrl("http://127.0.0.1:${localPort}").build().create(EkgService::class.java)
                monitorNode(node, ekgService, true)
            } catch (e: IOException) {
                log.error("IOException communicating with ${node.name}", e)
                retry = true
            } catch (e: CancellationException) {
                log.warn("Monitoring job canceled: ${node.name}")
            } catch (e: Throwable) {
                log.error("Fatal error communicating with ${node.name}!", e)
            } finally {
                ignoreExceptions {
                    localPortForwarder?.close()
                }
                ignoreExceptions {
                    ssh.disconnect()
                }
            }
        }
    }

    private suspend fun monitorNode(node: Node, ekgService: EkgService, rethrowExceptions: Boolean = false) {
        log.info("Start NodeMonitor for: ${node.name}")
        var lastBlockHeight = -1L
        while (true) {
            // delay until the next 5-second interval
            val before = System.currentTimeMillis()
            val delay = 5000 - (before % 5000)
            val now = before + delay
            try {
                delay(delay)
                val ekgMetrics = ekgService.getNodeMetrics(now)

                if (ekgMetrics.cardano.node.chainDB.metrics.blockNum.intX.valX > 0) {
                    // ignore any block height of zero. It just means we restarted the node and don't know where we are yet.
                    val nodeStats = ekgMetrics.toNodeStats(now, node)
                    webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success(type = "nodestats", data = nodeStats))
                    if (node.isDefault) {
                        nodeStats.blockHeight?.let { newBlockHeight ->
                            if (newBlockHeight > lastBlockHeight) {
                                newBlockChannel.offer(newBlockHeight)
                                lastBlockHeight = newBlockHeight
                            }
                        }
                    }
                } else {
                    webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success(type = "nodestats", data = NodeStats(now, node.name, node.color, null, null)))
                }
            } catch (e: ConnectException) {
                webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success(type = "nodestats", data = NodeStats(now, node.name, node.color, null, null)))
                if (rethrowExceptions) {
                    throw e
                }

            } catch (e: IOException) {
                log.error("Error communicating with Ekg!", e)
                webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success(type = "nodestats", data = NodeStats(now, node.name, node.color, null, null)))
                if (rethrowExceptions) {
                    throw e
                }
            }
        }
    }

    private fun EkgMetrics.toNodeStats(timestamp: Long, node: Node): NodeStats {
        return NodeStats(
                timestamp = timestamp,
                nodeName = node.name,
                color = node.color,
                peers = this.cardano.node.blockFetchDecision.peers.connectedPeers.intX.valX.toInt(),
                blockHeight = this.cardano.node.chainDB.metrics.blockNum.intX.valX
        )
    }

    /**
     * Find a local available port to bind to for port forwarding.
     */
    private fun availableLocalPort(): Int {
        val random = Random(System.currentTimeMillis())
        while (true) {
            val port = random.nextInt(13000, 65534)
            try {
                ServerSocket(port).apply { reuseAddress = true }.use {
                    DatagramSocket(port).apply { reuseAddress = true }.use {
                        return port
                    }
                }
            } catch (e: IOException) {
            }
        }
    }


    companion object {
        private const val RECONNECT_DELAY_MS = 5000L
    }

}