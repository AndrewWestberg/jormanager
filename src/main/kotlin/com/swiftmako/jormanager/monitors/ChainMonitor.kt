package com.swiftmako.jormanager.monitors

import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.model.Config
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.monitors.utils.ChainRepositoryHelper.getChainBlocksForSyncStart
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.BlockFetchProtocol
import com.swiftmako.jormanager.nodeclient.protocols.chainsync.ChainSyncProtocol
import com.swiftmako.jormanager.nodeclient.protocols.handshake.HandshakeProtocol
import com.swiftmako.jormanager.nodeclient.protocols.keepalive.KeepAliveProtocol
import com.swiftmako.jormanager.nodeclient.protocols.mux.Mux
import com.swiftmako.jormanager.repositories.ChainRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.services.PooltoolService
import com.swiftmako.jormanager.utils.CardanoUtils
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.TypeOfService
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.connection
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

@Component("chainMonitor")
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
@Lazy(false)
class ChainMonitor @Autowired constructor(
    private val cardanoUtils: CardanoUtils,
    private val chainRepository: ChainRepository,
    private val hostRepository: HostRepository,
    private val nodeRepository: NodeRepository,
    private val fileRepository: FileRepository,
    private val shelleyGenesisAdapter: JsonAdapter<GenesisShelley>,
    private val configAdapter: JsonAdapter<Config>,
    private val pooltoolService: PooltoolService,
    private val ledgerDao: LedgerDao,
) : SmartLifecycle, CoroutineScope {

    private val log by lazy { KotlinLogging.logger("ChainMonitor") }

    private var isShuttingDown = false

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext =
        job + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            if (throwable !is CancellationException) {
                log.error(throwable) { "Uncaught coroutine exception!" }
            }
        }

    override fun isAutoStartup() = "repair" != System.getProperty("jormanager.mode")

    override fun isRunning(): Boolean {
        val isRunning = job.isActive && !job.isCompleted && job.children.count() > 0
        log.info { "ChainMonitor isRunning: $isRunning" }
        return isRunning
    }

    override fun start() {
        log.info { "Starting ChainMonitor..." }
        monitorChain()
    }

    private var mux: Mux? = null

    private fun monitorChain() {
        launch {
            while (!isShuttingDown) {
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
                        val shelleyGenesisHash =
                            configAdapter.fromJson(configFile.content)!!.shelleyGenesisHash.hexToByteArray()

                        aSocket(ActorSelectorManager(coroutineContext)).tcp()
                            .connect(InetSocketAddress(defaultHost.hostname, defaultNode.port)) {
                                noDelay = true
                                keepAlive = true
                                lingerSeconds = 0
                                typeOfService = TypeOfService.IPTOS_LOWDELAY
                            }
                            .use { socket ->
                                log.debug { "ChainMonitor Socket connected" }
                                val socketConnection = socket.connection()
                                mux = Mux(socketConnection)
                                mux?.execute(HandshakeProtocol(networkMagic))
                                mux?.execute(
                                    KeepAliveProtocol(),
                                    ChainSyncProtocol(
                                        defaultHost,
                                        shelleyGenesisHash,
                                        getChainBlocksForSyncStart(chainRepository),
                                        chainRepository,
                                        isPooltool = false,
                                        pooltoolService = pooltoolService,
                                        pooltoolApiKey = "",
                                        poolId = "",
                                    ),
                                    BlockFetchProtocol(
                                        cardanoUtils,
                                        chainRepository,
                                        ledgerDao,
                                    )
                                )
                            }
                    }
                } catch (e: Throwable) {
                    if (!isShuttingDown) {
                        log.error(e) { "ChainMonitor error" }
                    }
                    mux?.shutdownGracefully()
                    mux = null
                }
                if (!isShuttingDown) {
                    log.info { "ChainMonitor Socket not connected. Wait 10 seconds to reconnect..." }
                    delay(RECONNECT_DELAY_MS)
                } else {
                    log.info { "ChainMonitor Socket not connected. Shutting down..." }
                }
            }
        }
        log.info { "... ChainMonitor start complete." }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun stop(callback: Runnable) {
        isShuttingDown = true
        GlobalScope.launch {
            mux?.shutdownGracefully()
            delay(3000)
            job.cancelChildren()
            log.info { "ChainMonitor stopped." }
            callback.run()
        }
    }

    override fun stop() {
    }

    companion object {
        const val RECONNECT_DELAY_MS = 10000L
    }
}