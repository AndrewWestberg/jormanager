package com.swiftmako.jormanager.monitors

import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.controllers.utils.BlockUtils
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.SSHClientPool
import com.swiftmako.jormanager.entities.Block
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.ktx.ignoreExceptions
import com.swiftmako.jormanager.model.AddedToCurrentChain
import com.swiftmako.jormanager.model.Genesis
import com.swiftmako.jormanager.model.GenesisByron
import com.swiftmako.jormanager.model.QueryTip
import com.swiftmako.jormanager.model.TraceAdoptedBlock
import com.swiftmako.jormanager.model.pooltool.Data
import com.swiftmako.jormanager.model.pooltool.PooltoolStats
import com.swiftmako.jormanager.repositories.BlockRepository
import com.swiftmako.jormanager.repositories.ChainRepository
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
import org.apache.commons.io.input.Tailer
import org.apache.commons.io.input.TailerListenerAdapter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext

@Component("blockMonitor")
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
@Lazy(false)
class BlockMonitor @Autowired constructor(
        private val blockRepository: BlockRepository,
        private val chainRepository: ChainRepository,
        private val hostRepository: HostRepository,
        private val nodeRepository: NodeRepository,
        private val fileRepository: FileRepository,
        private val webSocketTemplate: SimpMessagingTemplate,
        private val pooltoolService: PooltoolService,
        @Value("\${pooltool.apikey}") private val pooltoolApiKey: String,
        @Qualifier("nodesChannel") private val nodesChannel: BroadcastChannel<Node>,
        private val blockUtils: BlockUtils,
        private val byronGenesisAdapter: JsonAdapter<GenesisByron>,
        private val shelleyGenesisAdapter: JsonAdapter<Genesis>,
        private val adoptedBlockAdapter: JsonAdapter<TraceAdoptedBlock>,
        private val queryTipAdapter: JsonAdapter<QueryTip>,
        private val blockAdapter: JsonAdapter<AddedToCurrentChain>,
) : SmartLifecycle, CoroutineScope {

    private val log = LoggerFactory.getLogger(BlockMonitor::class.java)

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            log.error("Uncaught coroutine exception!", throwable)
        }
    }
    private val mutex = Mutex()
    private val blockFoundMutex = Mutex()
    private val monitorJobMap: MutableMap<Long, Job> = mutableMapOf()

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
        validateBlocks()
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

    private fun validateBlocks() {
        launch {
            while (true) {
                try {
                    // find the latest block we know of for sure from the repository
                    val chainTipSlotNumber = chainRepository.findSyncedTip()
                    val unvalidatedBlocks = blockRepository.findUnvalidatedBlocksOlderThan(chainTipSlotNumber - 180) // 3 minutes old
                    log.debug("unvalidatedBlocks size: ${unvalidatedBlocks.size}")
                    unvalidatedBlocks.forEach { unvalidatedBlock ->
                        if (unvalidatedBlock.hash.isEmpty()) {
                            // nothing to validate. This block must have been missed
                            blockRepository.save(unvalidatedBlock.copy(status = "missed")).also {
                                log.error("Missed Block: $it")
                                webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success(type = "block", data = it))
                            }
                        } else {
                            // we have a block hash to validate
                            val chainBlock = chainRepository.findBySlot(unvalidatedBlock.slot)
                            if (chainBlock?.hash?.startsWith(unvalidatedBlock.hash) == true) {
                                blockRepository.save(unvalidatedBlock.copy(hash = chainBlock.hash, status = "forged")).also {
                                    log.info("Forged Block: $it")
                                    webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success(type = "block", data = it))
                                }
                            } else {
                                blockRepository.save(unvalidatedBlock.copy(status = "orphaned")).also {
                                    log.error("Orphaned Block: $it")
                                    webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success(type = "block", data = it))
                                }
                            }
                        }
                    }
                } catch (e: EmptyResultDataAccessException) {
                    // ignore this one until we get some blocks in our table
                } catch (e: Throwable) {
                    log.error("Error validating blocks!", e)
                }

                delay(10_000)
            }
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun monitorBlocksLocal(host: Host, node: Node) {
        coroutineScope {
            val byronGenesisFile = fileRepository.findByIdOrNull(node.genesisByronFileId)
                    ?: throw IOException("Unable to read byron genesis file!")
            val byron = byronGenesisAdapter.fromJson(byronGenesisFile.content)!!
            val shelleyGenesisFile = fileRepository.findByIdOrNull(node.genesisShelleyFileId)
                    ?: throw IOException("Unable to read shelley genesis file!")
            val shelley = shelleyGenesisAdapter.fromJson(shelleyGenesisFile.content)!!
            val magicString = if (shelley.networkId.equals("testnet", ignoreCase = true)) {
                "--testnet-magic ${shelley.networkMagic}"
            } else {
                "--mainnet"
            }

            // delay a bit so we have some latest nodestats already
            delay(3 * RECONNECT_DELAY_MS)

            var retry = true
            while (retry) {
                retry = false
                delay(RECONNECT_DELAY_MS)

                try {
                    val logPath = "${host.nodeHomePath}/${node.name}/logs/node.json"
                    log.debug("Monitoring blocks from:  $logPath")
                    Tailer.create(File(logPath), object : TailerListenerAdapter() {
                        override fun handle(line: String?) {
                            if (line != null) {
                                if (line.contains("TraceAdoptedBlock")) {
                                    launch {
                                        saveBlocksFromRemoteNode(host, node, magicString, byron, shelley, line)
                                    }
                                } else if (line.contains("TraceAddBlockEvent.AddedToCurrentChain")) {
                                    if (pooltoolApiKey.isNotBlank()) {
                                        launch {
                                            sendBlockToPooltool(host, node, magicString, line)
                                        }
                                    }
                                }
                            }
                        }
                    }, 100, false, true, 8192).run()
                    log.info("Done tailing logs at: $logPath")
                    retry = true
                } catch (e: IOException) {
                    log.error("IOException communicating with ${node.name}", e)
                    retry = true
                } catch (e: CancellationException) {
                    log.warn("Monitoring job canceled: ${node.name}")
                } catch (e: Throwable) {
                    log.error("Fatal error communicating with ${node.name}!", e)
                }
            }
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun monitorBlocksRemote(host: Host, node: Node) {
        coroutineScope {
            val byronGenesisFile = fileRepository.findByIdOrNull(node.genesisByronFileId)
                    ?: throw IOException("Unable to read byron genesis file!")
            val byron = byronGenesisAdapter.fromJson(byronGenesisFile.content)!!
            val shelleyGenesisFile = fileRepository.findByIdOrNull(node.genesisShelleyFileId)
                    ?: throw IOException("Unable to read shelley genesis file!")
            val shelley = shelleyGenesisAdapter.fromJson(shelleyGenesisFile.content)!!
            val magicString = if (shelley.networkId.equals("testnet", ignoreCase = true)) {
                "--testnet-magic ${shelley.networkMagic}"
            } else {
                "--mainnet"
            }

            // delay a bit so we have some latest nodestats already
            delay(3 * RECONNECT_DELAY_MS)

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
                                launch {
                                    saveBlocksFromRemoteNode(null, node, magicString, byron, shelley, line)
                                }
                            }
                        }
                        cmd.join()
                    }
                    ssh.startSession().use { session ->

                        val cmd = session.exec("tail -Fn0 ${host.nodeHomePath}/${node.name}/logs/node.json | grep --line-buffered 'TraceAdoptedBlock\\|TraceAddBlockEvent.AddedToCurrentChain'")
                        cmd.inputStream.bufferedReader().use { reader ->
                            reader.forEachLine { line ->
                                if (line.contains("TraceAdoptedBlock")) {
                                    launch {
                                        saveBlocksFromRemoteNode(host, node, magicString, byron, shelley, line)
                                    }
                                } else {
                                    if (pooltoolApiKey.isNotBlank()) {
                                        launch {
                                            sendBlockToPooltool(host, node, magicString, line)
                                        }
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

    private suspend fun saveBlocksFromRemoteNode(host: Host?, node: Node, magicString: String, byron: GenesisByron, shelley: Genesis, line: String) {
        coroutineScope {
            blockFoundMutex.withLock {
                adoptedBlockAdapter.fromJson(line)?.let { traceAdoptedBlock ->
                    try {
                        val (epoch, slotInEpoch) = blockUtils.getEpochAndSlot(byron, shelley, traceAdoptedBlock.block.slot)
                        val block = Block(
                                at = traceAdoptedBlock.localTimeString(),
                                pool = node.name,
                                host = traceAdoptedBlock.host,
                                slot = traceAdoptedBlock.block.slot,
                                epoch = epoch,
                                slotInEpoch = slotInEpoch,
                                hash = traceAdoptedBlock.block.rawHash(),
                                status = "completed"
                        )

                        val existingBlock = blockRepository.findByPoolAndSlot(node.name, traceAdoptedBlock.block.slot)

                        if (existingBlock == null || existingBlock.hash.isEmpty()) {
                            val hashUpdatedBlock: Block = host?.let {
                                val hostConnection = HostConnection(host, node)
                                val tipJson = hostConnection.command("${host.cardanoCliPath} shelley query tip $magicString").trim()
                                queryTipAdapter.fromJson(tipJson)?.let { queryTip ->
                                    if (queryTip.headerHash.startsWith(block.hash)) {
                                        block.copy(id = existingBlock?.id, hash = queryTip.headerHash)
                                    } else {
                                        block.copy(id = existingBlock?.id)
                                    }
                                } ?: block.copy(id = existingBlock?.id)
                            } ?: block.copy(id = existingBlock?.id)

                            val savedBlock = blockRepository.save(hashUpdatedBlock)

                            log.info(savedBlock.toString())
                            webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success(type = "block", data = savedBlock))
                        }
                    } catch (e: DataIntegrityViolationException) {
                        log.warn("Block Exists! (${node.name}): $traceAdoptedBlock", e)
                    } catch (e: Throwable) {
                        log.error("Save Block Error!", e)
                    }
                }
            }
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun sendBlockToPooltool(host: Host, node: Node, magicString: String, line: String) {
        blockAdapter.fromJson(line)?.let { addedToCurrentChain ->
            val hostConnection = HostConnection(host, node)
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
                    log.debug("pooltool response: ${response.body()}")
                } catch (e: Throwable) {
                    log.error("Error sending stats to pooltool!", e)
                }
            } ?: throw IOException("Could not query json tip for node!")
        } ?: throw IOException("Could not parse AddedToCurrentChain json!")
    }

    override fun stop() {
        // shutdown all ssh connections
        SSHClientPool.shutdown()

        job.cancelChildren()
        log.info("BlockMonitor stopped.")
    }

    companion object {
        const val RECONNECT_DELAY_MS = 5000L
    }
}