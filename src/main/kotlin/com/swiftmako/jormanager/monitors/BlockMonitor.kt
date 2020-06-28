package com.swiftmako.jormanager.monitors

import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.entities.Block
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.TraceAdoptedBlock
import com.swiftmako.jormanager.repositories.BlockRepository
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import java.io.IOException
import java.io.Reader
import kotlin.coroutines.CoroutineContext

@Component("blockMonitor")
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
@Lazy(false)
class BlockMonitor @Autowired constructor(
        private val blockRepository: BlockRepository,
        private val hostRepository: HostRepository,
        private val nodeRepository: NodeRepository,
        moshi: Moshi,
        private val webSocketTemplate: SimpMessagingTemplate,
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

    private val adapter = moshi.adapter(TraceAdoptedBlock::class.java)

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

    private suspend fun monitorBlocksRemote(host: Host, node: Node) {
        var retry = true
        while (retry) {
            retry = false
            delay(RECONNECT_DELAY_MS)

            val ssh = SSHClient()
            ssh.loadKnownHosts()
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connect(host.hostname, host.sshPort)
            try {
                ssh.authPublickey(host.sshUser, host.sshPemPath)
                ssh.startSession().use { session ->
                    val cmd = session.exec("cat ${host.nodeHomePath}/${node.name}/logs/node-*.json | grep --line-buffered \"TraceAdoptedBlock\"")
                    cmd.inputStream.bufferedReader().use { reader ->
                        saveBlocksFromRemoteNode(node.name, reader)
                    }
                    cmd.join()
                }
                ssh.startSession().use { session ->
                    val cmd = session.exec("tail -F -n +0 ${host.nodeHomePath}/${node.name}/logs/node.json | grep --line-buffered \"TraceAdoptedBlock\"")
                    cmd.inputStream.bufferedReader().use { reader ->
                        saveBlocksFromRemoteNode(node.name, reader)
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
                ssh.disconnect()
            }
        }
    }

    private fun saveBlocksFromRemoteNode(node: String, reader: Reader) {
        reader.use {
            it.forEachLine { line ->
                adapter.fromJson(line)?.let { traceAdoptedBlock ->
                    try {
                        val block = Block(
                                at = traceAdoptedBlock.localAtTime(),
                                pool = node,
                                host = traceAdoptedBlock.host,
                                slot = traceAdoptedBlock.block.slot,
                                hash = traceAdoptedBlock.block.rawHash()
                        )

                        val existingBlock = blockRepository.findBySlot(traceAdoptedBlock.block.slot)

                        if (existingBlock == null) {
                            blockRepository.save(block)
                            log.info(block.toString())
                            webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success(type = "block", data = block))
                        }
                    } catch (e: DataIntegrityViolationException) {
                        log.warn("Block Exists!: $traceAdoptedBlock")
                    }
                }
            }
        }
    }

    override fun stop() {
        job.cancelChildren()
        log.info("BlockMonitor stopped.")
    }

    companion object {
        const val RECONNECT_DELAY_MS = 5000L
    }
}