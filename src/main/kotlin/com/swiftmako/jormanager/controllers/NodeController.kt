package com.swiftmako.jormanager.controllers

import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
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
import com.swiftmako.jormanager.services.NodeConfigurationFiles
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
        private val nodeConfigurationFiles: NodeConfigurationFiles,
        // @param:Value("\${jormanager.era}") private val eraString: String,
    ) {
        private val log by lazy { LoggerFactory.getLogger("NodeController") }
        private val objectMapper by lazy {
            JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()
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
            val hostId: Long, val type: String, val port: Int, val prometheusListen: String,
            val parentId: Long? = null,
            @param:com.fasterxml.jackson.annotation.JsonProperty("config") val configFileId: Long = 0,
        )

        data class ProvisioningPreview(
            val hostId: Long, val observerNodeId: Long, val networkKey: String,
            val prometheusPort: Int, val tracingPort: Int, val configSha256: String
        )

        @MessageMapping("/node/provisioning-preview")
        @SendTo("/topic/messages")
        fun previewProvisioning(request: ProvisioningPreviewRequest): SocketResponse<ProvisioningPreview> =
            try {
                require(request.type in setOf(NODE_TYPE_CORE, NODE_TYPE_POOL)) { "Invalid producer role" }
                val observer = nodeRepository.findDefault() ?: throw IOException("A matching connected observer is required")
                val host = hostRepository.findByIdOrNull(request.hostId) ?: throw IOException("Target host not found")
                val connection = HostConnection(host)
                val parent = if (request.type == NODE_TYPE_POOL) {
                    val node = request.parentId?.let { nodeRepository.findByIdOrNull(it) }
                        ?: throw IOException("Parent core node not found")
                    require(!node.isDraft && !node.isDeleted && node.type == NODE_TYPE_CORE && node.hostId == request.hostId &&
                        cardanoRepository.networkKey(node) == cardanoRepository.networkKey(observer)) {
                        "Pool parent must be an active core on the selected host and network"
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
                        fileRepository.findByIdOrNull(request.configFileId)?.content
                            ?: throw IOException("Selected config not found"),
                        request.type, "2", false, request.prometheusListen, promPort)
                val digest = MessageDigest.getInstance("SHA-256").digest(config.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                SocketResponse.Success(type = "node_provisioning_preview", data = ProvisioningPreview(
                    request.hostId, requireNotNull(observer.id), cardanoRepository.networkKey(observer),
                    promPort, tracingPort, digest
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
                require(walletUtils.isValidSpendingPassword(input.spendingPassword)) { "Invalid spending password!" }
                require(input.enableTracingListener) { "Tracing listener must be enabled!" }
                require(input.name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*"))) { "Invalid node name" }
                require(input.processorThreads > 0 && input.port in 1..65535) { "Invalid threads or port" }
                require(listOfNotNull(input.listen, input.prometheusListen, input.tracingListen).all {
                    it.matches(Regex("[A-Za-z0-9.:-]+"))
                }) { "Invalid listen address" }
                val assets = nodeConfigurationFiles.resolve(input)
                val topology = nodeConfigurationFiles.renderTopology(assets)
                val host = hostRepository.findByIdOrNull(input.hostId)
                    ?: throw IOException("Invalid HostId: ${input.hostId}")
                fun safePath(path: String) {
                    require(path.matches(Regex("[A-Za-z0-9_./-]+")) && !path.startsWith("-") &&
                        !path.split('/').contains("..")) { "Invalid host path" }
                }
                safePath(host.nodeHomePath)
                safePath(host.cardanoNodePath)
                safePath(host.cardanoCliPath)
                val nodeFolder = "${host.nodeHomePath}${File.separator}${input.name}"
                val tracingListen = input.tracingListen ?: if (host.isRemote) "0.0.0.0" else "127.0.0.1"
                val hostConnection = HostConnection(host)
                hostConnection.sudoCommand("pwd", input.sudoPassword)
                require(!hostConnection.commandFileExists(nodeFolder)) { "Node directory already exists" }
                require(!hostConnection.commandFileExists("/etc/systemd/system/${input.name}-node.service")) {
                    "Node service already exists"
                }
                require(!isPortUsed(hostConnection, input.port)) { "The selected node port is unavailable" }
                val promPort = allocatePrometheusPort(input.hostId, hostConnection)
                require(promPort in 1..65535 && promPort != input.port) { "Prometheus and node ports conflict" }
                val tracingPort = allocateTracingPort(promPort) { it == input.port || isPortUsed(hostConnection, it) }
                require(tracingPort in 1..65535) { "Tracing port is unavailable" }
                val config = renderManagedConfig(assets.getValue("config.json").toString(Charsets.UTF_8),
                    input.type, "4", true, input.prometheusListen, promPort)

                createNodeFolders(hostConnection, nodeFolder)
                assets.filterKeys { it != "config.json" && it != "topology.json" }.forEach { (name, bytes) ->
                    hostConnection.commandWriteFile("$nodeFolder/$name", bytes.toString(Charsets.UTF_8))
                }
                hostConnection.commandWriteFile("$nodeFolder/config.json", config)
                hostConnection.commandWriteFile("$nodeFolder/topology.json", topology)
                assets.filterKeys { it != "config.json" && it != "topology.json" }.forEach { (name, bytes) ->
                    require(hostConnection.commandReadFile("$nodeFolder/$name") == bytes.toString(Charsets.UTF_8)) {
                        "Installed $name does not match its validated selection"
                    }
                }
                require(hostConnection.commandReadFile("$nodeFolder/config.json") == config) { "Installed config differs from selection" }
                require(hostConnection.commandReadFile("$nodeFolder/topology.json") == topology) { "Installed topology differs from selection" }
                createEnvFile(hostConnection, input.type, input.name, nodeFolder, input.listen, input.port, tracingListen, tracingPort)
                createSystemdFile(input, input.type, host, hostConnection, input.name, input.processorThreads, tracingListen, tracingPort)
                createManualStartupScripts(input, input.type, host, hostConnection, tracingListen, tracingPort)

                if (input.isDefault) {
                    nodeRepository.findDefault()?.let { nodeRepository.save(it.copy(isDefault = false)) }
                }
                val savedNode = nodeRepository.save(Node(
                    hostId = host.id!!, color = input.color, type = input.type, processorThreads = input.processorThreads,
                    name = input.name, listen = input.listen, port = input.port, promPort = promPort,
                    tracingHost = tracingListen, tracingPort = tracingPort, isDefault = input.isDefault,
                    genesisByronFileId = input.genesisByronFileId, genesisShelleyFileId = input.genesisShelleyFileId,
                    genesisAlonzoFileId = input.genesisAlonzoFileId, genesisConwayFileId = input.genesisConwayFileId,
                    genesisDijkstraFileId = input.genesisDijkstraFileId, configFileId = input.configFileId,
                    topologyFileId = input.topologyFileId, peerSnapshotFileId = input.peerSnapshotFileId,
                ))
                nodesChannel.tryEmit(savedNode)
                val nodes = nodeRepository.findAll(Sort.by(Sort.Direction.ASC, "name")).filter { !it.isDeleted }
                webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success(type = "nodes", data = nodes))
                webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success("createnode", "${input.name} created successfully!"))
            } catch (e: Throwable) {
                log.error("Error Creating Node!", e)
                webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Error(type = "createnode", exception = e))
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
                    set(
                        "authors",
                        objectMapper.createObjectNode().apply {
                            put("@id", "https://github.com/cardano-foundation/CIPs/blob/master/CIP-0100/README.md#authors")
                            put("@container", "@set")
                        },
                    )
                    set(
                        "body",
                        objectMapper.createObjectNode().apply {
                            put("@id", "https://github.com/cardano-foundation/CIPs/blob/master/CIP-0100/README.md#body")
                            set(
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
                    set("@context", context)
                    put("hashAlgorithm", "blake2b-256")
                    putArray("authors")
                    set(
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
            val existingBls = mutableMapOf<String, JsonNode>()
            if (hostConnection.commandFileExists(path)) {
                val existing = objectMapper.readTree(hostConnection.commandReadFile(path))
                require(existing.isArray) { "Invalid existing bulk credentials" }
                existing.forEach { entry ->
                    require(entry.isArray && entry.size() in 3..4) { "Invalid existing bulk credential entry" }
                    val vrf = entry[1].path("cborHex").asString()
                    require(vrf.isNotEmpty()) { "Invalid existing VRF envelope" }
                    if (entry.size() == 4 && !entry[3].isNull) {
                        val bls = entry[3]
                        val keys = if (bls.isArray) bls.toList() else listOf(bls)
                        keys.forEach { key ->
                            require(key.isObject &&
                                key.path("type").asString() == "BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size" &&
                                key.path("cborHex").asString().matches(Regex("5820[0-9a-fA-F]{64}"))) {
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
                triple.forEach { entry.add(objectMapper.valueToTree<JsonNode>(it)) }
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


        internal fun renderManagedConfig(
            templateContent: String,
            nodeType: String,
            maxConcurrencyDeadline: String,
            peerSharing: Boolean,
            prometheusListen: String,
            promPort: Int,
        ): String {
            val root = (objectMapper.readTree(templateContent) as ObjectNode).deepCopy()
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
            root.remove("GenesisFile")
            root.path("CheckpointsFile")?.takeIf { !it.isMissingNode && it.isString }?.let {
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

            val rootTraceOptions = (traceOptions.get("") as? ObjectNode) ?: traceOptions.putObject("")
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
            rootTraceOptions.set("backends", backends)

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
                root.set("TraceOptionForwarder", forwarderOptions)
            } else {
                root.remove("TraceOptionForwarder")
            }

            LEGACY_TRACING_KEYS_TO_REMOVE.forEach(root::remove)
            root
                .propertyNames()
                .toList()
                .filter {
                    it.startsWith("Trace") &&
                        it != "TraceOptions" &&
                        it != "TraceOptionForwarder" &&
                        it != "TraceOptionMetricsPrefix" &&
                        it != "TraceOptionResourceFrequency"
                }.forEach(root::remove)
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



        private fun requireSameNetwork(observer: Node, targetId: Long) {
            val target = nodeRepository.findByIdOrNull(targetId) ?: throw IOException("Node $targetId not found")
            require(cardanoRepository.networkKey(target) == cardanoRepository.networkKey(observer)) {
                "Pool and observer are on different networks"
            }
        }


        private fun createNodeFolders(
            hostConnection: HostConnection,
            nodeFolder: String
        ) {
            hostConnection.command("mkdir $nodeFolder")
            hostConnection.command("mkdir $nodeFolder/db")
            hostConnection.command("mkdir $nodeFolder/logs")
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
