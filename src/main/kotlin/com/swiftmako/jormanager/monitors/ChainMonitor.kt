package com.swiftmako.jormanager.monitors

import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.model.Config
import com.swiftmako.jormanager.model.Genesis
import com.swiftmako.jormanager.nodeclient.protocols.mux.MuxProtocol
import com.swiftmako.jormanager.repositories.ChainRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.io.IOException
import kotlin.coroutines.CoroutineContext

@Component("chainMonitor")
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
@Lazy(false)
class ChainMonitor @Autowired constructor(
        private val chainRepository: ChainRepository,
        private val hostRepository: HostRepository,
        private val nodeRepository: NodeRepository,
        private val fileRepository: FileRepository,
        private val shelleyGenesisAdapter: JsonAdapter<Genesis>,
        private val configAdapter: JsonAdapter<Config>,
) : SmartLifecycle, CoroutineScope {

    private val log = LoggerFactory.getLogger(ChainMonitor::class.java)

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            log.error("Uncaught coroutine exception!", throwable)
        }
    }

    private lateinit var muxProtocol: MuxProtocol

    override fun isAutoStartup() = true

    override fun isRunning(): Boolean {
        val isRunning = job.isActive && !job.isCompleted && job.children.count() > 0
        log.info("ChainMonitor isRunning: $isRunning")
        return isRunning
    }

    override fun start() {
        log.info("Starting ChainMonitor...")

        monitorChain()
    }

    private fun monitorChain() {
        launch {
            while (true) {
                try {
                    nodeRepository.findDefault()?.let { defaultNode ->
                        val shelleyGenesisFile = fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)
                                ?: throw IOException("Unable to read shelley genesis file!")
                        val shelley = shelleyGenesisAdapter.fromJson(shelleyGenesisFile.content)!!
                        val networkMagic = shelley.networkMagic ?: throw IOException("network magic not found!")
                        val defaultHost = hostRepository.findByIdOrNull(defaultNode.hostId)
                                ?: throw IOException("host for default node not found!")
                        val configFile = fileRepository.findByIdOrNull(defaultNode.configFileId)
                                ?: throw IOException("Unable to read config file")
                        val shelleyGenesisHash = configAdapter.fromJson(configFile.content)!!.shelleyGenesisHash.hexToByteArray()

                        muxProtocol = MuxProtocol(defaultHost.hostname, defaultNode.port, networkMagic, shelleyGenesisHash, chainRepository)
                        muxProtocol.start().join()
                    }
                } catch (e: Throwable) {
                    log.error("Error monitoring chain!", e)
                }

                delay(RECONNECT_DELAY_MS)
            }
        }
    }


    override fun stop() {
        muxProtocol.cancel()
        muxProtocol.job.cancelChildren()
        job.cancelChildren()
        log.info("ChainMonitor stopped.")
    }

    companion object {
        const val RECONNECT_DELAY_MS = 5000L
    }
}