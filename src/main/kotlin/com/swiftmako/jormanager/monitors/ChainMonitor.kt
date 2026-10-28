package com.swiftmako.jormanager.monitors

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.model.Config
import com.swiftmako.jormanager.model.GenesisByron
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.nodeclient.protocols.NetworkChainSession
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.GenesisLedgerDecoder
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.BlockFetchProtocol
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.LeiosFetchProtocol
import com.swiftmako.jormanager.nodeclient.protocols.chainsync.ChainSyncProtocol
import com.swiftmako.jormanager.nodeclient.protocols.handshake.HandshakeProtocol
import com.swiftmako.jormanager.nodeclient.protocols.keepalive.KeepAliveProtocol
import com.swiftmako.jormanager.nodeclient.protocols.mux.Mux
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.ChainRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.services.ChainIngestionCoordinator
import com.swiftmako.jormanager.services.PooltoolService
import com.swiftmako.jormanager.utils.Blake2b
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.TypeOfService
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.connection
import kotlinx.coroutines.*
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Lazy
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

@Component("chainMonitor")
@Lazy(false)
class ChainMonitor(
    private val chainRepository: ChainRepository,
    private val hostRepository: HostRepository,
    private val nodeRepository: NodeRepository,
    private val fileRepository: FileRepository,
    private val shelleyGenesisAdapter: JsonAdapter<GenesisShelley>,
    private val configAdapter: JsonAdapter<Config>,
    private val pooltoolService: PooltoolService,
    private val cardanoRepository: CardanoRepository,
    private val coordinator: ChainIngestionCoordinator,
    private val byronGenesisAdapter: JsonAdapter<GenesisByron>,
    private val moshi: Moshi,
) : SmartLifecycle {
    private val log = KotlinLogging.logger("ChainMonitor")
    private var root: Job? = null
    override fun isAutoStartup() = System.getProperty("jormanager.mode") != "repair"
    override fun isRunning() = root?.isActive == true

    override fun start() {
        if (isRunning) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        root = scope.coroutineContext[Job]
        scope.launch {
            val observers = mutableMapOf<String, Pair<Node, Job>>()
            try {
                while (isActive) {
                    try {
                        val nodes = nodeRepository.findAll().filterNot { it.isDeleted || it.isDraft }.sortedByDescending { it.isDefault }
                        val selected = linkedMapOf<String, Node>()
                        for (node in nodes) {
                            try { selected.putIfAbsent(cardanoRepository.networkKey(node), node) }
                            catch (e: Exception) { log.warn(e) { "Unable to identify network for node ${node.id}" } }
                        }
                        for ((key, pair) in observers.toMap()) {
                            if (selected[key] != pair.first || !pair.second.isActive) {
                                pair.second.cancelAndJoin()
                                observers.remove(key)
                            }
                        }
                        for ((key, node) in selected) {
                            if (key !in observers) observers[key] = node to launch { observe(key, node) }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { log.error(e) { "Unable to enumerate chain observers" } }
                    delay(10_000)
                }
            } finally {
                observers.values.forEach { it.second.cancel() }
            }
        }
    }

    private suspend fun observe(key: String, node: Node) {
        while (currentCoroutineContext().isActive) {
            var mux: Mux? = null
            var session: NetworkChainSession? = null
            val selector = ActorSelectorManager(currentCoroutineContext())
            try {
                val host = requireNotNull(hostRepository.findByIdOrNull(node.hostId))
                val genesis = requireNotNull(fileRepository.findByIdOrNull(node.genesisShelleyFileId))
                val shelley = requireNotNull(shelleyGenesisAdapter.fromJson(genesis.content))
                val config = requireNotNull(fileRepository.findByIdOrNull(node.configFileId))
                val byronFile = requireNotNull(fileRepository.findByIdOrNull(node.genesisByronFileId))
                val byron = requireNotNull(byronGenesisAdapter.fromJson(byronFile.content))
                val epochSlots = Math.multiplyExact(byron.protocolConsts.k, 10L)
                require(epochSlots > 0)
                val configValues = moshi.adapter(Map::class.java).lenient().fromJson(config.content)
                val byronGenesisHash = requireNotNull(configValues?.get("ByronGenesisHash") as? String) { "Byron genesis hash missing" }
                require(GenesisLedgerDecoder.verifyByronGenesisHash(byronFile.content, byronGenesisHash.hexToByteArray())) { "Byron genesis hash mismatch" }
                val byronMagic = when (configValues?.get("RequiresNetworkMagic")) {
                    "RequiresNoMagic" -> null
                    "RequiresMagic" -> requireNotNull(shelley.networkMagic)
                    else -> throw IllegalArgumentException("Byron network magic policy missing or invalid")
                }
                val genesisOutputs = GenesisLedgerDecoder.byron(byronFile.content, byronMagic) +
                    if (!shelley.networkId.equals("mainnet", ignoreCase = true) &&
                        (configValues?.get("TestShelleyHardForkAtEpoch") as? Number)?.toLong() == 0L
                    ) GenesisLedgerDecoder.shelley(genesis.content) else emptyList()
                val genesisHash = requireNotNull(configAdapter.fromJson(config.content)).shelleyGenesisHash.hexToByteArray()
                require(Blake2b.hash256(genesis.content.toByteArray(Charsets.UTF_8)).contentEquals(genesisHash)) { "Shelley genesis hash mismatch" }
                coordinator.registerNetwork(key, byronGenesisHash.lowercase(), genesisOutputs)
                aSocket(selector).tcp().connect(InetSocketAddress(host.hostname, node.port)) {
                    noDelay = true; keepAlive = true; lingerSeconds = 0; typeOfService = TypeOfService.IPTOS_LOWDELAY
                }.use { socket ->
                    mux = Mux(socket.connection())
                    mux!!.execute(HandshakeProtocol(requireNotNull(shelley.networkMagic)))
                    cardanoRepository.reestablishTarget(host, node)
                    session = NetworkChainSession(key, coordinator, node.isDefault, coordinator.beginSession(key), epochSlots)
                    val active = requireNotNull(session)
                    val leios = LeiosFetchProtocol(isCurrent = { generation -> generation == active.generation })
                    mux!!.execute(
                        KeepAliveProtocol(),
                        ChainSyncProtocol(host, genesisHash, emptyList(), chainRepository, false, pooltoolService, "", "",
                            active, node, cardanoRepository, coordinator.intersectionPoints(key)),
                        BlockFetchProtocol(active, leios),
                        leios,
                    )
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                log.warn(e) { "Chain observer failed for network $key" }
                try { session?.let { coordinator.boundaryFailure(key, e.message ?: "Chain connection failed", it.generation) } }
                catch (failure: Exception) { log.warn(failure) { "Unable to record chain failure" } }
            } finally {
                mux?.shutdownGracefully()
                session?.close()
                selector.close()
            }
            delay(10_000)
        }
    }

    override fun stop(callback: Runnable) {
        val job = root
        root = null
        if (job == null) callback.run() else { job.invokeOnCompletion { callback.run() }; job.cancel() }
    }
    override fun stop() { root?.cancel(); root = null }
}
