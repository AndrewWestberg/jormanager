package com.swiftmako.jormanager.monitors

import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.controllers.utils.SSHClientPool
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.ignoreExceptions
import com.swiftmako.jormanager.model.Config
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.monitors.utils.ChainRepositoryHelper.getChainBlocksForSyncStart
import com.swiftmako.jormanager.nodeclient.protocols.chainsync.ChainSyncProtocol
import com.swiftmako.jormanager.nodeclient.protocols.handshake.HandshakeProtocol
import com.swiftmako.jormanager.nodeclient.protocols.keepalive.KeepAliveProtocol
import com.swiftmako.jormanager.nodeclient.protocols.mux.Mux
import com.swiftmako.jormanager.repositories.ChainRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.services.PooltoolService
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.TypeOfService
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.connection
import java.io.IOException
import java.net.DatagramSocket
import java.net.ServerSocket
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

@Component("pooltoolMonitor")
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
@Lazy(false)
class PooltoolMonitor
    @Autowired
    constructor(
        private val chainRepository: ChainRepository,
        private val hostRepository: HostRepository,
        private val fileRepository: FileRepository,
        private val shelleyShelleyGenesisAdapter: JsonAdapter<GenesisShelley>,
        private val configAdapter: JsonAdapter<Config>,
        @param:Qualifier("nodesChannel") private val nodesChannel: MutableSharedFlow<Node>,
        private val pooltoolService: PooltoolService,
        @param:Value("\${pooltool.apikey}") private val pooltoolApiKey: String,
    ) : SmartLifecycle,
        CoroutineScope {
        private val log by lazy { LoggerFactory.getLogger("PooltoolMonitor") }

        private val job = SupervisorJob()
        override val coroutineContext: CoroutineContext =
            job + Dispatchers.IO +
                CoroutineExceptionHandler { _, throwable ->
                    if (throwable !is CancellationException) {
                        log.error("Uncaught coroutine exception!", throwable)
                    }
                }

        private val mutex = Mutex()
        private val monitorJobMap: MutableMap<Long, Job> = mutableMapOf()
        private var isShuttingDown = false

        override fun isAutoStartup() = "repair" != System.getProperty("jormanager.mode") && pooltoolApiKey.isNotBlank()

        override fun isRunning(): Boolean {
            val isRunning = job.isActive && !job.isCompleted && job.children.count() > 0
            log.info("PooltoolMonitor isRunning: $isRunning")
            return isRunning
        }

        override fun start() {
            log.info("Starting PooltoolMonitor...")

            monitorCoreNodes()
        }

        private fun monitorCoreNodes() {
            launch {
                nodesChannel.collect { node ->
                    mutex.withLock {
                        if (node.type != "core") {
                            // Don't monitor blocks unless it is a core node
                            log.info("Skip pooltool monitoring for relay node: ${node.name}")
                            return@collect
                        }
                        log.info("Start pooltool monitoring for core node: ${node.name}")

                        val existingJob = monitorJobMap[node.id]
                        if (existingJob?.isActive == true) {
                            // Respawn the monitoring job in case something changed.
                            existingJob.cancel()
                            monitorJobMap.remove(node.id)
                        }

                        // Start a new monitoring job for this node
                        hostRepository.findByIdOrNull(node.hostId)?.let { host ->
                            val monitoringJob =
                                launch {
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

        private lateinit var mux: Mux

        private suspend fun monitorBlocksLocal(
            host: Host,
            node: Node,
            port: Int? = null
        ) {
            coroutineScope {
                while (true) {
                    try {
                        val shelleyGenesisFile =
                            fileRepository.findByIdOrNull(node.genesisShelleyFileId)
                                ?: throw IOException("Unable to read shelley genesis file!")
                        val shelley = shelleyShelleyGenesisAdapter.fromJson(shelleyGenesisFile.content)!!
                        val networkMagic = shelley.networkMagic ?: throw IOException("network magic not found!")
                        val configFile =
                            fileRepository.findByIdOrNull(node.configFileId)
                                ?: throw IOException("Unable to read config file")
                        val shelleyGenesisHash =
                            configAdapter.fromJson(configFile.content)!!.shelleyGenesisHash.hexToByteArray()

                        val listen =
                            if (host.isRemote || node.listen == "0.0.0.0") {
                                "127.0.0.1"
                            } else {
                                node.listen
                            }

                        aSocket(ActorSelectorManager(coroutineContext))
                            .tcp()
                            .connect(InetSocketAddress(listen, port ?: node.port)) {
                                noDelay = true
                                keepAlive = true
                                lingerSeconds = 0
                                typeOfService = TypeOfService.IPTOS_LOWDELAY
                            }.use { socket ->
                                log.debug("ChainMonitor Socket connected")
                                val socketConnection = socket.connection()
                                mux = Mux(socketConnection)
                                mux.execute(HandshakeProtocol(networkMagic))
                                mux.execute(
                                    KeepAliveProtocol(),
                                    ChainSyncProtocol(
                                        host,
                                        shelleyGenesisHash,
                                        getChainBlocksForSyncStart(chainRepository),
                                        chainRepository,
                                        isPooltool = true,
                                        pooltoolService = pooltoolService,
                                        pooltoolApiKey = pooltoolApiKey,
                                        poolId = node.poolId!!,
                                    )
                                )
                            }
                    } catch (e: Throwable) {
                        log.error("Error monitoring chain for pooltool!", e)
                        mux.shutdownGracefully()
                    } finally {
                        this@coroutineScope.coroutineContext.cancelChildren()
                    }

                    delay(RECONNECT_DELAY_MS)
                }
            }
        }

        @Suppress("BlockingMethodInNonBlockingContext")
        private suspend fun monitorBlocksRemote(
            host: Host,
            node: Node
        ) {
            coroutineScope {
                val sshClientPool = SSHClientPool.getInstance(host)
                var retry = true
                while (retry) {
                    retry = false
                    var ssh: SSHClient? = null
                    var localPortForwarder: LocalPortForwarder? = null
                    try {
                        ssh = sshClientPool.borrow()
                        val localPort = availableLocalPort()
                        val listen =
                            if (node.listen == "0.0.0.0") {
                                "127.0.0.1"
                            } else {
                                node.listen
                            }
                        val params = Parameters("127.0.0.1", localPort, listen, node.port)
                        val serverSocket =
                            ServerSocket().apply {
                                reuseAddress = true
                            }
                        serverSocket.bind(java.net.InetSocketAddress(params.localHost, params.localPort))
                        localPortForwarder = ssh.newLocalPortForwarder(params, serverSocket)

                        object : Thread("port forward $localPort") {
                            override fun run() {
                                localPortForwarder.listen()
                            }
                        }.start()

                        monitorBlocksLocal(host, node, localPort)
                    } catch (e: IOException) {
                        log.error("IOException communicating with ${node.name}")
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
        }

        @OptIn(DelicateCoroutinesApi::class)
        override fun stop(callback: java.lang.Runnable) {
            isShuttingDown = true
            GlobalScope.launch {
                job.cancelChildren()
                job.cancelAndJoin()
                log.info("PooltoolMonitor stopped.")
                callback.run()
            }
        }

        override fun stop() {
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
                } catch (_: IOException) {
                }
            }
        }

        companion object {
            const val RECONNECT_DELAY_MS = 5000L
        }
    }
