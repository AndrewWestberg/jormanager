package com.swiftmako.jormanager.controllers

import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.NodeConfigurationSnapshot
import com.swiftmako.jormanager.entities.Relay
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.CreateNodeRequest
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeConfigurationSnapshotRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.RelayRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.services.CardanoCapabilities
import com.swiftmako.jormanager.services.NodeConfigurationFiles
import com.swiftmako.jormanager.utils.CborSpans
import java.security.MessageDigest
import java.util.UUID
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class PoolDraftContextRequest(val requestId: String, val nodeId: Long)
data class PoolDraftContext(val requestId: String, val nodeId: Long, val hostId: Long, val observerNodeId: Long,
    val networkKey: String, val configSha256: String)

@Controller
class PoolDraftController(private val drafts: PoolDraftService) {
    @MessageMapping("/node/pool-draft")
    @SendTo("/topic/messages")
    fun create(request: CreateNodeRequest): SocketResponse<Node> = try {
        SocketResponse.Success("node_pool_draft", drafts.create(request))
    } catch (_: Exception) {
        SocketResponse.Error("node_pool_draft", SocketResponse.PublicFailure(
            code = "PoolDraftFailed", message = "Pool draft could not be prepared or is not qualified for this target",
            operation = "node_pool_draft", retryDisposition = "review",
        ))
    }

    @MessageMapping("/node/pool-draft-context")
    @SendTo("/topic/messages")
    fun context(request: PoolDraftContextRequest): SocketResponse<PoolDraftContext> = try {
        SocketResponse.Success("node_pool_draft_context", drafts.context(request))
    } catch (_: Exception) {
        SocketResponse.Error("node_pool_draft_context", SocketResponse.PublicFailure(
            code = "PoolDraftContextUnavailable", message = "Draft or matching observer is unavailable",
            operation = "node_pool_draft_context", retryDisposition = "review", requestId = request.requestId,
        ))
    }
}

@Service
class PoolDraftService(
    private val nodes: NodeRepository,
    private val hosts: HostRepository,
    private val files: FileRepository,
    private val wallets: WalletRepository,
    private val relays: RelayRepository,
    private val snapshots: NodeConfigurationSnapshotRepository,
    private val walletUtils: WalletUtils,
    private val configurationFiles: NodeConfigurationFiles,
    private val eras: CardanoRepository,
    private val capabilities: CardanoCapabilities,
    private val nodeController: NodeController,
    private val json: ObjectMapper,
    private val jdbc: JdbcTemplate,
) {
    private fun digest(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).toHexString()

    fun context(request: PoolDraftContextRequest): PoolDraftContext {
        require(request.requestId.isNotBlank())
        val node = requireNotNull(nodes.findByIdOrNull(request.nodeId))
        require(node.isDraft && !node.isDeleted)
        val observer = requireNotNull(nodes.findDefault())
        require(!observer.isDeleted && !observer.isDraft)
        val network = eras.networkKey(node)
        require(eras.networkKey(observer) == network)
        val evidence = eras.evidence(observer)
        require(evidence.freshness == "current" && evidence.era in setOf("dijkstra", "conway") && evidence.networkKey == network &&
            evidence.source == "host:${observer.hostId}/node:${observer.id}")
        val config = requireNotNull(files.findByIdOrNull(node.configFileId)).content
        return PoolDraftContext(request.requestId, request.nodeId, node.hostId, requireNotNull(observer.id), network,
            digest(config.toByteArray(Charsets.UTF_8)))
    }

    @Transactional
    fun create(request: CreateNodeRequest): Node {
        require(walletUtils.isValidSpendingPassword(request.spendingPassword)) { "Authorization required" }
        require(request.type in setOf("core", "pool") && !request.isDefault && request.name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*")))
        require(request.poolPledge != null && request.poolPledge.signum() >= 0 && request.poolCost != null && request.poolCost.signum() >= 0)
        require(request.metadata?.extended?.itn?.privateKey.isNullOrBlank()) { "Draft metadata must not contain an ITN private key" }
        val margin = request.poolMargin.toBigDecimal()
        require(margin >= java.math.BigDecimal.ZERO && margin <= java.math.BigDecimal.ONE)
        require(request.processorThreads > 0 && request.port in 1..65535)
        require(request.relays.orEmpty().all { it.addr.isNotBlank() && it.port in 1..65535 })
        val owner = requireNotNull(wallets.findByIdOrNull(requireNotNull(request.ownerStakingAccount)))
        val reward = requireNotNull(wallets.findByIdOrNull(requireNotNull(request.rewardsStakingAccount)))
        val payer = requireNotNull(wallets.findByIdOrNull(requireNotNull(request.registrationFeesAccount)))
        require(!owner.deleted && !reward.deleted && !payer.deleted && owner.stakingVkey != null && reward.stakingVkey != null && payer.paymentVkey != null)
        val host = requireNotNull(hosts.findByIdOrNull(request.hostId))
        val observer = requireNotNull(nodes.findDefault())
        require(!observer.isDeleted && !observer.isDraft)
        val observerHost = requireNotNull(hosts.findByIdOrNull(observer.hostId))
        val network = eras.networkKey(observer)
        val evidence = eras.evidence(observer)
        val selectedEra = requireNotNull(evidence.era)
        require(evidence.freshness == "current" && selectedEra in setOf("conway", "dijkstra") &&
            evidence.networkKey == network && evidence.source == "host:${observerHost.id}/node:${observer.id}")
        val connection = HostConnection(host)
        val parent = if (request.type == "pool") {
            val node = requireNotNull(request.parentId?.let { nodes.findByIdOrNull(it) })
            require(!node.isDraft && !node.isDeleted && node.type == "core" && node.hostId == host.id &&
                eras.networkKey(node) == network)
            node
        } else {
            require(request.parentId == null)
            null
        }
        val assets = if (parent == null) configurationFiles.resolve(request) else {
            val folder = "${host.nodeHomePath}/${parent.name}"
            val ids = mapOf("byron-genesis.json" to parent.genesisByronFileId, "shelley-genesis.json" to parent.genesisShelleyFileId,
                "alonzo-genesis.json" to parent.genesisAlonzoFileId, "conway-genesis.json" to parent.genesisConwayFileId) +
                listOfNotNull(parent.genesisDijkstraFileId?.let { "dijkstra-genesis.json" to it }).toMap()
            val inherited = ids.mapValues { requireNotNull(files.findByIdOrNull(it.value)).content.toByteArray(Charsets.UTF_8) }
            val remote = listOf("config.json", "topology.json", "peer-snapshot.json", "checkpoints.json")
                .filter { it in setOf("config.json", "topology.json") || connection.commandFileExists("$folder/$it") }
                .associateWith { connection.commandReadFile("$folder/$it").toByteArray(Charsets.UTF_8) }
            inherited + remote
        }
        configurationFiles.validate(assets)
        val observedGenesis = mapOf("byron-genesis.json" to observer.genesisByronFileId,
            "shelley-genesis.json" to observer.genesisShelleyFileId, "alonzo-genesis.json" to observer.genesisAlonzoFileId,
            "conway-genesis.json" to observer.genesisConwayFileId) +
            listOfNotNull(observer.genesisDijkstraFileId?.let { "dijkstra-genesis.json" to it }).toMap()
        require(assets.keys.filter { it.endsWith("-genesis.json") }.toSet() == observedGenesis.keys)
        observedGenesis.forEach { (name, id) ->
            require(assets.getValue(name).contentEquals(requireNotNull(files.findByIdOrNull(id)).content.toByteArray(Charsets.UTF_8))) {
                "Genesis differs from observer"
            }
        }
        fun portUsed(port: Int) = connection.command("ss -tulw").trim().contains(":$port")
        if (parent == null) require(!portUsed(request.port))
        val promPort = parent?.promPort ?: nodeController.allocatePrometheusPort(request.hostId, connection)
        if (parent == null) require(promPort != request.port)
        val tracingPort = parent?.tracingPort ?: nodeController.allocateTracingPort(promPort) { it == request.port || portUsed(it) }
        val config = if (parent != null) connection.commandReadFile("${host.nodeHomePath}/${parent.name}/config.json")
            else nodeController.renderManagedConfig(assets.getValue("config.json").toString(Charsets.UTF_8), request.type, "2", false, request.prometheusListen, promPort)
        val configHash = digest(config.toByteArray(Charsets.UTF_8))
        fun requireQualified() {
            if (selectedEra == "dijkstra") capabilities.requireHostOperation(host, observerHost, observer, network, configHash, "node.create")
            else capabilities.requireOperation(observerHost, observer, "node.create")
        }
        requireQualified()
        require(host.cardanoCliPath.matches(Regex("[A-Za-z0-9_./-]+")) && !host.cardanoCliPath.startsWith("-"))
        val directory = connection.commandArgs(listOf("mktemp", "-d", "/tmp/jormanager-pool-draft-XXXXXXXXXX")).trim()
        require(directory.matches(Regex("/tmp/jormanager-pool-draft-[A-Za-z0-9]+")))
        try {
            fun run(vararg args: String) = connection.commandArgs(listOf(host.cardanoCliPath, selectedEra) + args)
            fun envelope(path: String): String = connection.commandArgs(listOf("cat", path))
            fun keyBytes(content: String): ByteArray {
                val hex = json.readTree(content).path("cborHex").asString()
                require(hex.isNotEmpty() && hex.length % 2 == 0 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                return CborSpans.parse(hex.hexToByteArray()).payload()
            }
            fun keyPair(name: String, generated: Boolean, suppliedSecret: String?, suppliedPublic: String?, command: String): Pair<String, String> {
                val secret = "$directory/$name.skey"
                val public = "$directory/$name.vkey"
                if (generated) {
                    require(suppliedSecret.isNullOrBlank() && suppliedPublic.isNullOrBlank())
                    if (name == "cold") run("node", command, "--verification-key-file", public, "--signing-key-file", secret,
                        "--operational-certificate-issue-counter", "$directory/cold.counter")
                    else run("node", command, "--verification-key-file", public, "--signing-key-file", secret)
                    connection.commandArgs(listOf("chmod", "600", secret))
                } else {
                    val skey = requireNotNull(suppliedSecret).trim()
                    val vkey = requireNotNull(suppliedPublic).trim()
                    require(skey.isNotEmpty() && skey.length <= 16384 && vkey.isNotEmpty() && vkey.length <= 16384)
                    connection.commandWriteFile(secret, skey)
                    connection.commandArgs(listOf("chmod", "600", secret))
                    connection.commandWriteFile(public, vkey)
                }
                run("key", "verification-key", "--signing-key-file", secret, "--verification-key-file", "$directory/$name.derived.vkey")
                val skey = envelope(secret)
                val vkey = envelope(public)
                require(keyBytes(vkey).contentEquals(keyBytes(envelope("$directory/$name.derived.vkey")))) { "Key pair mismatch" }
                return skey to vkey
            }
            val cold = keyPair("cold", request.generateColdKeys, request.coldSKey, request.coldVKey, "key-gen")
            val vrf = keyPair("vrf", request.generateVRFKeys, request.vrfSKey, request.vrfVKey, "key-gen-VRF")
            val kes = keyPair("kes", request.generateKESKeys, request.kesSKey, request.kesVKey, "key-gen-KES")
            if (!request.generateColdKeys) {
                val supplied = requireNotNull(request.coldCounter).trim()
                require(supplied.isNotEmpty() && supplied.length <= 16384)
                connection.commandWriteFile("$directory/cold.counter", supplied)
            }
            val counter = envelope("$directory/cold.counter")
            val counterHex = json.readTree(counter).path("cborHex").asString()
            require(counterHex.isNotEmpty() && counterHex.length % 2 == 0 && counterHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
            val counterItems = CborSpans.parse(counterHex.hexToByteArray()).array()
            require(counterItems.size == 2 && counterItems[0].integer().signum() >= 0 && counterItems[1].payload().contentEquals(keyBytes(cold.second)))
            val poolId = run("stake-pool", "id", "--cold-verification-key-file", "$directory/cold.vkey", "--output-hex").trim().lowercase()
            require(poolId.matches(Regex("[0-9a-f]{56}")))
            requireQualified()
            val currentEvidence = eras.evidence(observer)
            require(currentEvidence.freshness == "current" && currentEvidence.era == selectedEra &&
                currentEvidence.networkKey == network && currentEvidence.generation == evidence.generation &&
                currentEvidence.source == evidence.source) { "Observer evidence changed during key preparation" }
            val prefix = "pool-draft-${UUID.randomUUID()}"
            fun save(name: String, content: String) = requireNotNull(files.save(File(name = "$prefix-$name", content = content)).id)
            fun saveSecret(name: String, content: String) = save(name, walletUtils.encryptSKeyContent(content, request.spendingPassword))
            fun asset(name: String) = save(name, assets.getValue(name).toString(Charsets.UTF_8))
            val byron = asset("byron-genesis.json")
            val shelley = asset("shelley-genesis.json")
            val alonzo = asset("alonzo-genesis.json")
            val conway = asset("conway-genesis.json")
            val dijkstra = if ("dijkstra-genesis.json" in assets) asset("dijkstra-genesis.json") else null
            val configId = save("config.json", config)
            val topology = configurationFiles.renderTopology(assets)
            val topologyId = asset("topology.json")
            val peerSnapshotId = if ("peer-snapshot.json" in assets) asset("peer-snapshot.json") else null
            val node = nodes.save(Node(hostId = request.hostId, parentId = parent?.id, color = request.color, type = request.type,
                processorThreads = request.processorThreads, name = request.name, listen = request.listen, port = request.port,
                promPort = promPort, tracingHost = request.tracingListen, tracingPort = tracingPort,
                genesisByronFileId = byron, genesisShelleyFileId = shelley, genesisAlonzoFileId = alonzo,
                genesisConwayFileId = conway, genesisDijkstraFileId = dijkstra, configFileId = configId,
                topologyFileId = topologyId, peerSnapshotFileId = peerSnapshotId, networkKeyDiscriminator = observer.networkKeyDiscriminator,
                isDefault = false, isDraft = true, poolId = poolId, poolPledge = request.poolPledge, poolCost = request.poolCost,
                poolMargin = request.poolMargin, ownerStakingAccountId = requireNotNull(owner.id), rewardsStakingAccountId = requireNotNull(reward.id),
                coreSKeyId = saveSecret("node.skey", cold.first), coreVKeyId = save("node.vkey", cold.second), coreCounterId = save("node.counter", counter),
                vrfSKeyId = saveSecret("vrf.skey", vrf.first), vrfVKeyId = save("vrf.vkey", vrf.second),
                kesSKeyId = saveSecret("kes.skey", kes.first), kesVKeyId = save("kes.vkey", kes.second)))
            require(eras.networkKey(node) == network) { "Draft configuration does not match observer network" }
            val id = requireNotNull(node.id)
            relays.saveAll(request.relays.orEmpty().map { Relay(nodeId = id, addr = it.addr, port = it.port) })
            snapshots.save(NodeConfigurationSnapshot(id = UUID.randomUUID().toString(), nodeId = id,
                role = request.type, assetsJson = json.writeValueAsString(
                    assets.mapValues { java.util.Base64.getEncoder().encodeToString(it.value) }), effectiveConfigDigest = configHash,
                effectiveTopologyDigest = digest(topology.toByteArray(Charsets.UTF_8)),
                approvedOverrides = json.writeValueAsString(mapOf("prometheusListen" to request.prometheusListen, "prometheusPort" to promPort,
                    "peerSharing" to false, "maxConcurrencyDeadline" to 2, "listen" to request.listen, "port" to request.port)),
                publicFingerprints = json.writeValueAsString(assets.mapValues { digest(it.value) }), installationObservation = null))
            val publicRequest = json.valueToTree<ObjectNode>(mapOf(
                "host" to request.hostId, "parentId" to request.parentId, "name" to request.name, "type" to request.type,
                "color" to request.color, "processorThreads" to request.processorThreads, "listen" to request.listen,
                "port" to request.port, "prometheusListen" to request.prometheusListen,
                "enableTracingListener" to request.enableTracingListener, "tracingListen" to request.tracingListen,
                "genesisByron" to request.genesisByronFileId, "genesisShelley" to request.genesisShelleyFileId,
                "genesisAlonzo" to request.genesisAlonzoFileId, "genesisConway" to request.genesisConwayFileId,
                "genesisDijkstra" to request.genesisDijkstraFileId, "config" to request.configFileId,
                "topology" to request.topologyFileId, "peerSnapshot" to request.peerSnapshotFileId,
                "registrationFeesAccount" to request.registrationFeesAccount, "ownerStakingAccount" to request.ownerStakingAccount,
                "rewardsStakingAccount" to request.rewardsStakingAccount, "poolPledge" to request.poolPledge,
                "poolCost" to request.poolCost, "poolMargin" to request.poolMargin, "relays" to request.relays,
                "metadata" to request.metadata,
            ))
            (publicRequest.path("metadata").path("extended").path("itn") as? ObjectNode)?.remove("privateKey")
            jdbc.update("INSERT INTO pool_draft_config(node_id, request_json, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)", id, json.writeValueAsString(publicRequest))
            return node
        } finally {
            connection.commandArgs(listOf("rm", "-rf", directory))
        }
    }
}
