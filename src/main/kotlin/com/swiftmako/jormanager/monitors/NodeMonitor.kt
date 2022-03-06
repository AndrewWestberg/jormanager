package com.swiftmako.jormanager.monitors

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.SSHClientPool
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.ktx.ignoreExceptions
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.model.NodeStats
import com.swiftmako.jormanager.model.ekg2.EkgMetrics2
import com.swiftmako.jormanager.moshi.adapters.PoolLedgerJsonAdapter
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.services.EkgService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SSHRuntimeException
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import okio.buffer
import okio.source
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random


@Component("nodeMonitor")
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
@Lazy(false)
class NodeMonitor @Autowired constructor(
    private val hostRepository: HostRepository,
    private val nodeRepository: NodeRepository,
    private val fileRepository: FileRepository,
    private val retrofit: Retrofit,
    private val webSocketTemplate: SimpMessagingTemplate,
    private val shelleyGenesisAdapter: JsonAdapter<GenesisShelley>,
    private val moshi: Moshi,
    @Qualifier("nodesChannel") private val nodesChannel: MutableSharedFlow<Node>,
    @Qualifier("newBlockChannel") private val newBlockChannel: MutableStateFlow<Long?>,
    @Qualifier("latestNodeStats") private val latestNodeStats: AtomicReference<NodeStats>,
) : SmartLifecycle, CoroutineScope {

    private val log by lazy { LoggerFactory.getLogger("NodeMonitor") }

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            log.error("Uncaught coroutine exception!", throwable)
        }
    }

    private val eventsChannel = Channel<NodeStats>(BUFFERED)
    private val mutex = Mutex()
    private val monitorJobMap: MutableMap<Long, Job> = mutableMapOf()
    private var isShuttingDown = false

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
//            nodeRepository.findAll().filter { !it.isDeleted }.forEach { node ->
//                nodesChannel.offer(node)
//            }
//        }

        loadLedgerValuesIfNecessary()
        monitorNodes()
        collectAndGroupStats()
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun stop(callback: Runnable) {
        isShuttingDown = true
        GlobalScope.launch {
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
                val coreNodes = nodeRepository.findAll()
                    .filter { !it.isDeleted && it.type != "relay" && (it.poolPledge == null || it.poolCost == null || it.poolMargin == null) }
                if (coreNodes.isEmpty()) {
                    return@launch
                }

                nodeRepository.findDefault()?.let { defaultNode ->
                    fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)?.let { genesisShelleyFile ->
                        val genesisShelley = shelleyGenesisAdapter.fromJson(genesisShelleyFile.content)!!
                        val magicString = if (genesisShelley.networkId.equals("testnet", ignoreCase = true)) {
                            "--testnet-magic ${genesisShelley.networkMagic}"
                        } else {
                            "--mainnet"
                        }
                        hostRepository.findByIdOrNull(defaultNode.hostId)?.let { defaultHost ->
                            val defaultHostConnection = HostConnection(defaultHost, defaultNode)

                            val ledgerStateFile = "/tmp/ledger-state-${genesisShelley.networkMagic}_pools.json"
                            defaultHostConnection.bashCommand(
                                "${defaultHost.cardanoCliPath} query ledger-state $magicString | jq -c > $ledgerStateFile",
                                timeoutSecs = 300L
                            )
                            val poolLedger = defaultHostConnection.commandGetFileBufferedSource(ledgerStateFile)
                                .use { ledgerStateJsonSource ->
                                    val poolIds = coreNodes.mapNotNull { it.poolId }.toSet()
                                    val poolLedgerAdapter = PoolLedgerJsonAdapter(moshi, poolIds)
                                    poolLedgerAdapter.fromJson(ledgerStateJsonSource)
                                        ?: throw IOException("Error dumping ledger state!")
                                }
                            defaultHostConnection.command("rm -f $ledgerStateFile")

                            coreNodes.forEach { coreNode ->
                                val updatedCoreNode = nodeRepository.save(
                                    coreNode.copy(
                                        poolPledge = poolLedger.poolIdToFutureLedgerParams[coreNode.poolId]?.pledge
                                            ?: poolLedger.poolIdToLedgerParams[coreNode.poolId]?.pledge,
                                        poolCost = poolLedger.poolIdToFutureLedgerParams[coreNode.poolId]?.cost
                                            ?: poolLedger.poolIdToLedgerParams[coreNode.poolId]?.cost,
                                        poolMargin = poolLedger.poolIdToFutureLedgerParams[coreNode.poolId]?.margin?.toString()
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
        launch {
            nodesChannel.collect { node ->
                if (node.type != "pool") {
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
    }

    private suspend fun monitorNodeLocal(node: Node) {
        val ekgService =
            retrofit.newBuilder().baseUrl("http://127.0.0.1:${node.ekgPort}").build().create(EkgService::class.java)
        monitorNode(node.id!!, ekgService)
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun monitorNodeRemote(host: Host, node: Node) {
        val sshClientPool = SSHClientPool.getInstance(host)
        var retry = true
        while (retry) {
            retry = false
            var ssh: SSHClient? = null
            var localPortForwarder: LocalPortForwarder? = null
            try {
                ssh = sshClientPool.borrow()
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

                val ekgService = retrofit.newBuilder().baseUrl("http://127.0.0.1:${localPort}").build()
                    .create(EkgService::class.java)
                monitorNode(node.id!!, ekgService, ssh, true)
            } catch (e: IOException) {
                if (!isShuttingDown) {
                    log.error("IOException communicating with ${node.name}", e)
                    retry = true
                }
            } catch (e: IllegalStateException) {
                if (!isShuttingDown) {
                    log.error("IllegalStateException communicating with ${node.name}", e)
                    retry = true
                }
            } catch (e: CancellationException) {
                log.warn("Monitoring job canceled: ${node.name}")
            } catch (e: Throwable) {
                log.error("Fatal error communicating with ${node.name}!", e)
            } finally {
                ignoreExceptions {
                    localPortForwarder?.close()
                }
                ignoreExceptions {
                    ssh?.let {
                        sshClientPool.recycle(ssh)
                    }
                }
            }
        }
    }

    private suspend fun monitorNode(
        nodeId: Long,
        ekgService: EkgService,
        ssh: SSHClient? = null,
        rethrowExceptions: Boolean = false
    ) {
        // delay a bit so the node has time to be saved in the db.
        delay(5000)
        var node = nodeRepository.findByIdOrNull(nodeId)!!
        log.info("Start NodeMonitor for: ${node.name}")

        val epochLength = fileRepository.findByIdOrNull(node.genesisShelleyFileId)?.let { genesisShelleyFile ->
            shelleyGenesisAdapter.fromJson(genesisShelleyFile.content)!!.epochLength
        } ?: 432000 //5-day default

        var lastBlockHeight = -1L
        while (true) {
            // delay until the next 5-second interval
            val before = System.currentTimeMillis()
            val delay = 5000 - (before % 5000)
            val now = before + delay
            try {
                node = nodeRepository.findByIdOrNull(nodeId)!!
                if (node.isDeleted) {
                    log.warn("Node deleted. Stop Monitoring...")
                    return
                }
                delay(delay)

                // Calculate incoming peers
                lateinit var output: String
                lateinit var errorOutput: String
                val incomingPeers: Int = ssh?.let {
                    ssh.startSession().use { session ->
                        val command =
                            "ss -n -p -4 state established | grep pid=\$(ps -Af | grep cardano-node | grep ${node.name}\\/topology | awk '{ print \$2 }') | grep ${node.port} | wc -l"
                        session.exec(command).use { cmd ->
                            output = cmd.inputStream.source().buffer().use { it.readUtf8() }
                            errorOutput = cmd.errorStream.source().buffer().use { it.readUtf8() }
                            cmd.join(5, TimeUnit.SECONDS)
                            if (cmd.exitStatus != 0) {
                                throw SSHRuntimeException("Command '$command' exited with code ${cmd.exitStatus}: ${cmd.exitErrorMessage}, $errorOutput")
                            }
                            output.trim().toInt()
                        }
                    }
                } ?: run {
                    val process = ProcessBuilder(
                        "/bin/bash",
                        "-c",
                        "ss -n -p -4 state established | grep pid=\$(ps -Af | grep cardano-node | grep ${node.name}\\/topology | awk '{ print \$2 }') | grep ${node.port} | wc -l"
                    ).start()
                    output = process.inputStream.source().buffer().use { it.readUtf8() }
                    process.waitFor(5, TimeUnit.SECONDS)
                    output.trim().toInt()
                }
                //log.debug("${node.name} incoming peers: $incomingPeers")

                val ekgMetrics = ekgService.getNodeMetrics2(now)

                if (ekgMetrics.cardano.node.metrics.blockNum.int.valX > 0L) {
                    // ignore any block height of zero. It just means we restarted the node and don't know where we are yet.
                    val nodeStats = ekgMetrics.toNodeStats(now, node, incomingPeers, epochLength)
                    eventsChannel.send(nodeStats)
                    if (node.isDefault) {
                        nodeStats.blockHeight?.let { newBlockHeight ->
                            if (newBlockHeight > lastBlockHeight) {
                                newBlockChannel.value = newBlockHeight
                                lastBlockHeight = newBlockHeight
                            }
                        }
                        latestNodeStats.set(nodeStats)
                    }
                } else {
                    eventsChannel.send(
                        NodeStats(
                            isDefault = node.isDefault,
                            timestamp = now,
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
                            epochLength = epochLength
                        )
                    )
                }
            } catch (e: ConnectException) {
                eventsChannel.send(
                    NodeStats(
                        isDefault = node.isDefault,
                        timestamp = now,
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
                        epochLength = epochLength
                    )
                )
                if (rethrowExceptions) {
                    throw e
                } else {
                    log.error("Connection problem!", e)
                }
            } catch (e: IOException) {
                eventsChannel.send(
                    NodeStats(
                        isDefault = node.isDefault,
                        timestamp = now,
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
                        epochLength = epochLength
                    )
                )
                if (rethrowExceptions) {
                    throw e
                } else {
                    log.error("Error communicating with Ekg or ss!", e)
                }
            }
        }
    }

    private fun EkgMetrics2.toNodeStats(timestamp: Long, node: Node, incomingPeers: Int, epochLength: Long): NodeStats {
        return NodeStats(
            isDefault = node.isDefault,
            timestamp = timestamp,
            nodeName = node.name,
            color = node.color,
            peers = this.cardano.node.metrics.connectedPeers.int.valX.toInt(),
            incomingPeers = incomingPeers,
            blockHeight = this.cardano.node.metrics.blockNum.int.valX,
            remainingKESPeriods = this.cardano.node.metrics.remainingKESPeriods.int.valX.toInt(),
            epoch = this.cardano.node.metrics.epoch.int.valX,
            slot = this.cardano.node.metrics.slotNum.int.valX,
            slotInEpoch = this.cardano.node.metrics.slotInEpoch.int.valX,
            txsProcessed = this.cardano.node.metrics.txsProcessedNum.int.valX,
            epochLength = epochLength,
        )
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
                // delay until the next 5-second interval + 3 sec
                val before = System.currentTimeMillis()
                val delay = RECONNECT_DELAY_MS - (before % RECONNECT_DELAY_MS)
                delay(delay + 3000)
                collectMutex.withLock {
                    webSocketTemplate.convertAndSend(
                        "/topic/messages",
                        SocketResponse.Success(type = "nodestats", data = nodeStatEvents)
                    )
                    nodeStatEvents.clear()
                }
            }
        }
    }

    /**
     * Find a local available port to bind to for port forwarding.
     */
    private fun availableLocalPort(): Int {
        val random = Random(System.currentTimeMillis())
        while (true) {
            val port = random.nextInt(23000, 65534)
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
