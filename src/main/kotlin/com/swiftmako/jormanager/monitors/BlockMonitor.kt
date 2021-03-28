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
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.model.GenesisByron
import com.swiftmako.jormanager.model.QueryTip
import com.swiftmako.jormanager.model.TraceAdoptedBlock
import com.swiftmako.jormanager.repositories.BlockRepository
import com.swiftmako.jormanager.repositories.ChainRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
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
import okio.buffer
import okio.source
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import java.io.IOException
import java.lang.IllegalStateException
import java.util.concurrent.TimeUnit
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
        @Qualifier("nodesChannel") private val nodesChannel: BroadcastChannel<Node>,
        private val blockUtils: BlockUtils,
        private val byronGenesisAdapter: JsonAdapter<GenesisByron>,
        private val shelleyShelleyGenesisAdapter: JsonAdapter<GenesisShelley>,
        private val adoptedBlockAdapter: JsonAdapter<TraceAdoptedBlock>,
        private val queryTipAdapter: JsonAdapter<QueryTip>,
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
            nodeRepository.findAll().filter { !it.isDeleted }.forEach { node ->
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
                        log.info("Skip block monitoring for: ${node.name}")
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
                    val poolIds = nodeRepository.findPoolIds()
                    // log.debug("validateBlocks: poolIds: $poolIds")
                    // find the latest block we know of for sure from the repository
                    val chainTipSlotNumber = chainRepository.findSyncedTip()
                    val unvalidatedBlocks =
                            blockRepository.findUnvalidatedBlocksOlderThan(chainTipSlotNumber - 180) // 3 minutes old
                    // log.debug("unvalidatedBlocks size: ${unvalidatedBlocks.size}")
                    // var firstTime = true
                    unvalidatedBlocks.forEach { unvalidatedBlock ->
                        val chainBlock = chainRepository.findBySlot(unvalidatedBlock.slot)
                        // if (firstTime) log.debug("chainBlock: $chainBlock")
                        val hash = if (poolIds.contains(chainBlock?.poolId)) {
                            chainBlock?.hash ?: unvalidatedBlock.hash
                        } else {
                            unvalidatedBlock.hash
                        }
                        // if (firstTime) log.debug("hash: $hash")

                        val pool = chainBlock?.let {
                            nodeRepository.findByPoolId(it.poolId)
                        } ?: unvalidatedBlock.pool
                        // if (firstTime) log.debug("pool: $pool")

                        if (hash.isEmpty()) {
                            // nothing to validate. This block must have been missed
                            blockRepository.save(unvalidatedBlock.copy(pool = pool, status = "missed")).also {
                                log.error("Missed Block: $it")
                                webSocketTemplate.convertAndSend(
                                        "/topic/messages",
                                        SocketResponse.Success(type = "block", data = it)
                                )
                            }
                        } else {
                            // we have a block hash to validate
                            if (chainBlock?.hash?.startsWith(hash) == true) {
                                blockRepository.save(unvalidatedBlock.copy(hash = chainBlock.hash, pool = pool, status = "forged"))
                                        .also {
                                            log.info("Forged Block: $it")
                                            webSocketTemplate.convertAndSend(
                                                    "/topic/messages",
                                                    SocketResponse.Success(type = "block", data = it)
                                            )
                                        }
                            } else {
                                blockRepository.save(unvalidatedBlock.copy(pool = pool, status = "orphaned")).also {
                                    log.error("Orphaned Block: $it")
                                    webSocketTemplate.convertAndSend(
                                            "/topic/messages",
                                            SocketResponse.Success(type = "block", data = it)
                                    )
                                }
                            }
                        }
                        // firstTime = false
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
            val shelley = shelleyShelleyGenesisAdapter.fromJson(shelleyGenesisFile.content)!!
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
                    val monitoredNode =
                            nodeRepository.findByIdOrNull(node.id) ?: throw CancellationException("Node not found!")
                    if (monitoredNode.isDeleted) {
                        throw CancellationException("Node has been deleted!")
                    }
                    val logPath = "${host.nodeHomePath}/${monitoredNode.name}/logs/node.json"
                    log.debug("Monitoring blocks from:  $logPath")

                    var process = ProcessBuilder(
                            "/bin/bash",
                            "-c",
                            "cat ${host.nodeHomePath}/${monitoredNode.name}/logs/node-*.json | grep -F TraceAdoptedBlock"
                    ).start()
                    process.inputStream.source().buffer().use { source ->
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (line.contains("val")) {
                                saveBlocksFromRemoteNode(null, monitoredNode, magicString, byron, shelley, line)
                            }
                        }
                    }
                    process.waitFor(60, TimeUnit.SECONDS)

                    process = ProcessBuilder(
                            "/bin/bash",
                            "-c",
                            "tail -Fn0 ${host.nodeHomePath}/${monitoredNode.name}/logs/node.json | grep --line-buffered 'TraceAdoptedBlock'"
                    ).start()
                    process.inputStream.source().buffer().use { source ->
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (line.contains("val")) {
                                saveBlocksFromRemoteNode(host, monitoredNode, magicString, byron, shelley, line)
                            }
                        }
                    }
                    process.waitFor(1, TimeUnit.SECONDS)

                    log.info("Done tailing logs at: $logPath")
                    retry = true
                } catch (e: IOException) {
                    log.error("IOException communicating with ${node.name}", e)
                    retry = true
                } catch (e: IllegalStateException) {
                    log.error("IllegalStateException communicating with ${node.name}", e)
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
            val shelley = shelleyShelleyGenesisAdapter.fromJson(shelleyGenesisFile.content)!!
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
                    val monitoredNode =
                            nodeRepository.findByIdOrNull(node.id) ?: throw CancellationException("Node not found!")
                    if (monitoredNode.isDeleted) {
                        throw CancellationException("Node has been deleted!")
                    }

                    val logPath = "${host.nodeHomePath}/${monitoredNode.name}/logs/node.json"
                    log.debug("Monitoring blocks from:  $logPath")

                    ssh.connect(host.hostname, host.sshPort)
                    ssh.authPublickey(host.sshUser, host.sshPemPath)
                    ssh.startSession().use { session ->
                        val cmd =
                                session.exec("cat ${host.nodeHomePath}/${monitoredNode.name}/logs/node-*.json | grep -F TraceAdoptedBlock")
                        cmd.inputStream.source().buffer().use { source ->
                            while (true) {
                                val line = source.readUtf8Line() ?: break
                                if (line.contains("val")) {
                                    saveBlocksFromRemoteNode(null, monitoredNode, magicString, byron, shelley, line)
                                }
                            }
                        }
                        cmd.join()
                    }
                    ssh.startSession().use { session ->
                        val cmd =
                                session.exec("tail -Fn0 ${host.nodeHomePath}/${monitoredNode.name}/logs/node.json | grep --line-buffered 'TraceAdoptedBlock'")
                        cmd.inputStream.source().buffer().use { source ->
                            while (true) {
                                val line = source.readUtf8Line() ?: break
                                if (line.contains("val")) {
                                    saveBlocksFromRemoteNode(host, monitoredNode, magicString, byron, shelley, line)
                                }
                            }
                        }
                        cmd.join()
                        log.info("Done tailing logs!")
                    }
                } catch (e: IOException) {
                    log.error("IOException communicating with ${node.name}", e)
                    retry = true
                } catch (e: IllegalStateException) {
                    log.error("IllegalStateException communicating with ${node.name}", e)
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

    private suspend fun saveBlocksFromRemoteNode(
            host: Host?,
            node: Node,
            magicString: String,
            byron: GenesisByron,
            shelley: GenesisShelley,
            line: String
    ) {
        coroutineScope {
            blockFoundMutex.withLock {
                adoptedBlockAdapter.fromJson(line)?.let { traceAdoptedBlock ->
                    try {
                        val (epoch, slotInEpoch) = blockUtils.getEpochAndSlot(
                                byron,
                                shelley,
                                traceAdoptedBlock.data.block.slot
                        )
                        val block = Block(
                                at = traceAdoptedBlock.localTimeString(),
                                pool = "---",
                                host = traceAdoptedBlock.host,
                                slot = traceAdoptedBlock.data.block.slot,
                                epoch = epoch,
                                slotInEpoch = slotInEpoch,
                                hash = traceAdoptedBlock.data.block.rawHash(),
                                status = "completed"
                        )

                        val existingBlock =
                                blockRepository.findBySlot(traceAdoptedBlock.data.block.slot).firstOrNull()

                        if (existingBlock == null || existingBlock.hash.isEmpty()) {
                            val pool = existingBlock?.pool ?: "---"
                            val hashUpdatedBlock: Block = host?.let {
                                val hostConnection = HostConnection(host, node)
                                val tipJson =
                                        hostConnection.command("${host.cardanoCliPath} query tip $magicString").trim()
                                queryTipAdapter.fromJson(tipJson)?.let { queryTip ->
                                    if (queryTip.hash.startsWith(block.hash)) {
                                        block.copy(id = existingBlock?.id, pool = pool, hash = queryTip.hash)
                                    } else {
                                        block.copy(id = existingBlock?.id, pool = pool)
                                    }
                                } ?: block.copy(id = existingBlock?.id, pool = pool)
                            } ?: block.copy(id = existingBlock?.id, pool = pool)

                            val savedBlock = blockRepository.save(hashUpdatedBlock)

                            log.info(savedBlock.toString())
                            webSocketTemplate.convertAndSend(
                                    "/topic/messages",
                                    SocketResponse.Success(type = "block", data = savedBlock)
                            )
                        }
                    } catch (e: DataIntegrityViolationException) {
                        log.warn("Block Exists! (${node.name}): $traceAdoptedBlock", e)
                    } catch (e: Throwable) {
                        log.error("Save Block Error!", e)
                    }
                } ?: log.error("Unable to parse block json!")
            }
        }
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