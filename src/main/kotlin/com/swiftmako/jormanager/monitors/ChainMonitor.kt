package com.swiftmako.jormanager.monitors

import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.model.Config
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.monitors.utils.ChainRepositoryHelper.getChainBlocksForSyncStart
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.BlockFetchProtocol
import com.swiftmako.jormanager.nodeclient.protocols.chainsync.ChainSyncProtocol
import com.swiftmako.jormanager.nodeclient.protocols.handshake.HandshakeProtocol
import com.swiftmako.jormanager.nodeclient.protocols.mux.Mux
import com.swiftmako.jormanager.repositories.*
import com.swiftmako.jormanager.services.PooltoolService
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.InetSocketAddress
import kotlin.coroutines.CoroutineContext

@Component("chainMonitor")
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
@Lazy(false)
class ChainMonitor @Autowired constructor(
    private val chainRepository: ChainRepository,
    private val hostRepository: HostRepository,
    private val nodeRepository: NodeRepository,
    private val fileRepository: FileRepository,
    private val shelleyShelleyGenesisAdapter: JsonAdapter<GenesisShelley>,
    private val configAdapter: JsonAdapter<Config>,
    private val pooltoolService: PooltoolService,
    private val blockFetchRepository: BlockFetchRepository,
    private val ledgerRepository: LedgerRepository,
) : SmartLifecycle, CoroutineScope {

    private val log by lazy { LoggerFactory.getLogger(ChainMonitor::class.java) }

    private var isShuttingDown = false

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext =
        job + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            if (throwable !is CancellationException) {
                log.error("Uncaught coroutine exception!", throwable)
            }
        }

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
            while (!isShuttingDown) {
                try {
                    nodeRepository.findDefault()?.let { defaultNode ->
                        val shelleyGenesisFile = fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)
                            ?: throw IOException("Unable to read shelley genesis file!")
                        val shelley = shelleyShelleyGenesisAdapter.fromJson(shelleyGenesisFile.content)!!
                        val networkMagic = shelley.networkMagic ?: throw IOException("network magic not found!")
                        val defaultHost = hostRepository.findByIdOrNull(defaultNode.hostId)
                            ?: throw IOException("host for default node not found!")
                        val configFile = fileRepository.findByIdOrNull(defaultNode.configFileId)
                            ?: throw IOException("Unable to read config file")
                        val shelleyGenesisHash =
                            configAdapter.fromJson(configFile.content)!!.shelleyGenesisHash.hexToByteArray()

                        aSocket(ActorSelectorManager(coroutineContext)).tcp()
                            .connect(InetSocketAddress(defaultHost.hostname, defaultNode.port))
                            .use { socket ->
                                log.debug("ChainMonitor Socket connected")
                                val socketConnection = socket.connection()
                                val mux = Mux(socketConnection)
                                mux.execute(HandshakeProtocol(networkMagic))
                                mux.execute(
                                    ChainSyncProtocol(
                                        defaultHost,
                                        shelleyGenesisHash,
                                        getChainBlocksForSyncStart(chainRepository),
                                        chainRepository,
                                        isPooltool = false,
                                        pooltoolService = pooltoolService,
                                        pooltoolApiKey = "",
                                        poolId = "",
                                    ).also {
                                        // launch coroutine to save blocks
                                        it.initBlockReceiveHandler(this@launch)
                                    },
                                    BlockFetchProtocol(chainRepository, blockFetchRepository, ledgerRepository)
                                )
                            }
                    }
                } catch (e: Throwable) {
                    if (e !is CancellationException) {
                        log.error("ChainMonitor error", e)
                    } else {
                        isShuttingDown = true
                    }
                }
                if (!isShuttingDown) {
                    log.info("ChainMonitor Socket not connected. Wait 10 seconds to reconnect...")
                    delay(RECONNECT_DELAY_MS)
                }
            }
        }
        log.info("... ChainMonitor start complete.")
    }

    override fun stop() {
        job.cancelChildren()
        log.info("ChainMonitor stopped.")
    }

    companion object {
        const val RECONNECT_DELAY_MS = 10000L
    }
}