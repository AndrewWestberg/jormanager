package com.swiftmako.jormanager.monitors

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.model.NodeStats
import com.swiftmako.jormanager.moshi.adapters.PoolLedgerJsonAdapter
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.tracing.DataPointSessionClientFactory
import com.swiftmako.jormanager.tracing.NodeStateDataPointDecoder
import com.swiftmako.jormanager.tracing.NodeStateMetrics
import com.swiftmako.jormanager.tracing.SocketDataPointSessionClientFactory
import com.swiftmako.jormanager.tracing.TraceForwardMessage
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
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

@Component("nodeMonitor")
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
@Lazy(false)
class NodeMonitor
    @Autowired
    constructor(
        private val hostRepository: HostRepository,
        private val nodeRepository: NodeRepository,
        private val fileRepository: FileRepository,
        private val webSocketTemplate: SimpMessagingTemplate,
        private val shelleyGenesisAdapter: JsonAdapter<GenesisShelley>,
        private val moshi: Moshi,
        @param:Qualifier("nodesChannel") private val nodesChannel: MutableSharedFlow<Node>,
        @param:Qualifier("latestNodeStats") private val latestNodeStats: AtomicReference<NodeStats>,
        private val nodeStateDecoder: NodeStateDataPointDecoder = NodeStateDataPointDecoder(),
        private val dataPointSessionClientFactory: DataPointSessionClientFactory = SocketDataPointSessionClientFactory(),
        private val startupDelayMillis: Long = STARTUP_DELAY_MS,
        private val sampleIntervalMillis: Long = SAMPLE_INTERVAL_MS,
        private val publishDelayMillis: Long = PUBLISH_DELAY_MS,
    ) : SmartLifecycle,
        CoroutineScope {
        private val log by lazy { LoggerFactory.getLogger("NodeMonitor") }

        private val job = SupervisorJob()
        override val coroutineContext: CoroutineContext =
            job + Dispatchers.IO +
                CoroutineExceptionHandler { _, throwable ->
                    if (throwable !is CancellationException) {
                        log.error("Uncaught coroutine exception!", throwable)
                    }
                }

        private val eventsChannel = Channel<NodeStats>(BUFFERED)
        private val mutex = Mutex()
        private val monitorJobMap: MutableMap<Long, Job> = mutableMapOf()
        private var nodeCollectorJob: Job? = null
        private var seedJob: Job? = null
        private var isShuttingDown = false

        init {
            require(sampleIntervalMillis > 0) { "sampleIntervalMillis must be positive" }
            require(publishDelayMillis >= 0) { "publishDelayMillis cannot be negative" }
            require(startupDelayMillis >= 0) { "startupDelayMillis cannot be negative" }
        }

        override fun isAutoStartup() = "repair" != System.getProperty("jormanager.mode")

        override fun isRunning(): Boolean {
            val isRunning = job.isActive && !job.isCompleted && job.children.count() > 0
            log.info("NodeMonitor isRunning: $isRunning")
            return isRunning
        }

        override fun start() {
            log.info("Starting NodeMonitor...")

            loadLedgerValuesIfNecessary()
            monitorNodes()
            seedNodes()
            collectAndGroupStats()
        }

        @OptIn(DelicateCoroutinesApi::class)
        override fun stop(callback: Runnable) {
            isShuttingDown = true
            GlobalScope.launch {
                seedJob?.cancel()
                nodeCollectorJob?.cancel()
                job.cancelChildren()
                job.cancelAndJoin()
                log.info("NodeMonitor stopped.")
                callback.run()
            }
        }

        override fun stop() {
        }

        /**
         * Load some values from ledger state into our db if they don't exist already
         */
        private fun loadLedgerValuesIfNecessary() {
            launch {
                try {
                    // core nodes that need updating from the ledger state
                    val coreNodes =
                        nodeRepository
                            .findAll()
                            .filter { !it.isDeleted && it.type != "relay" && (it.poolPledge == null || it.poolCost == null || it.poolMargin == null) }
                    if (coreNodes.isEmpty()) {
                        return@launch
                    }

                    nodeRepository.findDefault()?.let { defaultNode ->
                        fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)?.let { genesisShelleyFile ->
                            val genesisShelley = shelleyGenesisAdapter.fromJson(genesisShelleyFile.content)!!
                            val magicString =
                                if (genesisShelley.networkId.equals("testnet", ignoreCase = true)) {
                                    "--testnet-magic ${genesisShelley.networkMagic}"
                                } else {
                                    "--mainnet"
                                }
                            hostRepository.findByIdOrNull(defaultNode.hostId)?.let { defaultHost ->
                                val defaultHostConnection = HostConnection(defaultHost, defaultNode)
                                val socketPath = "--socket-path ${defaultHost.nodeHomePath}/${defaultNode.name}/db/socket"

                                val ledgerStateFile = "/tmp/ledger-state-${genesisShelley.networkMagic}_pools.json"
                                defaultHostConnection.bashCommand(
                                    "${defaultHost.cardanoCliPath} conway query ledger-state $magicString $socketPath --output-json | jq -c > $ledgerStateFile",
                                    timeoutSecs = 300L
                                )
                                val poolLedger =
                                    defaultHostConnection
                                        .commandGetFileBufferedSource(ledgerStateFile)
                                        .use { ledgerStateJsonSource ->
                                            val poolIds = coreNodes.mapNotNull { it.poolId }.toSet()
                                            val poolLedgerAdapter = PoolLedgerJsonAdapter(moshi, poolIds)
                                            poolLedgerAdapter.fromJson(ledgerStateJsonSource)
                                                ?: throw IOException("Error dumping ledger state!")
                                        }
                                defaultHostConnection.command("rm -f $ledgerStateFile")

                                coreNodes.forEach { coreNode ->
                                    val updatedCoreNode =
                                        nodeRepository.save(
                                            coreNode.copy(
                                                poolPledge =
                                                    poolLedger.poolIdToFutureLedgerParams[coreNode.poolId]?.pledge
                                                        ?: poolLedger.poolIdToLedgerParams[coreNode.poolId]?.pledge,
                                                poolCost =
                                                    poolLedger.poolIdToFutureLedgerParams[coreNode.poolId]?.cost
                                                        ?: poolLedger.poolIdToLedgerParams[coreNode.poolId]?.cost,
                                                poolMargin =
                                                    poolLedger.poolIdToFutureLedgerParams[coreNode.poolId]?.margin?.toString()
                                                        ?: poolLedger.poolIdToLedgerParams[coreNode.poolId]?.margin?.toString(),
                                            )
                                        )
                                    log.warn("Updated ${updatedCoreNode.name} based on Ledger State: pledge: ${updatedCoreNode.poolPledge}, cost: ${updatedCoreNode.poolCost}, margin: ${updatedCoreNode.poolMargin}")
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    log.error("Error updating core node values from ledger!", e)
                }
            }
        }

        private fun monitorNodes() {
            nodeCollectorJob =
                launch {
                    nodesChannel.collect { node ->
                        refreshNode(node)
                    }
                }
        }

        private fun seedNodes() {
            seedJob =
                launch {
                    nodeRepository.findAll().forEach { node ->
                        refreshNode(node)
                    }
                }
        }

        private suspend fun refreshNode(node: Node) {
            val nodeId = node.id ?: return

            mutex.withLock {
                val existingJob = monitorJobMap[nodeId]
                if (!shouldMonitorNode(node)) {
                    existingJob?.cancel()
                    monitorJobMap.remove(nodeId)
                    return
                }

                existingJob?.cancel()
                monitorJobMap[nodeId] =
                    launch {
                        monitorNode(nodeId)
                    }
            }
        }

        private suspend fun monitorNode(nodeId: Long) {
            if (startupDelayMillis > 0) {
                delay(startupDelayMillis)
            }

            var node = nodeRepository.findByIdOrNull(nodeId) ?: return
            if (!shouldMonitorNode(node)) {
                return
            }

            log.info("Start NodeMonitor for: ${node.name}")

            val epochLength =
                resolveEpochLength(node.genesisShelleyFileId)

            while (true) {
                val waitMillis = nextAlignedDelay(sampleIntervalMillis)
                val now = System.currentTimeMillis() + waitMillis
                try {
                    delay(waitMillis)
                    node = nodeRepository.findByIdOrNull(nodeId) ?: return
                    if (!shouldMonitorNode(node)) {
                        log.warn("Node deleted. Stop Monitoring...")
                        return
                    }
                    val nodeStats = loadNodeStats(node, now, epochLength)
                    eventsChannel.send(nodeStats)
                    if (node.isDefault && nodeStats.blockHeight != null) {
                        latestNodeStats.set(nodeStats)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    eventsChannel.send(nullNodeStats(node, now, epochLength))
                    log.error("Error communicating with node-state endpoint!", e)
                } catch (e: Throwable) {
                    eventsChannel.send(nullNodeStats(node, now, epochLength))
                    log.error("Fatal node-state monitoring error for ${node.name}", e)
                }
            }
        }

        private suspend fun loadNodeStats(
            node: Node,
            timestamp: Long,
            epochLength: Long,
        ): NodeStats {
            val host = hostRepository.findByIdOrNull(node.hostId)
            if (host == null || node.tracingPort == null) {
                return nullNodeStats(node, timestamp, epochLength)
            }

            val metrics = loadNodeStateMetrics(host, node.tracingPort)
            return if (metrics != null && metrics.blockHeight > 0L) {
                metrics.toNodeStats(
                    timestamp = timestamp,
                    nodeName = node.name,
                    color = node.color,
                    isDefault = node.isDefault,
                    epochLength = epochLength,
                )
            } else {
                nullNodeStats(node, timestamp, epochLength)
            }
        }

        private suspend fun loadNodeStateMetrics(
            host: Host,
            tracingPort: Int,
        ): NodeStateMetrics? {
            val client = dataPointSessionClientFactory.create()
            try {
                var decoded: NodeStateMetrics? = null
                client.runSession(host.hostname, tracingPort) { message ->
                    if (message is TraceForwardMessage.DataPointsReply && decoded == null) {
                        decoded = nodeStateDecoder.decode(message)
                    }
                }
                return decoded
            } finally {
                client.close()
            }
        }

        private fun resolveEpochLength(genesisShelleyFileId: Long): Long =
            fileRepository.findByIdOrNull(genesisShelleyFileId)
                ?.toEpochLength()
                ?: DEFAULT_EPOCH_LENGTH

        private fun File.toEpochLength(): Long =
            shelleyGenesisAdapter.fromJson(content)?.epochLength ?: DEFAULT_EPOCH_LENGTH

        private fun nullNodeStats(
            node: Node,
            timestamp: Long,
            epochLength: Long,
        ): NodeStats =
            NodeStats(
                isDefault = node.isDefault,
                timestamp = timestamp,
                nodeName = node.name,
                color = node.color,
                peers = null,
                incomingPeers = null,
                blockHeight = null,
                remainingKESPeriods = null,
                epoch = null,
                slot = null,
                slotInEpoch = null,
                txsProcessed = null,
                epochLength = epochLength,
            )

        private fun shouldMonitorNode(node: Node): Boolean =
            !node.isDeleted && node.type == CORE_NODE_TYPE && node.tracingPort != null

        private fun nextAlignedDelay(intervalMillis: Long): Long {
            val now = System.currentTimeMillis()
            val remainder = now % intervalMillis
            return if (remainder == 0L) intervalMillis else intervalMillis - remainder
        }

        private fun collectAndGroupStats() {
            val collectMutex = Mutex()
            val nodeStatEvents = mutableListOf<NodeStats>()
            launch {
                eventsChannel.consumeEach { nodeStats ->
                    collectMutex.withLock {
                        nodeStatEvents.add(nodeStats)
                    }
                }
            }

            launch {
                while (true) {
                    delay(nextAlignedDelay(sampleIntervalMillis) + publishDelayMillis)
                    collectMutex.withLock {
                        if (nodeStatEvents.isNotEmpty()) {
                            webSocketTemplate.convertAndSend(
                                "/topic/messages",
                                SocketResponse.Success(type = "nodestats", data = nodeStatEvents.toList())
                            )
                            nodeStatEvents.clear()
                        }
                    }
                }
            }
        }

        companion object {
            private const val CORE_NODE_TYPE = "core"
            private const val DEFAULT_EPOCH_LENGTH = 432000L
            private const val STARTUP_DELAY_MS = 5000L
            private const val SAMPLE_INTERVAL_MS = 5000L
            private const val PUBLISH_DELAY_MS = 3000L
        }
    }
