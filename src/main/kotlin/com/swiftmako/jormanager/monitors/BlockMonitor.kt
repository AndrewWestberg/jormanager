package com.swiftmako.jormanager.monitors

import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.entities.Block
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.TraceAdoptedBlock
import com.swiftmako.jormanager.repositories.BlockRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import java.io.File
import java.io.IOException
import java.io.Reader
import kotlin.coroutines.CoroutineContext

@Component("blockMonitor")
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
@Lazy(false)
class BlockMonitor @Autowired constructor(
        private val blockRepository: BlockRepository,
        private val moshi: Moshi,
        private val webSocketTemplate: SimpMessagingTemplate
) : SmartLifecycle, CoroutineScope {

    private val log = LoggerFactory.getLogger(BlockMonitor::class.java)

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            log.error("Uncaught coroutine exception!", throwable)
        }
    }

    private val adapter = moshi.adapter(TraceAdoptedBlock::class.java)

    override fun isAutoStartup() = true

    override fun isRunning(): Boolean {
        val isRunning = job.isActive && !job.isCompleted && job.children.count() > 0
        log.info("BlockMonitor isRunning: $isRunning")
        return isRunning
    }

    override fun start() {
        log.info("Starting BlockMonitor...")
        listOf("bcsh").forEach { node ->
            launch {
                var retry = true
                while (retry) {
                    retry = false
                    delay(RECONNECT_DELAY_MS)
                    val ssh = SSHClient()
                    ssh.loadKnownHosts()
                    ssh.addHostKeyVerifier(PromiscuousVerifier())
                    ssh.connect("papa", 15795)
                    try {
                        val base = "${System.getProperty("user.home")}${File.separator}.ssh${File.separator}"
                        ssh.authPublickey("westbam", "$base/tux_private.pem")
                        ssh.startSession().use { session ->
                            val cmd = session.exec("cat /home/westbam/haskell/${node}/logs/node-*.json | grep --line-buffered \"TraceAdoptedBlock\"")
                            cmd.inputStream.bufferedReader().use { reader ->
                                saveBlocksFromRemoteNode(node, reader)
                            }
                            cmd.join()
                        }
                        ssh.startSession().use { session ->
                            val cmd = session.exec("tail -F -n +0 /home/westbam/haskell/${node}/logs/node.json | grep --line-buffered \"TraceAdoptedBlock\"")
                            cmd.inputStream.bufferedReader().use { reader ->
                                saveBlocksFromRemoteNode(node, reader)
                            }
                            cmd.join()
                            log.info("Done tailing logs!")
                        }
                    } catch (e: IOException) {
                        log.error("IOException communicating with $node", e)
                        retry = true
                    } catch (e: Throwable) {
                        log.error("Fatal error communicating with $node!", e)
                    } finally {
                        ssh.disconnect()
                    }
                }
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