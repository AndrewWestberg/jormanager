package com.swiftmako.jormanager.monitors

import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.Block
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.ktx.ignoreExceptions
import com.swiftmako.jormanager.model.AddedToCurrentChain
import com.swiftmako.jormanager.model.Genesis
import com.swiftmako.jormanager.model.QueryTip
import com.swiftmako.jormanager.model.TraceAdoptedBlock
import com.swiftmako.jormanager.model.pooltool.Data
import com.swiftmako.jormanager.model.pooltool.PooltoolStats
import com.swiftmako.jormanager.repositories.BlockRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.services.PooltoolService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import java.io.IOException
import kotlin.coroutines.CoroutineContext

@Component("blockMonitor")
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
@Lazy(false)
class BlockMonitor @Autowired constructor(
        private val blockRepository: BlockRepository,
        private val hostRepository: HostRepository,
        private val nodeRepository: NodeRepository,
        private val fileRepository: FileRepository,
        moshi: Moshi,
        private val webSocketTemplate: SimpMessagingTemplate,
        private val pooltoolService: PooltoolService,
        @Value("\${pooltool.apikey}") private val pooltoolApiKey: String,
        @Qualifier("nodesChannel") private val nodesChannel: BroadcastChannel<Node>
) : SmartLifecycle, CoroutineScope {

    private val log = LoggerFactory.getLogger(BlockMonitor::class.java)

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            log.error("Uncaught coroutine exception!", throwable)
        }
    }
    private val mutex = Mutex()
    private val monitorJobMap: MutableMap<Long, Job> = mutableMapOf()

    private val adoptedBlockAdapter by lazy { moshi.adapter(TraceAdoptedBlock::class.java) }
    private val blockAdapter by lazy { moshi.adapter(AddedToCurrentChain::class.java) }
    private val queryTipAdapter by lazy { moshi.adapter(QueryTip::class.java) }
    private val genesisAdapter by lazy { moshi.adapter(Genesis::class.java) }

    override fun isAutoStartup() = true

    override fun isRunning(): Boolean {
        val isRunning = job.isActive && !job.isCompleted && job.children.count() > 0
        log.info("BlockMonitor isRunning: $isRunning")
        return isRunning
    }

    override fun start() {
        log.info("Starting BlockMonitor...")
        launch {
            nodeRepository.findAll().forEach { node ->
                nodesChannel.offer(node)
            }
        }

        monitorBlocks()
    }

    private fun monitorBlocks() {
        launch {
            nodesChannel.openSubscription().consumeEach { node ->
                mutex.withLock {
                    if (node.type != "core") {
                        // Don't monitor blocks unless it is a core node
                        log.info("Skip block monitoring for relay node: ${node.name}")
                        return@consumeEach
                    }
                    log.info("Start block monitoring for core node: ${node.name}")

                    val existingJob = monitorJobMap[node.id]
                    if (existingJob?.isActive == true) {
                        // Respawn the monitoring job in case something changed.
                        existingJob.cancel()
                        monitorJobMap.remove(node.id)
                    }

                    // Start a new monitoring job for this node
                    hostRepository.findByIdOrNull(node.hostId)?.let { host ->
                        val monitoringJob = launch {
                            when (host.type) {
                                "local" -> {
                                    monitorBlocksLocal(host, node)
                                }
                                "remote" -> {
                                    monitorBlocksRemote(host, node)
                                }
                            }
                        }
                        monitorJobMap[node.id!!] = monitoringJob
                    } ?: log.error("Host not found for id ${node.hostId}")
                }
            }
        }
    }

    private suspend fun monitorBlocksLocal(host: Host, node: Node) {
        TODO("Not implemented yet!")
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun monitorBlocksRemote(host: Host, node: Node) {
        coroutineScope {
            val magicString = fileRepository.findByIdOrNull(node.genesisShelleyFileId)?.let { genesisFile ->
                val genesis = genesisAdapter.fromJson(genesisFile.content)!!
                if (genesis.networkId.equals("testnet", ignoreCase = true)) {
                    "--testnet-magic ${genesis.networkMagic}"
                } else {
                    "--mainnet"
                }
            } ?: throw IOException("Unable to read shelley genesis file!")

            var retry = true
            while (retry) {
                retry = false
                delay(RECONNECT_DELAY_MS)

                val ssh = SSHClient()
                ssh.loadKnownHosts()
                ssh.addHostKeyVerifier(PromiscuousVerifier())
                try {
                    ssh.connect(host.hostname, host.sshPort)
                    ssh.authPublickey(host.sshUser, host.sshPemPath)
                    ssh.startSession().use { session ->
                        val cmd = session.exec("cat ${host.nodeHomePath}/${node.name}/logs/node-*.json | grep --line-buffered \"TraceAdoptedBlock\"")
                        cmd.inputStream.bufferedReader().use { reader ->
                            reader.forEachLine { line ->
                                saveBlocksFromRemoteNode(null, node, magicString, line)
                            }
                        }
                        cmd.join()
                    }
                    ssh.startSession().use { session ->

                        val cmd = session.exec("tail -Fn0 ${host.nodeHomePath}/${node.name}/logs/node.json | grep --line-buffered 'TraceAdoptedBlock\\|TraceAddBlockEvent.AddedToCurrentChain'")
                        cmd.inputStream.bufferedReader().use { reader ->
                            reader.forEachLine { line ->
                                if (line.contains("TraceAdoptedBlock")) {
                                    saveBlocksFromRemoteNode(host, node, magicString, line)
                                } else {
                                    launch {
                                        sendBlockToPooltool(host, node, magicString, line)
                                    }
                                }
                            }
                        }
                        cmd.join()
                        log.info("Done tailing logs!")
                    }
                } catch (e: IOException) {
                    log.error("IOException communicating with ${node.name}", e)
                    retry = true
                } catch (e: CancellationException) {
                    log.warn("Monitoring job canceled: ${node.name}")
                } catch (e: Throwable) {
                    log.error("Fatal error communicating with ${node.name}!", e)
                } finally {
                    ignoreExceptions {
                        ssh.disconnect()
                    }
                }
            }
        }
    }

    private fun saveBlocksFromRemoteNode(host: Host?, node: Node, magicString: String, line: String) {
        adoptedBlockAdapter.fromJson(line)?.let { traceAdoptedBlock ->
            try {
                val block = Block(
                        at = traceAdoptedBlock.localTimeString(),
                        pool = node.name,
                        host = traceAdoptedBlock.host,
                        slot = traceAdoptedBlock.block.slot,
                        hash = traceAdoptedBlock.block.rawHash()
                )

                val existingBlock = blockRepository.findBySlot(traceAdoptedBlock.block.slot)

                if (existingBlock == null) {
                    blockRepository.save(block)

                    val savedBlock: Block = host?.let {
                        HostConnection(host, node).use { hostConnection ->
                            val tipJson = hostConnection.command("${host.cardanoCliPath} shelley query tip $magicString").trim()
                            queryTipAdapter.fromJson(tipJson)?.let { queryTip ->
                                if (queryTip.headerHash.startsWith(block.hash)) {
                                    blockRepository.save(block.copy(hash = queryTip.headerHash))
                                } else {
                                    block
                                }
                            } ?: block
                        }
                    } ?: block

                    log.info(savedBlock.toString())
                    webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success(type = "block", data = savedBlock))
                }
            } catch (e: DataIntegrityViolationException) {
                log.warn("Block Exists!: $traceAdoptedBlock")
            } catch (e: Throwable) {
                log.error("Save Block Error!", e)
            }
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun sendBlockToPooltool(host: Host, node: Node, magicString: String, line: String) {
        blockAdapter.fromJson(line)?.let { addedToCurrentChain ->
            HostConnection(host, node).use { hostConnection ->
                val tipJson = hostConnection.command("${host.cardanoCliPath} shelley query tip $magicString").trim()
                queryTipAdapter.fromJson(tipJson)?.let { queryTip ->
                    try {
                        val stats = PooltoolStats(
                                apiKey = pooltoolApiKey,
                                poolId = requireNotNull(node.poolId),
                                data = Data(
                                        nodeId = "", // future use
                                        version = addedToCurrentChain.env,
                                        at = addedToCurrentChain.at,
                                        blockNo = queryTip.blockNo,
                                        slotNo = queryTip.slotNo,
                                        blockHash = queryTip.headerHash
                                )
                        )
                        log.info("Pooltool Request: $stats")
                        val response = pooltoolService.sendStats(stats)
                        log.info("pooltool response: ${response.body()}")
                    } catch (e: Throwable) {
                        log.error("Error sending stats to pooltool!", e)
                    }
                } ?: throw IOException("Could not query json tip for node!")
            }
        } ?: throw IOException("Could not parse AddedToCurrentChain json!")
    }

    override fun stop() {
        job.cancelChildren()
        log.info("BlockMonitor stopped.")
    }

    companion object {
        const val RECONNECT_DELAY_MS = 5000L
    }
}