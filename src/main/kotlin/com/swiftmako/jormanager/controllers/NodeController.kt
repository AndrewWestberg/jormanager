package com.swiftmako.jormanager.controllers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.CreateNodeRequest
import com.swiftmako.jormanager.model.GenesisByron
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.model.RotateKesRequest
import com.swiftmako.jormanager.model.UpdateColorRequest
import com.swiftmako.jormanager.model.key.Key
import com.swiftmako.jormanager.model.metadata.pool.ExtendedMetadata
import com.swiftmako.jormanager.model.metadata.pool.Metadata
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.services.CardanoCapabilities
import com.swiftmako.jormanager.services.NetworkBundleValidator
import com.swiftmako.jormanager.services.ReviewedNetworkReconfiguration
import com.swiftmako.jormanager.repositories.NodeConfigurationSnapshotRepository
import com.swiftmako.jormanager.entities.NodeConfigurationSnapshot
import com.swiftmako.jormanager.services.MetadataService
import com.swiftmako.jormanager.utils.CardanoNetworkEpochs
import com.swiftmako.jormanager.utils.Blake2b
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_SINGLETON
import org.springframework.context.annotation.Scope
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import retrofit2.Retrofit

@Controller
@Scope(SCOPE_SINGLETON)
class NodeController
    @Autowired
    constructor(
        private val nodeRepository: NodeRepository,
        private val hostRepository: HostRepository,
        private val fileRepository: FileRepository,
        private val walletUtils: WalletUtils,
        private val webSocketTemplate: SimpMessagingTemplate,
        @param:Qualifier("nodesChannel") private val nodesChannel: MutableSharedFlow<Node>,
        private val retrofit: Retrofit,
        private val okHttpClient: OkHttpClient,
        private val shelleyGenesisAdapter: JsonAdapter<GenesisShelley>,
        private val byronGenesisAdapter: JsonAdapter<GenesisByron>,
        private val extendedMetadataAdapter: JsonAdapter<ExtendedMetadata>,
        private val metadataAdapter: JsonAdapter<Metadata>,
        private val keyFileJsonAdapter: JsonAdapter<Key>,
        private val cardanoRepository: CardanoRepository,
        private val cardanoCapabilities: CardanoCapabilities,
        private val networkBundleValidator: NetworkBundleValidator,
        private val nodeConfigurationSnapshotRepository: NodeConfigurationSnapshotRepository,
        private val reviewedNetworkReconfiguration: ReviewedNetworkReconfiguration,
        // @param:Value("\${jormanager.era}") private val eraString: String,
    ) {
        private val log by lazy { LoggerFactory.getLogger("NodeController") }
        private val objectMapper by lazy {
            ObjectMapper().apply {
                enable(SerializationFeature.INDENT_OUTPUT)
            }
        }
        private val rationaleHttpClient by lazy {
            okHttpClient
                .newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }
        private val rationaleUrlPattern =
            Regex("""https://cardanostakehouse\.com/(?:[A-Za-z0-9._~/-]|%[0-9A-Fa-f]{2})+""")

        @MessageMapping("/nodes")
        @SendTo("/topic/messages")
        fun getNodes(): SocketResponse<List<Node>> {
            val nodes = nodeRepository.findAll(Sort.by(Sort.Direction.ASC, "name")).filter { !it.isDeleted }
            return SocketResponse.Success(type = "nodes", data = nodes)
        }
        data class ProvisioningPreviewRequest(
            val hostId: Long, val bundleId: String? = null, val expectedResetId: String? = null,
            val type: String, val port: Int, val prometheusListen: String, val parentId: Long? = null
        )

        data class ProvisioningPreview(
            val hostId: Long, val observerNodeId: Long, val networkKey: String,
            val bundleId: String?, val resetId: String?, val prometheusPort: Int,
            val tracingPort: Int, val configSha256: String
        )

        @MessageMapping("/node/provisioning-preview")
        @SendTo("/topic/messages")
        fun previewProvisioning(request: ProvisioningPreviewRequest): SocketResponse<ProvisioningPreview> =
            try {
                require(request.type in setOf(NODE_TYPE_RELAY, NODE_TYPE_CORE, NODE_TYPE_POOL)) { "Invalid node role" }
                val bundle = request.bundleId?.let {
                    networkBundleValidator.resolve(it, requireNotNull(request.expectedResetId))
                }
                require(bundle != null || request.expectedResetId == null) { "Reset requires a bundle" }
                val observer = nodeRepository.findDefault() ?: throw IOException("A matching connected observer is required")
                require(observer.configurationResetId == bundle?.bundle?.resetId) { "Observer is on a different network reset" }
                val host = hostRepository.findByIdOrNull(request.hostId) ?: throw IOException("Target host not found")
                val connection = HostConnection(host)
                val parent = if (request.type == NODE_TYPE_POOL) {
                    val node = request.parentId?.let { nodeRepository.findByIdOrNull(it) }
                        ?: throw IOException("Parent core node not found")
                    require(!node.isDraft && !node.isDeleted && node.type == NODE_TYPE_CORE && node.hostId == request.hostId &&
                        node.configurationBundleId == bundle?.bundle?.bundleId && node.configurationResetId == bundle?.bundle?.resetId &&
                        cardanoRepository.networkKey(node) == cardanoRepository.networkKey(observer)) {
                        "Pool parent must be an active core on the selected host and bundle"
                    }
                    node
                } else null
                if (parent == null) require(request.port in 1..65535 && !isPortUsed(connection, request.port)) {
                    "The selected node port is unavailable"
                }
                val promPort = parent?.promPort ?: allocatePrometheusPort(request.hostId, connection)
                if (parent == null) require(promPort != request.port) { "Prometheus and node ports conflict" }
                val tracingPort = parent?.tracingPort ?: allocateTracingPort(promPort) { it == request.port || isPortUsed(connection, it) }
                val config = if (parent != null) connection.commandReadFile("${host.nodeHomePath}/${parent.name}/config.json")
                    else renderManagedConfig(
                        bundle?.assets?.getValue("config.json")?.toString(Charsets.UTF_8)
                            ?: (fileRepository.findByIdOrNull(observer.configFileId)?.content
                                ?: throw IOException("Observer config not found")),
                        request.type, if (request.type == NODE_TYPE_RELAY) "4" else "2",
                        request.type == NODE_TYPE_RELAY, request.prometheusListen, promPort)
                val digest = MessageDigest.getInstance("SHA-256").digest(config.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                SocketResponse.Success(type = "node_provisioning_preview", data = ProvisioningPreview(
                    request.hostId, requireNotNull(observer.id), cardanoRepository.networkKey(observer),
                    bundle?.bundle?.bundleId, bundle?.bundle?.resetId, promPort, tracingPort, digest
                ))
            } catch (e: Exception) {
                SocketResponse.Error(type = "node_provisioning_preview", exception = e)
            }


        @MessageMapping("/restartnode")
        @SendTo("/topic/messages")
        @Synchronized
        fun restartNode(nodeId: Long): SocketResponse<String> =
            try {
                val node = nodeRepository.findByIdOrNull(nodeId) ?: throw IllegalArgumentException("Node not found!")
                require(!node.isDraft) { "A draft node has no running service" }
                val host = hostRepository.findByIdOrNull(node.hostId) ?: throw IllegalArgumentException("Host not found!")
                val hostConnection = HostConnection(host)
                val processId =
                    if (hostConnection.hasSystemd) {
                        hostConnection
                            .command("systemctl show --property MainPID --value ${node.name}-node.service")
                            .trim()
                    } else {
                        throw IllegalAccessError("This node does not support automatic restart.")
                    }
                hostConnection.command("kill -INT $processId")
                try {
                    Thread.sleep(1000)
                    hostConnection.command("kill -TERM $processId")
                    Thread.sleep(1000)
                    hostConnection.command("kill -KILL $processId")
                } catch (t: Throwable) {
                    log.debug("kill command error!", t)
                }
                SocketResponse.Success(type = "restartnode", data = "triggered")
            } catch (e: Throwable) {
                log.error("Error Restarting Node!", e)
                SocketResponse.Error(type = "restartnode", exception = e)
            }

        @MessageMapping("/updatenodecolor")
        @Transactional
        fun updateNodeColor(request: UpdateColorRequest) {
            try {
                val node = nodeRepository.findByIdOrNull(request.id) ?: throw IllegalArgumentException("Node not found!")
                nodeRepository.save(node.copy(color = request.color))

                // send all to the client for ui updates
                val nodes = nodeRepository.findAll(Sort.by(Sort.Direction.ASC, "name")).filter { !it.isDeleted }
                webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success(type = "nodes", data = nodes))

                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Success("updatenodecolor", "Updated successfully!")
                )
            } catch (e: Throwable) {
                log.error("Error Updating Node color!", e)
                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Error(type = "updatenodecolor", exception = e)
                )
                // rethrow so db transaction is rolled back
                throw RuntimeException(e)
            }
        }

        @MessageMapping("/createnode")
        @Transactional
        fun createNode(input: CreateNodeRequest) {
            try {
                require(input.type == NODE_TYPE_RELAY) { "Producer creation requires a reviewed pool draft" }
                if (!walletUtils.isValidSpendingPassword(input.spendingPassword)) {
                    throw IllegalArgumentException("Invalid spending password!")
                }
                if (!input.enableTracingListener) {
                    throw IllegalArgumentException("Tracing listener must be enabled!")
                }
                input.reconfigurationId?.let { reviewedNetworkReconfiguration.consume(it, input) }
                require(input.bundleId != null || input.expectedResetId == null) { "A reset requires a selected bundle" }
                val selectedBundle = input.bundleId?.let { bundleId ->
                    require(input.genesisByronFileId == 0L && input.genesisShelleyFileId == 0L &&
                        input.genesisAlonzoFileId == 0L && input.genesisConwayFileId == 0L) {
                        "Individual genesis selections cannot be mixed with a bundle"
                    }
                    networkBundleValidator.resolve(bundleId, requireNotNull(input.expectedResetId) { "The expected reset is required" })
                }
                require(input.name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*"))) { "Invalid node name" }
                val request = if (selectedBundle == null) input else {
                    require(input.type == NODE_TYPE_RELAY) { "Reviewed BLS producer provisioning is unavailable" }
                    val observer = nodeRepository.findDefault() ?: throw IOException("A matching connected observer is required")
                    require(observer.configurationResetId == selectedBundle.bundle.resetId) { "Observer is on a different network reset" }
                    val prefix = "snapshot-${UUID.randomUUID()}"
                    fun store(name: String): Long = fileRepository.save(com.swiftmako.jormanager.entities.File(
                        name = "$prefix-$name", content = selectedBundle.assets.getValue(name).toString(Charsets.UTF_8)
                    )).id!!
                    val byron = store("byron-genesis.json")
                    val shelley = store("shelley-genesis.json")
                    val alonzo = store("alonzo-genesis.json")
                    val conway = store("conway-genesis.json")
                    store("dijkstra-genesis.json")
                    store("config.json")
                    store("topology.json")
                    store("peer-snapshot.json")
                    input.copy(genesisByronFileId = byron, genesisShelleyFileId = shelley,
                        genesisAlonzoFileId = alonzo, genesisConwayFileId = conway)
                }
                if (request.type == NODE_TYPE_CORE || request.type == NODE_TYPE_POOL) {
                    val observer = nodeRepository.findDefault() ?: throw IOException("No default node!")
                    if (selectedBundle == null) {
                        require(observer.configurationResetId == null) { "Select the observer's configuration bundle" }
                        val requested = listOf(request.genesisByronFileId, request.genesisShelleyFileId,
                            request.genesisAlonzoFileId, request.genesisConwayFileId)
                        val observed = listOf(observer.genesisByronFileId, observer.genesisShelleyFileId,
                            observer.genesisAlonzoFileId, observer.genesisConwayFileId)
                        requested.zip(observed).forEach { (requestedId, observedId) ->
                            val requestedFile = fileRepository.findByIdOrNull(requestedId)
                                ?: throw IOException("Selected genesis file not found")
                            val observedFile = fileRepository.findByIdOrNull(observedId)
                                ?: throw IOException("Observer genesis file not found")
                            require(requestedFile.content == observedFile.content) { "Selected genesis differs from observer network" }
                        }
                    }
                    if (request.type == NODE_TYPE_POOL) {
                        val parent = request.parentId?.let { nodeRepository.findByIdOrNull(it) }
                            ?: throw IOException("Parent core node not found")
                        require(!parent.isDeleted && parent.type == NODE_TYPE_CORE && parent.hostId == request.hostId) {
                            "Pool parent must be an active core on the selected host"
                        }
                        require(cardanoRepository.networkKey(parent) == cardanoRepository.networkKey(observer)) {
                            "Pool parent and observer are on different networks"
                        }
                        require(parent.configurationBundleId == observer.configurationBundleId) {
                            "Pool parent and observer use different configuration bundles"
                        }
                    }
                }
                log.debug(request.toString())
                val host =
                    hostRepository.findByIdOrNull(request.hostId)
                        ?: throw IOException("Invalid HostId: ${request.hostId}")
                val tracingListen = request.tracingListen ?: if (host.isRemote) "0.0.0.0" else "127.0.0.1"
                val hostConnection = HostConnection(host)
                // validate sudo password right away
                hostConnection.sudoCommand("pwd", request.sudoPassword)

                when (request.type) {
                    NODE_TYPE_RELAY -> {
                        val nodeFolder = "${host.nodeHomePath}${File.separator}${request.name}"
                        if (selectedBundle != null && hostConnection.commandFileExists(nodeFolder)) {
                            throw IOException("Node directory already exists; a clean directory and database are required")
                        }
                        if (selectedBundle != null) {
                            require(request.port in 1..65535 && !isPortUsed(hostConnection, request.port)) {
                                "The selected node port is unavailable"
                            }
                        }
                        val allocatedPromPort = allocatePrometheusPort(request.hostId, hostConnection)
                        require(allocatedPromPort != request.port) { "Prometheus and node ports conflict" }
                        val tracingPort = allocateTracingPort(allocatedPromPort) { port ->
                            port == request.port || isPortUsed(hostConnection, port)
                        }
                        if (selectedBundle != null) {
                            val observer = nodeRepository.findDefault() ?: throw IOException("A matching connected observer is required")
                            val observerHost = hostRepository.findByIdOrNull(observer.hostId) ?: throw IOException("Observer host not found")
                            val effectiveConfig = renderManagedConfig(
                                selectedBundle.assets.getValue("config.json").toString(Charsets.UTF_8),
                                request.type, "4", true, request.prometheusListen, allocatedPromPort
                            )
                            val configHash = MessageDigest.getInstance("SHA-256").digest(effectiveConfig.toByteArray(Charsets.UTF_8))
                                .joinToString("") { "%02x".format(it.toInt() and 255) }
                            cardanoCapabilities.requireHostOperation(
                                host, observerHost, observer, cardanoRepository.networkKey(observer), configHash, "node.create"
                            )
                        }
                        createNodeFolders(hostConnection, nodeFolder)
                        val genesisByronFile =
                            createGenesisFile("byron", request.genesisByronFileId, hostConnection, nodeFolder)
                        createGenesisFile("shelley", request.genesisShelleyFileId, hostConnection, nodeFolder)
                        createGenesisFile("alonzo", request.genesisAlonzoFileId, hostConnection, nodeFolder)
                        createGenesisFile("conway", request.genesisConwayFileId, hostConnection, nodeFolder)
                        val dijkstraFile = if (selectedBundle != null) {
                            val prefix = genesisByronFile.name.substringBeforeLast("-byron")
                            val file = fileRepository.findByName("$prefix-dijkstra-genesis.json")
                                ?: throw IOException("Dijkstra genesis snapshot missing")
                            createGenesisFile("dijkstra", file.id!!, hostConnection, nodeFolder)
                        } else null
                        if (selectedBundle == null) {
                            createTopologyFile(genesisByronFile.name, hostConnection, nodeFolder)
                        } else {
                            val topology = selectedBundle.assets.getValue("topology.json").toString(Charsets.UTF_8)
                                .replace("${selectedBundle.bundle.catalogPrefix}-peer-snapshot.json", "peer-snapshot.json")
                                .replace("musashi-peer-snapshot.json", "peer-snapshot.json")
                            hostConnection.commandWriteFile("$nodeFolder/topology.json", topology)
                            hostConnection.commandWriteFile("$nodeFolder/peer-snapshot.json",
                                selectedBundle.assets.getValue("peer-snapshot.json").toString(Charsets.UTF_8))
                        }
                        val (configFileId, promPort) =
                            createConfigFile(
                                genesisByronFileName = genesisByronFile.name,
                                nodeType = request.type,
                                promPort = allocatedPromPort,
                                prometheusListen = request.prometheusListen,
                                hostConnection = hostConnection,
                                nodeFolder = nodeFolder,
                                maxConcurrencyDeadline = "4",
                                peerSharing = true,
                            )
                        if (selectedBundle != null) {
                            listOf("byron-genesis.json", "shelley-genesis.json", "alonzo-genesis.json",
                                "conway-genesis.json", "dijkstra-genesis.json", "peer-snapshot.json").forEach { name ->
                                val expected = selectedBundle.assets.getValue(name).toString(Charsets.UTF_8)
                                require(hostConnection.commandReadFile("$nodeFolder/$name") == expected) {
                                    "Installed $name does not match its validated snapshot"
                                }
                            }
                            val expectedConfig = renderManagedConfig(
                                selectedBundle.assets.getValue("config.json").toString(Charsets.UTF_8),
                                request.type, "4", true, request.prometheusListen, promPort
                            )
                            require(hostConnection.commandReadFile("$nodeFolder/config.json") == expectedConfig) {
                                "Installed config differs from the reviewed configuration"
                            }
                            val expectedTopology = selectedBundle.assets.getValue("topology.json").toString(Charsets.UTF_8)
                                .replace("${selectedBundle.bundle.catalogPrefix}-peer-snapshot.json", "peer-snapshot.json")
                                .replace("musashi-peer-snapshot.json", "peer-snapshot.json")
                            require(hostConnection.commandReadFile("$nodeFolder/topology.json") == expectedTopology) {
                                "Installed topology differs from the reviewed configuration"
                            }
                        }
                        createEnvFile(
                            hostConnection,
                            request.type,
                            request.name,
                            nodeFolder,
                            request.listen,
                            request.port,
                            tracingListen,
                            tracingPort,
                        )
                        createSystemdFile(
                            request = request,
                            startupNodeType = request.type,
                            host = host,
                            hostConnection = hostConnection,
                            name = request.name,
                            processorThreads = request.processorThreads,
                            tracingHost = tracingListen,
                            tracingPort = tracingPort,
                        )
                        createManualStartupScripts(
                            request = request,
                            startupNodeType = request.type,
                            host = host,
                            hostConnection = hostConnection,
                            tracingHost = tracingListen,
                            tracingPort = tracingPort,
                        )

                        if (request.isDefault) {
                            val oldDefault = nodeRepository.findDefault()
                            oldDefault?.let {
                                // Make old default no longer the default
                                nodeRepository.save(oldDefault.copy(isDefault = false))
                            }
                        }

                        val node =
                            Node(
                                hostId = host.id!!,
                                color = request.color,
                                type = request.type,
                                processorThreads = request.processorThreads,
                                name = request.name,
                                listen = request.listen,
                                port = request.port,
                                promPort = promPort,
                                tracingHost = tracingListen,
                                genesisByronFileId = request.genesisByronFileId,
                                genesisShelleyFileId = request.genesisShelleyFileId,
                                genesisAlonzoFileId = request.genesisAlonzoFileId,
                                genesisConwayFileId = request.genesisConwayFileId,
                                configFileId = configFileId,
                                isDefault = request.isDefault,
                                tracingPort = tracingPort,
                                genesisDijkstraFileId = dijkstraFile?.id,
                                configurationBundleId = selectedBundle?.bundle?.bundleId,
                                configurationResetId = selectedBundle?.bundle?.resetId,
                            )
                        val savedNode = nodeRepository.save(node)
                        if (selectedBundle != null) {
                            fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
                                .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
                            val effectiveConfig = hostConnection.commandReadFile("$nodeFolder/config.json")
                            val effectiveTopology = hostConnection.commandReadFile("$nodeFolder/topology.json")
                            nodeConfigurationSnapshotRepository.save(NodeConfigurationSnapshot(
                                id = UUID.randomUUID().toString(), nodeId = savedNode.id!!,
                                bundleId = selectedBundle.bundle.bundleId, role = request.type,
                                assetsJson = selectedBundle.bundle.assetsJson,
                                effectiveConfigDigest = digest(effectiveConfig),
                                effectiveTopologyDigest = digest(effectiveTopology),
                                approvedOverrides = objectMapper.writeValueAsString(mapOf(
                                    "prometheusListen" to request.prometheusListen, "prometheusPort" to promPort,
                                    "peerSharing" to true, "maxConcurrencyDeadline" to 4,
                                    "listen" to request.listen, "port" to request.port
                                )), publicFingerprints = objectMapper.writeValueAsString(
                                    selectedBundle.assets.mapValues { (_, bytes) ->
                                        MessageDigest.getInstance("SHA-256").digest(bytes)
                                            .joinToString("") { "%02x".format(it.toInt() and 255) }
                                    }
                                ), installationObservation = null
                            ))
                        }

                        // send it to the channel for monitoring
                        nodesChannel.tryEmit(savedNode)

                        // send all to the client for ui updates
                        val nodes = nodeRepository.findAll(Sort.by(Sort.Direction.ASC, "name")).filter { !it.isDeleted }
                        webSocketTemplate.convertAndSend(
                            "/topic/messages",
                            SocketResponse.Success(type = "nodes", data = nodes)
                        )
                    }


                    else -> {
                        throw IOException("Invalid node type: ${request.type}")
                    }
                }

                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Success("createnode", "${request.name} created successfully!")
                )
            } catch (e: Throwable) {
                log.error("Error Creating Node!", e)
                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Error(type = "createnode", exception = e)
                )
                // rethrow so db transaction is rolled back
                throw RuntimeException(e)
            }
        }


        @MessageMapping("/rotatekes")
        @Transactional
        fun rotateKes(request: RotateKesRequest) {
            try {
                if (!walletUtils.isValidSpendingPassword(request.spendingPassword)) {
                    throw IllegalArgumentException("Invalid spending password!")
                }
                val defaultNode = nodeRepository.findDefault() ?: throw IOException("Default node not found!")
                val genesisFile =
                    fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)
                        ?: throw IOException("Genesis shelley not found!")
                val genesisShelley = shelleyGenesisAdapter.fromJson(genesisFile.content)!!
                val byronToShelleyEpochs = CardanoNetworkEpochs.byronToShelleyEpochs(genesisShelley)
                val magicString =
                    if (genesisShelley.networkId.equals("testnet", ignoreCase = true)) {
                        "--testnet-magic ${genesisShelley.networkMagic}"
                    } else {
                        "--mainnet"
                    }

                val genesisByronFile =
                    fileRepository.findByIdOrNull((defaultNode.genesisByronFileId))
                        ?: throw IOException("Genesis byron not found!")
                val genesisByron = byronGenesisAdapter.fromJson(genesisByronFile.content)!!

                val defaultHost =
                    hostRepository.findByIdOrNull(defaultNode.hostId)
                        ?: throw IOException("Default host not found!")
                val defaultHostConnection = HostConnection(defaultHost, defaultNode)
                val node = nodeRepository.findByIdOrNull(request.id) ?: throw IOException("Invalid node id: ${request.id}")
                val host = hostRepository.findByIdOrNull(node.hostId) ?: throw IOException("Host not found!")
                val hostConnection = HostConnection(host, node)
                try {
                    hostConnection.bashCommand("echo 'SSH connectivity verified'", 5L)
                } catch (e: Exception) {
                    throw IOException("Pre-flight check failed: Remote host is unreachable.", e)
                }
                val socketPath = "--socket-path ${defaultHost.nodeHomePath}/${defaultNode.name}/db/socket"
                try {
                    val era = cardanoRepository.getEra(defaultHost, defaultNode, magicString, socketPath)
                    // fetch cold keys and vrf
                    val coreSKeyContent =
                        fileRepository.findByIdOrNull(node.coreSKeyId)?.let { coreSKey ->
                            walletUtils.getSKeyContent(coreSKey, request.spendingPassword)
                        } ?: throw IOException("Could not find core skey!")
                    defaultHostConnection.commandWriteFile("/tmp/core.node.skey", coreSKeyContent)
                    val coreCounter =
                        fileRepository.findByIdOrNull(node.coreCounterId)
                            ?: throw IOException("Could not find core counter!")
                    defaultHostConnection.commandWriteFile(
                        "/tmp/core.node.counter",
                        coreCounter.content
                    )
                    val vrfSKeyContent =
                        fileRepository.findByIdOrNull(node.vrfSKeyId)?.let { vrfSKey ->
                            walletUtils.getSKeyContent(vrfSKey, request.spendingPassword)
                        } ?: throw IOException("Could not find vrf skey!")

                    // generate new KES keys
                    defaultHostConnection.command(
                        "${defaultHost.cardanoCliPath} $era node key-gen-KES --verification-key-file /tmp/core.kes.vkey --signing-key-file /tmp/core.kes.skey"
                    )
                    val kesSKeyContent = defaultHostConnection.commandReadFile("/tmp/core.kes.skey")
                    val kesVKeyContent = defaultHostConnection.commandReadFile("/tmp/core.kes.vkey")
                    val kesSKey =
                        com.swiftmako.jormanager.entities.File(
                            name = "${node.name}.kes.skey",
                            content =
                                walletUtils.encryptSKeyContent(
                                    kesSKeyContent,
                                    request.spendingPassword
                                )
                        )
                    fileRepository.findByIdOrNull(node.kesSKeyId)?.let { oldKesSkey ->
                        fileRepository.save(oldKesSkey.copy(name = "${oldKesSkey.name}-${System.currentTimeMillis()}"))
                    }
                    val kesVKey =
                        com.swiftmako.jormanager.entities.File(
                            name = "${node.name}.kes.vkey",
                            content = kesVKeyContent
                        )
                    fileRepository.findByIdOrNull(node.kesVKeyId)?.let { oldKesVkey ->
                        fileRepository.save(oldKesVkey.copy(name = "${oldKesVkey.name}-${System.currentTimeMillis()}"))
                    }
                    val kesSKeyId = fileRepository.save(kesSKey).id!!
                    val kesVKeyId = fileRepository.save(kesVKey).id!!

                    // calculate current kes period
                    val slotLength = genesisShelley.slotLength
                    val epochLength = genesisShelley.epochLength
                    val slotsPerKESPeriod = genesisShelley.slotsPerKESPeriod
                    val startTimeByron = genesisByron.startTime
                    val startTimeGenesis = genesisShelley.systemStart

                    val startTimeSec = defaultHostConnection.command("date --date=$startTimeGenesis +%s").trim().toLong()
                    val transTimeEnd = startTimeSec + (byronToShelleyEpochs * epochLength)
                    val byronSlots = (startTimeSec - startTimeByron) / 20L
                    val transSlots = (byronToShelleyEpochs * epochLength) / 20L

                    val currentTimeSec = defaultHostConnection.command("date -u +%s").trim().toLong()

                    val currentSlot =
                        if (currentTimeSec < transTimeEnd) {
                            // in transition phase between shelley genesis start and transition end
                            (byronSlots + (currentTimeSec - startTimeSec) / 20L)
                        } else {
                            // after transition phase
                            (byronSlots + transSlots + ((currentTimeSec - transTimeEnd) / slotLength))
                        }
                    var currentKESPeriod = ((currentSlot - byronSlots) / (slotsPerKESPeriod * slotLength))
                    if (currentKESPeriod < 0L) {
                        currentKESPeriod = 0L
                    }

                    val maxKESEvolutions = genesisShelley.maxKESEvolutions
                    val expiresKESPeriod = currentKESPeriod + maxKESEvolutions
                    val kesExpireTimeSec = (currentTimeSec + (slotLength * maxKESEvolutions * slotsPerKESPeriod))
                    val kesExpireDate = defaultHostConnection.command("date --date=@$kesExpireTimeSec").trim()
                    log.warn(
                        "RotatingKES: currentKESPeriod: $currentKESPeriod, expiresKESPeriod: $expiresKESPeriod, expireDate: $kesExpireDate"
                    )

                    defaultHostConnection.command(
                        "${defaultHost.cardanoCliPath} $era node issue-op-cert --hot-kes-verification-key-file /tmp/core.kes.vkey --cold-signing-key-file /tmp/core.node.skey --operational-certificate-issue-counter /tmp/core.node.counter --kes-period $currentKESPeriod --out-file /tmp/core.node.opcert"
                    )
                    val opcertContent = defaultHostConnection.commandReadFile("/tmp/core.node.opcert")
                    val opcertFile =
                        com.swiftmako.jormanager.entities.File(
                            name = "${node.name}.node.opcert",
                            content = opcertContent
                        )
                    val opcertId = fileRepository.save(opcertFile).id!!
                    fileRepository.findByIdOrNull(node.opcertId)?.let { oldOpcertFile ->
                        fileRepository.save(oldOpcertFile.copy(name = "${oldOpcertFile.name}-${System.currentTimeMillis()}"))
                    }

                    val coreCounterContent = defaultHostConnection.commandReadFile("/tmp/core.node.counter")
                    fileRepository.save(coreCounter.copy(content = coreCounterContent))

                    nodeRepository.save(
                        node.copy(
                            kesSKeyId = kesSKeyId,
                            kesVKeyId = kesVKeyId,
                            opcertId = opcertId,
                            kesExpireDate = kesExpireDate,
                            kesExpireTimeSec = kesExpireTimeSec
                        )
                    )

                    // upload kes and opcert
                    // 13. Create and Save node information
                    val (coreNode, nodeFolder, isUpdateBulkCredentials) =
                        if (node.type == "core") {
                            val nodeFolder = "${host.nodeHomePath}${File.separator}${node.name}"
                            createKesVrfOpcert(
                                node.name,
                                kesSKeyContent,
                                vrfSKeyContent,
                                opcertContent,
                                hostConnection,
                                nodeFolder
                            )
                            Triple(node, nodeFolder, nodeRepository.findByParentId(node.id!!).isNotEmpty())
                        } else {
                            // node.type == "pool"
                            val coreNode =
                                nodeRepository.findByIdOrNull(node.parentId!!)
                                    ?: throw IOException("pool does not have a parent!")
                            val nodeFolder = "${host.nodeHomePath}${File.separator}${coreNode.name}"
                            createKesVrfOpcert(
                                node.name,
                                kesSKeyContent,
                                vrfSKeyContent,
                                opcertContent,
                                hostConnection,
                                nodeFolder
                            )
                            Triple(coreNode, nodeFolder, true)
                        }

                    if (isUpdateBulkCredentials) {
                        val credentials = mutableListOf<MutableList<Key>>()
                        // core node's pool
                        credentials.add(
                            mutableListOf(
                                keyFileJsonAdapter.fromJson(fileRepository.findByIdOrNull(coreNode.opcertId)!!.content)!!,
                                keyFileJsonAdapter.fromJson(
                                    walletUtils.getSKeyContent(
                                        fileRepository.findByIdOrNull(
                                            coreNode.vrfSKeyId
                                        )!!,
                                        request.spendingPassword
                                    )
                                )!!,
                                keyFileJsonAdapter.fromJson(
                                    walletUtils.getSKeyContent(
                                        fileRepository.findByIdOrNull(
                                            coreNode.kesSKeyId
                                        )!!,
                                        request.spendingPassword
                                    )
                                )!!,
                            )
                        )
                        // all child pools (including the one being updated
                        nodeRepository.findByParentId(coreNode.id!!).forEach { pool ->
                            credentials.add(
                                mutableListOf(
                                    keyFileJsonAdapter.fromJson(fileRepository.findByIdOrNull(pool.opcertId)!!.content)!!,
                                    keyFileJsonAdapter.fromJson(
                                        walletUtils.getSKeyContent(
                                            fileRepository.findByIdOrNull(
                                                pool.vrfSKeyId
                                            )!!,
                                            request.spendingPassword
                                        )
                                    )!!,
                                    keyFileJsonAdapter.fromJson(
                                        walletUtils.getSKeyContent(
                                            fileRepository.findByIdOrNull(
                                                pool.kesSKeyId
                                            )!!,
                                            request.spendingPassword
                                        )
                                    )!!,
                                )
                            )
                        }
                        createBulkCredentials(hostConnection, nodeFolder, credentials)
                    }
                } finally {
                    // cleanup
                    defaultHostConnection.command(
                        "rm -f /tmp/core.node.skey /tmp/core.node.counter /tmp/core.kes.skey /tmp/core.kes.vkey /tmp/core.node.opcert"
                    )
                }

                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Success("rotatekes", "KES rotated successfully, restart node to activate!")
                )
            } catch (e: Throwable) {
                log.error("Error Rotating KES!", e)
                webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Error(type = "rotatekes", exception = e))
                // rethrow so db transaction is rolled back
                throw RuntimeException(e)
            }
        }

        @MessageMapping("/getmetadata")
        fun getMetadata(nodeId: Long) {
            try {
                val node = nodeRepository.findByIdOrNull(nodeId) ?: throw IOException("Node id not found!")
                val metadataRequest =
                    Request
                        .Builder()
                        .get()
                        .cacheControl(CacheControl.FORCE_NETWORK)
                        .url(node.metadataUrl!!)
                        .build()
                val metadataResponse = okHttpClient.newCall(metadataRequest).execute()
                if (metadataResponse.isSuccessful) {
                    val metadata =
                        requireNotNull(metadataResponse.body).source().use { source -> metadataAdapter.fromJson(source) }
                    val extendedMetadataRequest =
                        Request
                            .Builder()
                            .get()
                            .cacheControl(CacheControl.FORCE_NETWORK)
                            .url(node.extendedMetadataUrl!!)
                            .build()
                    val extendedMetadataResponse = okHttpClient.newCall(extendedMetadataRequest).execute()
                    if (extendedMetadataResponse.isSuccessful) {
                        val extendedMetadata =
                            requireNotNull(extendedMetadataResponse.body)
                                .source()
                                .use { source -> extendedMetadataAdapter.fromJson(source) }
                        webSocketTemplate.convertAndSend(
                            "/topic/messages",
                            SocketResponse.Success(
                                type = "getmetadata",
                                data =
                                    mapOf(
                                        "metadata" to metadata,
                                        "extendedMetadata" to extendedMetadata
                                    )
                            )
                        )
                    } else {
                        throw IOException("Unable to download extendedmetadata!")
                    }
                } else {
                    throw IOException("Unable to download metadata!")
                }
            } catch (e: Throwable) {
                log.error("Error fetching metadata!", e)
                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Error(type = "getmetadata", exception = e)
                )
            }
        }





        private fun uploadMetadata(metadataJson: String): String =
            runBlocking {
                val service =
                    retrofit
                        .newBuilder()
                        .baseUrl("https://1v27dl8wn5.execute-api.us-west-2.amazonaws.com")
                        .build()
                        .create(MetadataService::class.java)
                val metadataPostInfo = service.getMetadataPostInfo()

                val uploadService =
                    retrofit
                        .newBuilder()
                        .baseUrl("https://s3.us-west-2.amazonaws.com")
                        .build()
                        .create(MetadataService::class.java)

                val response =
                    uploadService.saveMetadataFile(
                        contentType =
                            MultipartBody.Part.createFormData(
                                "content-type",
                                null,
                                "application/json".toRequestBody("text/plain".toMediaTypeOrNull())
                            ),
                        awsAccessKeyId =
                            MultipartBody.Part.createFormData(
                                "AWSAccessKeyId",
                                null,
                                metadataPostInfo.fields.awsAccessKeyId.toRequestBody("text/plain".toMediaTypeOrNull())
                            ),
                        key =
                            MultipartBody.Part.createFormData(
                                "key",
                                null,
                                metadataPostInfo.fields.key.toRequestBody("text/plain".toMediaTypeOrNull())
                            ),
                        policy =
                            MultipartBody.Part.createFormData(
                                "policy",
                                null,
                                metadataPostInfo.fields.policy.toRequestBody("text/plain".toMediaTypeOrNull())
                            ),
                        signature =
                            MultipartBody.Part.createFormData(
                                "signature",
                                null,
                                metadataPostInfo.fields.signature.toRequestBody("text/plain".toMediaTypeOrNull())
                            ),
                        securityToken =
                            MultipartBody.Part.createFormData(
                                "x-amz-security-token",
                                null,
                                metadataPostInfo.fields.securityToken.toRequestBody("text/plain".toMediaTypeOrNull())
                            ),
                        file =
                            MultipartBody.Part.createFormData(
                                "file",
                                metadataPostInfo.fields.key,
                                metadataJson.toRequestBody("application/json".toMediaTypeOrNull())
                            )
                    )

                if (response.isSuccessful) {
                    return@runBlocking "https://cardanostakehouse.com/${metadataPostInfo.fields.key}"
                }
                throw IOException("Could not upload json file!")
            }

        internal fun uploadGovernanceRationale(rationale: String?): Pair<String, String>? {
            if (rationale.isNullOrBlank()) {
                return null
            }

            val context =
                objectMapper.createObjectNode().apply {
                    put(
                        "hashAlgorithm",
                        "https://github.com/cardano-foundation/CIPs/blob/master/CIP-0100/README.md#hashAlgorithm",
                    )
                    set<ObjectNode>(
                        "authors",
                        objectMapper.createObjectNode().apply {
                            put("@id", "https://github.com/cardano-foundation/CIPs/blob/master/CIP-0100/README.md#authors")
                            put("@container", "@set")
                        },
                    )
                    set<ObjectNode>(
                        "body",
                        objectMapper.createObjectNode().apply {
                            put("@id", "https://github.com/cardano-foundation/CIPs/blob/master/CIP-0100/README.md#body")
                            set<ObjectNode>(
                                "@context",
                                objectMapper.createObjectNode().apply {
                                    put(
                                        "comment",
                                        "https://github.com/cardano-foundation/CIPs/blob/master/CIP-0100/README.md#comment",
                                    )
                                },
                            )
                        },
                    )
                }
            val document =
                objectMapper.createObjectNode().apply {
                    set<ObjectNode>("@context", context)
                    put("hashAlgorithm", "blake2b-256")
                    putArray("authors")
                    set<ObjectNode>(
                        "body",
                        objectMapper.createObjectNode().apply {
                            put("comment", rationale)
                        },
                    )
                }
            val json = objectMapper.writeValueAsString(document) + "\n"
            val expectedBytes = json.toByteArray(Charsets.UTF_8)
            val url = uploadMetadata(json)

            if (url.toByteArray(Charsets.UTF_8).size > 128 || !rationaleUrlPattern.matches(url)) {
                throw IOException("Invalid rationale anchor URL returned by metadata storage!")
            }

            val request =
                Request
                    .Builder()
                    .url(url)
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .build()
            val actualBytes =
                rationaleHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Could not retrieve uploaded rationale!")
                    }
                    val body = response.body
                    body.byteStream().use { it.readNBytes(expectedBytes.size + 1) }
                }
            if (!actualBytes.contentEquals(expectedBytes)) {
                throw IOException("Uploaded rationale does not match the submitted document!")
            }

            return url to Blake2b.hash256(expectedBytes).toHexString()
        }

        private fun createManualStartupScripts(
            request: CreateNodeRequest,
            startupNodeType: String,
            host: Host,
            hostConnection: HostConnection,
            tracingHost: String,
            tracingPort: Int?,
        ) {
            val startNodeContent =
                renderManualStartupScript(
                    request = request,
                    startupNodeType = startupNodeType,
                    host = host,
                    tracingHost = tracingHost,
                    tracingPort = tracingPort,
                )

            hostConnection.commandWriteFile("${host.nodeHomePath}/${request.name}/startNode.sh", startNodeContent)
            hostConnection.command("chmod 555 ${host.nodeHomePath}/${request.name}/startNode.sh")

            val stopNodeContent =
                """
            |#!/bin/bash
            |OLDPWD=`pwd`
            |cd ${host.nodeHomePath}/${request.name}
            |PID=`cat ${request.name}.pid`
            |kill -s INT ${'$'}PID >/dev/null 2>&1
            |sleep 3
            |kill -s KILL ${'$'}PID >/dev/null 2>&1
            |rm -f ${request.name}.pid
            |cd ${'$'}OLDPWD
                """.trimMargin()
            hostConnection.commandWriteFile("${host.nodeHomePath}/${request.name}/stopNode.sh", stopNodeContent)
            hostConnection.command("chmod 555 ${host.nodeHomePath}/${request.name}/stopNode.sh")
        }

        private fun createSystemdFile(
            request: CreateNodeRequest,
            startupNodeType: String,
            host: Host,
            hostConnection: HostConnection,
            name: String,
            processorThreads: Int,
            tracingHost: String,
            tracingPort: Int?,
        ) {
            val systemdContent =
                renderSystemdContent(
                    startupNodeType = startupNodeType,
                    host = host,
                    name = name,
                    processorThreads = processorThreads,
                    tracingHost = tracingHost,
                    tracingPort = tracingPort,
                )

            hostConnection.sudoCommandWriteFile(
                "/etc/systemd/system/$name-node.service",
                systemdContent,
                request.sudoPassword
            )

            if (hostConnection.hasSystemd) {
                hostConnection.sudoCommand("systemctl daemon-reload", request.sudoPassword)
                hostConnection.sudoCommand("systemctl stop $name-node.service", request.sudoPassword)
                hostConnection.sudoCommand("systemctl start $name-node.service", request.sudoPassword)
                hostConnection.sudoCommand("systemctl enable $name-node.service", request.sudoPassword)
            }
        }

        private fun createEnvFile(
            hostConnection: HostConnection,
            nodeType: String,
            nodeName: String,
            nodeFolder: String,
            listen: String,
            port: Int,
            tracingHost: String,
            tracingPort: Int?,
        ): String {
            hostConnection.commandWriteFile(
                "$nodeFolder/env",
                renderEnvContent(
                    nodeType = nodeType,
                    nodeName = nodeName,
                    nodeFolder = nodeFolder,
                    listen = listen,
                    port = port,
                    tracingHost = tracingHost,
                    tracingPort = tracingPort,
                )
            )
            return hostConnection.command("chmod 400 $nodeFolder/env")
        }

        internal fun renderEnvContent(
            nodeType: String,
            nodeName: String,
            nodeFolder: String,
            listen: String,
            port: Int,
            tracingHost: String,
            tracingPort: Int?,
        ): String {
            val tracingEnv =
                tracingPort?.let {
                    "|TRACING_HOST=$tracingHost\n|TRACING_PORT=$tracingPort\n"
                } ?: ""

            return when (nodeType) {
                NODE_TYPE_RELAY -> {
                    """
                |TOPOLOGY=$nodeFolder/topology.json
                |DATABASE_PATH=$nodeFolder/db
                |SOCKET_PATH=$nodeFolder/db/socket
                |HOST_ADDR=$listen
                |PORT=$port
                |CONFIG=$nodeFolder/config.json
                $tracingEnv
                    """.trimMargin()
                }

                NODE_TYPE_CORE -> {
                    """
                |TOPOLOGY=$nodeFolder/topology.json
                |DATABASE_PATH=$nodeFolder/db
                |SOCKET_PATH=$nodeFolder/db/socket
                |HOST_ADDR=$listen
                |PORT=$port
                |CONFIG=$nodeFolder/config.json
                |SHELLEY_KES_KEY=$nodeFolder/$nodeName.kes.skey
                |SHELLEY_VRF_KEY=$nodeFolder/$nodeName.vrf.skey
                |SHELLEY_OPCERT=$nodeFolder/$nodeName.node.opcert
                $tracingEnv
                    """.trimMargin()
                }

                else -> {
                    """
                |TOPOLOGY=$nodeFolder/topology.json
                |DATABASE_PATH=$nodeFolder/db
                |SOCKET_PATH=$nodeFolder/db/socket
                |HOST_ADDR=$listen
                |PORT=$port
                |CONFIG=$nodeFolder/config.json
                |BULK_CREDENTIALS=$nodeFolder/credentials.json
                $tracingEnv
                    """.trimMargin()
                }
            }
        }

        private fun createBulkCredentials(
            hostConnection: HostConnection,
            nodeFolder: String,
            credentials: MutableList<MutableList<Key>>
        ) {
            val path = "$nodeFolder/credentials.json"
            val existingBls = mutableMapOf<String, com.fasterxml.jackson.databind.JsonNode>()
            if (hostConnection.commandFileExists(path)) {
                val existing = objectMapper.readTree(hostConnection.commandReadFile(path))
                require(existing.isArray) { "Invalid existing bulk credentials" }
                existing.forEach { entry ->
                    require(entry.isArray && entry.size() in 3..4) { "Invalid existing bulk credential entry" }
                    val vrf = entry[1].path("cborHex").asText()
                    require(vrf.isNotEmpty()) { "Invalid existing VRF envelope" }
                    if (entry.size() == 4 && !entry[3].isNull) {
                        val bls = entry[3]
                        val keys = if (bls.isArray) bls.toList() else listOf(bls)
                        keys.forEach { key ->
                            require(key.isObject &&
                                key.path("type").asText() == "BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size" &&
                                key.path("cborHex").asText().matches(Regex("5820[0-9a-fA-F]{64}"))) {
                                "Invalid BLS envelope in existing bulk credentials"
                            }
                        }
                        require(existingBls.put(vrf, bls.deepCopy()) == null) { "Duplicate VRF in bulk credentials" }
                    }
                }
            }
            val output = objectMapper.createArrayNode()
            credentials.forEach { triple ->
                require(triple.size == 3) { "Invalid bulk credential entry" }
                val entry = objectMapper.createArrayNode()
                triple.forEach { entry.add(objectMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(it)) }
                existingBls.remove(triple[1].cborHex)?.let { entry.add(it) }
                output.add(entry)
            }
            require(existingBls.isEmpty()) { "Refusing to discard BLS keys from existing bulk credentials" }
            writePrivateNodeFile(hostConnection, path, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output))
        }

        private fun writePrivateNodeFile(hostConnection: HostConnection, path: String, content: String) {
            val staged = "/tmp/jormanager-credentials-${UUID.randomUUID()}"
            try {
                hostConnection.commandWriteSecret(staged, content)
                hostConnection.command(listOf("chmod", "400", staged))
                hostConnection.command(listOf("mv", "-f", staged, path))
            } finally {
                hostConnection.command(listOf("rm", "-f", staged))
            }
        }

        private fun createKesVrfOpcert(
            nodeName: String,
            kesSKeyContent: String,
            vrfSKeyContent: String,
            opcertContent: String,
            hostConnection: HostConnection,
            nodeFolder: String
        ) {
            writePrivateNodeFile(hostConnection, "$nodeFolder/$nodeName.kes.skey", kesSKeyContent)
            writePrivateNodeFile(hostConnection, "$nodeFolder/$nodeName.vrf.skey", vrfSKeyContent)

            if (hostConnection.commandFileExists("$nodeFolder/$nodeName.node.opcert")) {
                hostConnection.command("chmod 600 $nodeFolder/$nodeName.node.opcert")
            }
            hostConnection.commandWriteFile("$nodeFolder/$nodeName.node.opcert", opcertContent)
            hostConnection.command("chmod 400 $nodeFolder/$nodeName.node.opcert")
        }

        internal fun allocatePrometheusPort(
            hostId: Long,
            hostConnection: HostConnection,
            isPortUsed: (Int) -> Boolean = { port -> isPortUsed(hostConnection, port) },
        ): Int {
            var promPort = 12789 + nodeRepository.countForHost(hostId)
            while (isPortUsed(promPort)) {
                promPort++
            }

            return promPort
        }

        private fun createConfigFile(
            genesisByronFileName: String,
            nodeType: String,
            promPort: Int,
            prometheusListen: String,
            hostConnection: HostConnection,
            nodeFolder: String,
            maxConcurrencyDeadline: String,
            peerSharing: Boolean,
        ): Pair<Long, Int> {
            val configFile = fileRepository.findByName(genesisByronFileName.substringBeforeLast("-byron") + "-config.json")
            val configFileContent =
                configFile?.content?.let {
                    renderManagedConfig(
                        templateContent = it,
                        nodeType = nodeType,
                        maxConcurrencyDeadline = maxConcurrencyDeadline,
                        peerSharing = peerSharing,
                        prometheusListen = prometheusListen,
                        promPort = promPort,
                    )
                }
        /*
                configFile
                    ?.content
                    ?.replace(Regex(""""ConwayGenesisFile": .*,"""), """"ConwayGenesisFile": "conway-genesis.json",""")
                    ?.replace(Regex(""""AlonzoGenesisFile": .*,"""), """"AlonzoGenesisFile": "alonzo-genesis.json",""")
                    ?.replace(Regex(""""ByronGenesisFile": .*,"""), """"ByronGenesisFile": "byron-genesis.json",""")
                    ?.replace(Regex(""""ShelleyGenesisFile": .*,"""), """"ShelleyGenesisFile": "shelley-genesis.json",""")
                    ?.replace(Regex(""""GenesisFile": .*,"""), """"GenesisFile": "shelley-genesis.json",""")
                    ?.replace(Regex(""""PeerSharing": .*"""), """"PeerSharing": $peerSharing,""")
                    ?.replace(
                        Regex(""""TraceBlockFetchDecisions":.*(true|false),"""),
                        """"TraceBlockFetchDecisions": true,"""
                    )?.replace(
                        Regex(""".*"defaultScribes.*\[\n.*\[\n.*StdoutSK.*\n.*stdout.*\n.*\]\n.*\],"""),
                        """
                                                |  "defaultScribes": [
                                                |    [
                                                |      "FileSK",
                                                |      "logs/node.json"
                                                |    ]
                                                |  ],
                        """.trimMargin()
                    )?.replace(Regex(""""rpLogLimitBytes": .*,"""), """"rpLogLimitBytes": 20000000,""")
                    ?.replace(Regex(""""scFormat.*,"""), """"scFormat": "ScJson",""")
                    ?.replace(Regex(""""scKind.*,"""), """"scKind": "FileSK",""")
                    ?.replace(Regex(""""scName.*,"""), """"scName": "logs/node.json",""")
                    ?.replace("12798", "$promPort")
                    ?.replace(
                        Regex(""""MaxConcurrencyDeadline.*,""""),
                        """"MaxConcurrencyDeadline": $maxConcurrencyDeadline,"""
                    )
         */
            configFileContent?.let {
                log.debug("Creating $nodeFolder/config.json from db file ${configFile.name}")
                hostConnection.commandWriteFile("$nodeFolder/config.json", it)
                maybeCreateCheckpointsFile(configFile.name, it, hostConnection, nodeFolder)
            } ?: throw IOException("Config file not found in db!")

            return Pair(configFile.id!!, promPort)
        }

        internal fun renderManagedConfig(
            templateContent: String,
            nodeType: String,
            maxConcurrencyDeadline: String,
            peerSharing: Boolean,
            prometheusListen: String,
            promPort: Int,
        ): String {
            val root = objectMapper.readTree(templateContent).deepCopy<ObjectNode>()
            normalizeManagedConfig(root, nodeType, maxConcurrencyDeadline, peerSharing, prometheusListen, promPort)
            return objectMapper.writeValueAsString(root) + "\n"
        }

        private fun normalizeManagedConfig(
            root: ObjectNode,
            nodeType: String,
            maxConcurrencyDeadline: String,
            peerSharing: Boolean,
            prometheusListen: String,
            promPort: Int,
        ) {
            root.put("ConwayGenesisFile", "conway-genesis.json")
            root.put("AlonzoGenesisFile", "alonzo-genesis.json")
            root.put("ByronGenesisFile", "byron-genesis.json")
            root.put("ShelleyGenesisFile", "shelley-genesis.json")
            if (root.has("DijkstraGenesisFile")) root.put("DijkstraGenesisFile", "dijkstra-genesis.json")
            root.put("GenesisFile", "shelley-genesis.json")
            root.path("CheckpointsFile")?.takeIf { !it.isMissingNode && it.isTextual }?.let {
                root.put("CheckpointsFile", "checkpoints.json")
            }
            root.put("PeerSharing", peerSharing)
            root.put("MaxConcurrencyDeadline", maxConcurrencyDeadline.toInt())

            normalizeTracingConfig(root, nodeType, prometheusListen, promPort)
        }

        private fun normalizeTracingConfig(
            root: ObjectNode,
            nodeType: String,
            prometheusListen: String,
            promPort: Int,
        ) {
            val traceOptions = (root.get("TraceOptions") as? ObjectNode) ?: root.putObject("TraceOptions")

            val rootTraceOptions = traceOptions.get("") as ObjectNode
            rootTraceOptions.remove("detail")
            if (!rootTraceOptions.has("severity")) {
                rootTraceOptions.put("severity", MARKUS_ROOT_TRACE_SEVERITY)
            }

            val backends = objectMapper.createArrayNode().apply {
                add(STDOUT_MACHINE_FORMAT_BACKEND)
                if (nodeType in setOf(NODE_TYPE_CORE, NODE_TYPE_RELAY)) {
                    add(FORWARDER_BACKEND)
                }
                add("PrometheusSimple suffix $prometheusListen $promPort")
            }
            rootTraceOptions.set<ArrayNode>("backends", backends)

            traceOptions.putObject("Forge.Loop").put("severity", "Silence")
            traceOptions.putObject("Forge.Loop.ForgedBlock").put("severity", "Info")
            traceOptions.putObject("Forge.Loop.AdoptedBlock").put("severity", "Info")
            traceOptions.putObject("ChainDB.AddBlockEvent.AddedToCurrentChain").put("severity", "Silence")
            traceOptions.putObject("Net.ConnectionManager.Remote").put("severity", "Silence")
            traceOptions.putObject("Net.PeerSelection").put("severity", "Silence")
            traceOptions.putObject("Net.InboundGovernor.Remote").put("severity", "Silence")
            traceOptions.putObject("Net.InboundGovernor.Remote.InboundGovernorCounters").put("severity", "Silence")
            traceOptions.putObject("ChainSync.Client.DownloadedHeader").put("severity", "Silence")
            traceOptions.putObject("BlockFetch.Client.SendFetchRequest").put("severity", "Silence")
            traceOptions.putObject("BlockFetch.Client.CompletedBlockFetch").put("severity", "Silence")
            traceOptions.putObject("Resources").put("severity", "Silence")
            traceOptions.putObject("Mempool.AddedTx").put("severity", "Silence")
            traceOptions.putObject("Mempool.RemoveTxs").put("severity", "Silence")

            root.put("UseTraceDispatcher", true)
            root.put("TurnOnLogging", true)
            root.put("TurnOnLogMetrics", true)
            root.put("minSeverity", MIN_TRACE_SEVERITY)

            if (nodeType in setOf(NODE_TYPE_CORE, NODE_TYPE_RELAY)) {
                val forwarderOptions = objectMapper.createObjectNode().apply {
                    put("connQueueSize", TRACE_FORWARDER_CONN_QUEUE_SIZE)
                    put("disconnQueueSize", TRACE_FORWARDER_DISCONN_QUEUE_SIZE)
                    put("maxReconnectDelay", TRACE_FORWARDER_MAX_RECONNECT_DELAY)
                }
                root.set<ObjectNode>("TraceOptionForwarder", forwarderOptions)
            } else {
                root.remove("TraceOptionForwarder")
            }

            LEGACY_TRACING_KEYS_TO_REMOVE.forEach(root::remove)
            root
                .fieldNames()
                .asSequence()
                .toList()
                .filter {
                    it.startsWith("Trace") &&
                        it != "TraceOptions" &&
                        it != "TraceOptionForwarder" &&
                        it != "TraceOptionMetricsPrefix" &&
                        it != "TraceOptionResourceFrequency"
                }.forEach(root::remove)
        }

        private fun maybeCreateCheckpointsFile(
            configFileName: String,
            configFileContent: String,
            hostConnection: HostConnection,
            nodeFolder: String,
        ) {
            val checkpointsFileName =
                objectMapper
                    .readTree(configFileContent)
                    .path("CheckpointsFile")
                    .takeIf { !it.isMissingNode && it.isTextual }
                    ?.asText()
                    ?: return

            val sourceCheckpointsFile =
                fileRepository.findByName(configFileName.removeSuffix("-config.json") + "-checkpoints.json")
                    ?: throw IOException("Checkpoints file $checkpointsFileName referenced by $configFileName not found in db!")

            log.debug("Creating $nodeFolder/checkpoints.json from db file ${sourceCheckpointsFile.name}")
            hostConnection.commandWriteFile("$nodeFolder/checkpoints.json", sourceCheckpointsFile.content)
        }

        private fun isPortUsed(
            hostConnection: HostConnection,
            port: Int
        ): Boolean = hostConnection.command("ss -tulw").trim().contains(":$port")

        internal fun allocateTracingPort(
            promPort: Int,
            isPortUsed: (Int) -> Boolean,
        ): Int {
            var tracingPort = promPort + 1
            while (isPortUsed(tracingPort)) {
                tracingPort++
            }
            return tracingPort
        }

        internal fun renderTracingListenerArgument(
            tracingHost: String,
            tracingPort: Int?,
        ): String? {
            if (tracingPort == null) {
                return null
            }

            return "--tracer-socket-network-accept ${'$'}{TRACING_HOST}:${'$'}{TRACING_PORT}"
        }

        internal fun renderManualStartupScript(
            request: CreateNodeRequest,
            startupNodeType: String,
            host: Host,
            tracingHost: String,
            tracingPort: Int?,
        ): String {
            val listenerArgument = renderTracingListenerArgument(tracingHost, tracingPort)
            val listenerLine = listenerArgument?.let { "|  $it \\\n" } ?: ""

            return when (startupNodeType) {
                NODE_TYPE_RELAY -> {
                    """
                |#!/bin/bash
                |OLDPWD=`pwd`
                |cd ${host.nodeHomePath}/${request.name}
                |source ${host.nodeHomePath}/${request.name}/env
                |nohup ${host.cardanoNodePath} \
                |  +RTS -N${request.processorThreads} -RTS run \
                |  --topology ${'$'}{TOPOLOGY} \
                |  --database-path ${'$'}{DATABASE_PATH} \
                |  --socket-path ${'$'}{SOCKET_PATH} \
                |  --host-addr ${'$'}{HOST_ADDR} \
                |  --port ${'$'}{PORT} \
                |  --config ${'$'}{CONFIG} \
                $listenerLine
                |  > ${request.name}.log 2>&1 &
                |echo ${'$'}! > ${request.name}.pid
                |cd ${'$'}OLDPWD
                |echo "Started with logfile ${request.name}.log"
                    """.trimMargin()
                }

                else -> {
                    """
                |#!/bin/bash
                |OLDPWD=`pwd`
                |cd ${host.nodeHomePath}/${request.name}
                |source ${host.nodeHomePath}/${request.name}/env
                |nohup ${host.cardanoNodePath} \
                |  +RTS -N${request.processorThreads} -RTS run \
                |  --topology ${'$'}{TOPOLOGY} \
                |  --database-path ${'$'}{DATABASE_PATH} \
                |  --socket-path ${'$'}{SOCKET_PATH} \
                |  --host-addr ${'$'}{HOST_ADDR} \
                |  --port ${'$'}{PORT} \
                |  --config ${'$'}{CONFIG} \
                |  --shelley-kes-key ${'$'}{SHELLEY_KES_KEY} \
                |  --shelley-vrf-key ${'$'}{SHELLEY_VRF_KEY} \
                |  --shelley-operational-certificate ${'$'}{SHELLEY_OPCERT} \
                $listenerLine
                |  > ${request.name}.log 2>&1 &
                |echo ${'$'}! > ${request.name}.pid
                |cd ${'$'}OLDPWD
                |echo "Started with logfile ${request.name}.log"
                    """.trimMargin()
                }
            }
        }

        internal fun renderSystemdContent(
            startupNodeType: String,
            host: Host,
            name: String,
            processorThreads: Int,
            tracingHost: String,
            tracingPort: Int?,
        ): String {
            val listenerArgument = renderTracingListenerArgument(tracingHost, tracingPort)
            val listenerLine = listenerArgument?.let { "|  $it\n" } ?: ""

            return when (startupNodeType) {
                NODE_TYPE_RELAY -> {
                    """
                |[Unit]
                |Description=Cardano Haskell Node - $name
                |After=syslog.target
                |StartLimitIntervalSec=0
                |
                |[Service]
                |Type=simple
                |Restart=always
                |RestartSec=5
                |User=${host.sshUser}
                |LimitNOFILE=131072
                |WorkingDirectory=${host.nodeHomePath}/$name
                |EnvironmentFile=${host.nodeHomePath}/$name/env
                |ExecStart=${host.cardanoNodePath} \
                |  +RTS -N$processorThreads -RTS run \
                |  --topology ${'$'}{TOPOLOGY} \
                |  --database-path ${'$'}{DATABASE_PATH} \
                |  --socket-path ${'$'}{SOCKET_PATH} \
                |  --host-addr ${'$'}{HOST_ADDR} \
                |  --port ${'$'}{PORT} \
                |  --config ${'$'}{CONFIG} \
                $listenerLine
                |KillSignal=SIGINT
                |SyslogIdentifier=$name-node
                |
                |[Install]
                |WantedBy=multi-user.target
                    """.trimMargin()
                }

                NODE_TYPE_CORE -> {
                    """
                |[Unit]
                |Description=Cardano Haskell Node - $name
                |After=syslog.target
                |StartLimitIntervalSec=0
                |
                |[Service]
                |Type=simple
                |Restart=always
                |RestartSec=5
                |User=${host.sshUser}
                |LimitNOFILE=131072
                |WorkingDirectory=${host.nodeHomePath}/$name
                |EnvironmentFile=${host.nodeHomePath}/$name/env
                |ExecStart=${host.cardanoNodePath} \
                |  +RTS -N$processorThreads -RTS run \
                |  --topology ${'$'}{TOPOLOGY} \
                |  --database-path ${'$'}{DATABASE_PATH} \
                |  --socket-path ${'$'}{SOCKET_PATH} \
                |  --host-addr ${'$'}{HOST_ADDR} \
                |  --port ${'$'}{PORT} \
                |  --config ${'$'}{CONFIG} \
                |  --shelley-kes-key ${'$'}{SHELLEY_KES_KEY} \
                |  --shelley-vrf-key ${'$'}{SHELLEY_VRF_KEY} \
                |  --shelley-operational-certificate ${'$'}{SHELLEY_OPCERT} \
                $listenerLine
                |KillSignal=SIGINT
                |SyslogIdentifier=$name-node
                |
                |[Install]
                |WantedBy=multi-user.target
                    """.trimMargin()
                }

                else -> {
                    """
                |[Unit]
                |Description=Cardano Haskell Node - $name
                |After=syslog.target
                |StartLimitIntervalSec=0
                |
                |[Service]
                |Type=simple
                |Restart=always
                |RestartSec=5
                |User=${host.sshUser}
                |LimitNOFILE=131072
                |WorkingDirectory=${host.nodeHomePath}/$name
                |EnvironmentFile=${host.nodeHomePath}/$name/env
                |ExecStart=${host.cardanoNodePath} \
                |  +RTS -N$processorThreads -RTS run \
                |  --topology ${'$'}{TOPOLOGY} \
                |  --database-path ${'$'}{DATABASE_PATH} \
                |  --socket-path ${'$'}{SOCKET_PATH} \
                |  --host-addr ${'$'}{HOST_ADDR} \
                |  --port ${'$'}{PORT} \
                |  --config ${'$'}{CONFIG} \
                |  --bulk-credentials-file ${'$'}{BULK_CREDENTIALS} \
                $listenerLine
                |KillSignal=SIGINT
                |SyslogIdentifier=$name-node
                |
                |[Install]
                |WantedBy=multi-user.target
                    """.trimMargin()
                }
            }
        }


        private fun createTopologyFile(
            byronGenesisFileName: String,
            hostConnection: HostConnection,
            nodeFolder: String
        ) {
            val topologyFile =
                fileRepository.findByName(byronGenesisFileName.substringBeforeLast("-byron") + "-topology.json")
            topologyFile?.let {
                if (!hostConnection.commandFileExists("$nodeFolder/topology.json")) {
                    log.debug("Creating $nodeFolder/topology.json from db file ${topologyFile.name}")
                    hostConnection.commandWriteFile("$nodeFolder/topology.json", topologyFile.content)
                } else {
                    log.debug("SKIP Creating $nodeFolder/topology.json from db file ${topologyFile.name}")
                }
            } ?: throw IOException("Topology file not found in db!")
        }

        private fun requireSameNetwork(observer: Node, targetId: Long) {
            val target = nodeRepository.findByIdOrNull(targetId) ?: throw IOException("Node $targetId not found")
            require(cardanoRepository.networkKey(target) == cardanoRepository.networkKey(observer)) {
                "Pool and observer are on different networks"
            }
        }

        private fun createGenesisFile(
            prefix: String,
            genesisFileId: Long,
            hostConnection: HostConnection,
            nodeFolder: String
        ): com.swiftmako.jormanager.entities.File {
            val genesisFile = fileRepository.findByIdOrNull(genesisFileId)
            genesisFile?.let {
                log.debug("Creating $nodeFolder/$prefix-genesis.json from db file ${genesisFile.name}")
                hostConnection.commandWriteFile("$nodeFolder/$prefix-genesis.json", genesisFile.content)
                return genesisFile
            } ?: throw IOException("Genesis file not found in db!")
        }

        private fun createNodeFolders(
            hostConnection: HostConnection,
            nodeFolder: String
        ) {
            hostConnection.command("mkdir -p $nodeFolder/db")
            hostConnection.command("mkdir -p $nodeFolder/logs")
        }

        companion object {
            const val NODE_TYPE_RELAY = "relay"
            const val NODE_TYPE_CORE = "core"
            const val NODE_TYPE_POOL = "pool"
            private const val STDOUT_MACHINE_FORMAT_BACKEND = "Stdout MachineFormat"
            private const val FORWARDER_BACKEND = "Forwarder"
            private const val MARKUS_ROOT_TRACE_SEVERITY = "Notice"
            private const val MIN_TRACE_SEVERITY = "Critical"
            private const val TRACE_FORWARDER_CONN_QUEUE_SIZE = 64
            private const val TRACE_FORWARDER_DISCONN_QUEUE_SIZE = 128
            private const val TRACE_FORWARDER_MAX_RECONNECT_DELAY = 30
            private val MARKUS_TRACE_OPTIONS_JSON =
                """
                {
                  "": {
                    "backends": [
                      "Stdout MachineFormat",
                      "PrometheusSimple 127.0.0.1 12798"
                    ],
                    "severity": "Notice"
                  },
                  "Version.NodeVersion": {
                    "severity": "Info"
                  },
                  "Startup.DiffusionInit": {
                    "severity": "Info"
                  },
                  "Resources": {
                    "severity": "Info",
                    "maxFrequency": 0.0167
                  },
                  "ChainDB.LedgerEvent.Replay": {
                    "severity": "Info",
                    "maxFrequency": 0.0668
                  },
                  "ChainDB.ImmDbEvent": {
                    "severity": "Warning"
                  },
                  "ChainDB.ImmDbEvent.ChunkValidation.ValidatedChunk": {
                    "severity": "Info"
                  },
                  "Net.ConnectionManager.Remote": {
                    "severity": "Info"
                  },
                  "Net.PeerSelection": {
                    "severity": "Info"
                  },
                  " Net.PeerSelection.Actions.ConnectionError": {
                    "severity": "Silence"
                  },
                  "Net.InboundGovernor.Remote": {
                    "severity": "Info"
                  },
                  "Net.InboundGovernor.Remote.InboundGovernorCounters": {
                    "severity": "Info",
                    "maxFrequency": 0.0167
                  },
                  "Net.ErrorPolicy": {
                    "severity": "Info"
                  },
                  "Net.AcceptPolicy.ConnectionRateLimiting": {
                    "severity": "Info",
                    "maxFrequency": 0.0167
                  },
                  "Net.AcceptPolicy.ConnectionLimitResume": {
                    "severity": "Info",
                    "maxFrequency": 0.0167
                  },
                  "ChainSync.Client": {
                    "severity": "Warning"
                  },
                  "Forge.Loop": {
                    "severity": "Info"
                  },
                  "Forge.Loop.NodeNotLeader": {
                    "severity": "Silence"
                  },
                  "Forge.Loop.StartLeadershipCheck": {
                    "severity": "Silence"
                  },
                  "Forge.StateInfo": {
                    "severity": "Info",
                    "maxFrequency": 0.0002783
                  },
                  "ChainDB.AddBlockEvent.SwitchedToAFork": {
                    "severity": "Info"
                  },
                  "ChainDB.AddBlockEvent.AddedToCurrentChain": {
                    "maxFrequency": 2.0
                  },
                  "ChainSync.Client.DownloadedHeader": {
                    "severity": "Info",
                    "maxFrequency": 14.0
                  },
                  "BlockFetch.Client.SendFetchRequest": {
                    "severity": "Info"
                  },
                  "BlockFetch.Client.CompletedBlockFetch": {
                    "severity": "Info",
                    "maxFrequency": 4.0
                  }
                }
                """.trimIndent()
            private val LEGACY_TRACING_KEYS_TO_REMOVE =
                listOf(
                    "defaultBackends",
                    "defaultScribes",
                    "setupBackends",
                    "setupScribes",
                    "rotation",
                    "TracingVerbosity",
                    "EKGBackend",
                    "PrometheusSimple",
                    "hasEkg",
                    "hasEKG",
                    "hasPrometheus",
                )
            private val IP4_ADDRESS =
                Regex(
                    "(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)"
                )
        }
    }
