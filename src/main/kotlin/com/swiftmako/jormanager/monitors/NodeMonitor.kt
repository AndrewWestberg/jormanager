package com.swiftmako.jormanager.monitors

import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.SocketResponse
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
import kotlin.coroutines.CoroutineContext

@Component("nodeMonitor")
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
@Lazy(false)
class NodeMonitor @Autowired constructor(
        private val hostRepository: HostRepository,
        private val nodeRepository: NodeRepository,
        private val retrofitBuilder: Retrofit.Builder,
        private val webSocketTemplate: SimpMessagingTemplate,
        @Qualifier("nodesChannel") private val nodesChannel: BroadcastChannel<Node>
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
        val ekgService = retrofitBuilder.baseUrl("http://127.0.0.1:${node.ekgPort}").build().create(EkgService::class.java)
        while (true) {
            try {
                // delay until the next 5-second interval
                delay(5000 - (System.currentTimeMillis() % 5000))
                val now = System.currentTimeMillis()
                val ekgMetrics = ekgService.getNodeMetrics(now)

                if (ekgMetrics.cardano.node.chainDB.metrics.blockNum.intX.valX > 0) {
                    // ignore any block height of zero. It just means we restarted the node and don't know where we are yet.
                    webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success(type = "nodestats", data = ekgMetrics.toNodeStats(now, node)))
                }
            } catch (e: ConnectException) {
                log.error(e.message)
            } catch (e: IOException) {
                log.error("Error communicating with Ekg!", e)
            }
        }
    }

    private suspend fun monitorNodeRemote(host: Host, node: Node) {

    }

    private fun EkgMetrics.toNodeStats(timestamp: Long, node: Node): NodeStats {
        return NodeStats(
                timestamp = timestamp,
                nodeName = node.name,
                color = node.color,
                blockHeight = this.cardano.node.chainDB.metrics.blockNum.intX.valX
        )
    }

}