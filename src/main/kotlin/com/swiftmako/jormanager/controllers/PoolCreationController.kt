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
import com.swiftmako.jormanager.repositories.LeiosKeyRepository
import com.swiftmako.jormanager.services.LeiosKeyService
import com.swiftmako.jormanager.services.PoolDeploymentService
import com.swiftmako.jormanager.model.LeiosKeyRequest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.data.domain.Sort
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
data class PoolSetupContextRequest(val requestId: String, val nodeId: Long)
data class PoolSetupKey(val keyId: Long, val publicKeyHex: String)
data class PoolSetupContext(val requestId: String, val nodeId: Long, val hostId: Long, val observerNodeId: Long,
    val networkKey: String, val configSha256: String, val request: tools.jackson.databind.JsonNode,
    val keyId: Long?, val registrationRequestId: String?, val keys: List<PoolSetupKey>, val setupRequestId: String?)

@Controller
class PoolCreationController(private val service: PoolCreationService, private val nodeController: NodeController,
    private val nodes: NodeRepository, private val messages: SimpMessagingTemplate) {
    @MessageMapping("/createnode")
    fun create(request: CreateNodeRequest) {
        if (request.type == "relay") {
            nodeController.createNode(request)
            return
        }
        try {
            val node = service.create(request)
            messages.convertAndSend("/topic/messages", SocketResponse.Success("nodes", nodes.findAll(Sort.by(Sort.Direction.ASC, "name")).filter { !it.isDeleted }))
            messages.convertAndSend("/topic/messages", SocketResponse.Success("createnode", com.swiftmako.jormanager.model.CreateNodeResult(request.requestId, requireNotNull(node.id), "${node.name} created successfully!")))
        } catch (e: PoolCreationFailure) {
            messages.convertAndSend("/topic/messages", SocketResponse.Error("createnode", SocketResponse.PublicFailure(
                "PoolCreationFailed", e.message ?: "Pool setup failed", "createnode", e.disposition, request.requestId)))
        } catch (_: Exception) {
            messages.convertAndSend("/topic/messages", SocketResponse.Error("createnode", SocketResponse.PublicFailure(
                "PoolCreationFailed", "Pool setup failed; inspect protected server diagnostics.", "createnode", "retry", request.requestId)))
        }
    }

    @MessageMapping("/node/setup-context")
    @SendTo("/topic/messages")
    fun context(request: PoolSetupContextRequest): SocketResponse<PoolSetupContext> = try {
        SocketResponse.Success("node_setup_context", service.context(request))
    } catch (_: Exception) {
        SocketResponse.Error("node_setup_context", SocketResponse.PublicFailure(
            "PoolSetupContextUnavailable", "Setup or matching observer is unavailable",
            "node_setup_context", "retry", request.requestId))
    }
}

class PoolCreationFailure(message: String, val disposition: String = "wait") : IllegalStateException(message)

@Service
class PoolCreationService(
    private val nodes: NodeRepository,
    private val hosts: HostRepository,
    private val files: FileRepository,
    private val wallets: WalletRepository,
    private val relays: RelayRepository,
    private val snapshots: NodeConfigurationSnapshotRepository,
    private val walletUtils: WalletUtils,
    private val configurationFiles: NodeConfigurationFiles,
    private val eras: CardanoRepository,
    private val nodeController: NodeController,
    private val json: ObjectMapper,
    private val jdbc: JdbcTemplate,
    private val keys: LeiosKeyRepository,
    private val keyService: LeiosKeyService,
    private val registration: PoolRegistrationController,
    private val installer: PoolDeploymentService,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)
    private fun digest(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).toHexString()
    private data class Journal(val id: String, val requestId: String, val nodeId: Long, val fingerprint: String,
        val details: ObjectNode, val state: String)

    private fun journal(sql: String, argument: Any): Journal? = jdbc.query(sql, { rs, _ ->
        Journal(rs.getString("id"), rs.getString("request_id"), rs.getLong("node_id"), rs.getString("fingerprint"),
            json.readTree(rs.getString("details")) as ObjectNode, rs.getString("state"))
    }, argument).firstOrNull()
    private fun journalForRequest(id: String) = journal("SELECT * FROM pool_deployment_previews WHERE request_id=?", id)
    private fun journalForNode(id: Long) = journal("SELECT * FROM pool_deployment_previews WHERE node_id=? ORDER BY created_at DESC", id)

    private fun observerEra(observer: Node): String {
        val host = requireNotNull(hosts.findByIdOrNull(observer.hostId))
        val genesis = json.readTree(requireNotNull(files.findByIdOrNull(observer.genesisShelleyFileId)).content)
        val magic = if (genesis.path("networkId").asString().equals("mainnet", true)) "--mainnet"
            else "--testnet-magic ${genesis.path("networkMagic").asString().also { require(it.matches(Regex("[0-9]+"))) }}"
        return eras.getEra(host, observer, magic, "--socket-path ${host.nodeHomePath}/${observer.name}/db/socket")
            .also { require(it in setOf("conway", "dijkstra")) }
    }

    private fun intent(request: CreateNodeRequest): ObjectNode {
        val result = json.valueToTree<ObjectNode>(mapOf(
            "host" to request.hostId, "parentId" to request.parentId, "name" to request.name, "type" to request.type,
            "color" to request.color, "isDefault" to request.isDefault, "processorThreads" to request.processorThreads,
            "listen" to request.listen, "port" to request.port, "prometheusListen" to request.prometheusListen,
            "enableTracingListener" to request.enableTracingListener, "tracingListen" to request.tracingListen,
            "genesisByron" to request.genesisByronFileId, "genesisShelley" to request.genesisShelleyFileId,
            "genesisAlonzo" to request.genesisAlonzoFileId, "genesisConway" to request.genesisConwayFileId,
            "genesisDijkstra" to request.genesisDijkstraFileId, "config" to request.configFileId,
            "topology" to request.topologyFileId, "peerSnapshot" to request.peerSnapshotFileId,
            "generateColdKeys" to request.generateColdKeys, "coldVKey" to request.coldVKey, "coldCounter" to request.coldCounter,
            "generateVRFKeys" to request.generateVRFKeys, "vrfVKey" to request.vrfVKey,
            "generateKESKeys" to request.generateKESKeys, "kesVKey" to request.kesVKey,
            "registrationFeesAccount" to request.registrationFeesAccount, "ownerStakingAccount" to request.ownerStakingAccount,
            "rewardsStakingAccount" to request.rewardsStakingAccount, "poolPledge" to request.poolPledge,
            "poolCost" to request.poolCost, "poolMargin" to request.poolMargin, "relays" to request.relays,
            "metadata" to request.metadata))
        (result.path("metadata").path("extended").path("itn") as? ObjectNode)?.remove("privateKey")
        return result
    }

    private fun insertJournal(requestId: String, node: Node, intent: ObjectNode) {
        val details = json.createObjectNode()
        details.set("intent", intent)
        val fingerprint = digest(json.writeValueAsBytes(intent))
        jdbc.update("INSERT INTO pool_deployment_previews(id,request_id,node_id,host_id,fingerprint,details,state,created_at) VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
            UUID.randomUUID().toString(), requestId, requireNotNull(node.id), node.hostId, fingerprint, json.writeValueAsString(details), "prepared")
    }

    private fun saveDetails(row: Journal) {
        jdbc.update("UPDATE pool_deployment_previews SET details=? WHERE id=?", json.writeValueAsString(row.details), row.id)
    }

    @Synchronized
    fun create(request: CreateNodeRequest): Node {
        UUID.fromString(request.requestId)
        require(walletUtils.isValidSpendingPassword(request.spendingPassword)) { "Authorization required" }
        val connection = requireNotNull(jdbc.dataSource).connection
        val locks = listOf("pool-setup-request:${request.requestId}") +
            listOfNotNull(request.resumeNodeId?.let { "pool-setup-node:$it" })
        val acquired = mutableListOf<String>()
        try {
            locks.forEach { key ->
                connection.prepareStatement("SELECT pg_advisory_lock(hashtextextended(?, 0))").use {
                    it.setString(1, key)
                    it.execute()
                }
                acquired.add(key)
            }
            return createLocked(request)
        } finally {
            try {
                acquired.asReversed().forEach { key ->
                    connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))").use {
                        it.setString(1, key)
                        it.execute()
                    }
                }
            } finally { connection.close() }
        }
    }

    private fun createLocked(request: CreateNodeRequest): Node {
        val requestedIntent = intent(request)
        val persistedIntent = json.readTree(json.writeValueAsBytes(requestedIntent))
        var row = journalForRequest(request.requestId)
        if (row == null) {
            try {
                transaction.executeWithoutResult {
                    if (request.resumeNodeId == null) {
                        prepare(request)
                    } else {
                        val node = requireNotNull(nodes.findByIdOrNull(request.resumeNodeId))
                        require(node.isDraft && !node.isDeleted && node.hostId == request.hostId && node.name == request.name &&
                            node.type == request.type && node.parentId == request.parentId && !node.poolId.isNullOrBlank() &&
                            node.ownerStakingAccountId == request.ownerStakingAccount &&
                            node.rewardsStakingAccountId == request.rewardsStakingAccount &&
                            node.poolPledge == request.poolPledge && node.poolCost == request.poolCost &&
                            node.poolMargin?.toBigDecimal()?.compareTo(request.poolMargin.toBigDecimal()) == 0)
                        val previous = journalForNode(request.resumeNodeId)
                        require(previous == null || !previous.details.path("intent").isObject) { "Resume with the original request ID" }
                        if (previous != null && previous.state != "previewed") {
                            throw PoolCreationFailure("Pool setup is ${previous.state}; inspect the host and operational certificate counter before retrying.")
                        }
                        val saved = jdbc.query("SELECT request_json FROM pool_draft_config WHERE node_id=?", { rs, _ -> rs.getString(1) }, request.resumeNodeId).singleOrNull()
                        if (saved != null) {
                            json.readTree(saved).properties().forEach { (name, value) ->
                                require(persistedIntent.get(name) == value) { "Saved pool setup differs from the requested setup" }
                            }
                        }
                        val observer = requireNotNull(nodes.findDefault())
                        require(eras.networkKey(node) == eras.networkKey(observer))
                        insertJournal(request.requestId, node, requestedIntent)
                    }
                }
            } catch (e: org.springframework.dao.DuplicateKeyException) {
                if (journalForRequest(request.requestId) == null) throw e
            }
            row = requireNotNull(journalForRequest(request.requestId))
        }
        require(row.details.path("intent") == persistedIntent && row.fingerprint == digest(json.writeValueAsBytes(requestedIntent))) { "Request ID already used" }
        require(request.resumeNodeId == null || request.resumeNodeId == row.nodeId)
        val node = requireNotNull(nodes.findByIdOrNull(row.nodeId))
        if (row.state == "installed" && !node.isDraft) return node
        if (row.state in setOf("installing", "failed", "installed_startup_failed")) {
            throw PoolCreationFailure("Pool setup is ${row.state}; inspect the host and operational certificate counter before retrying.")
        }
        val observer = requireNotNull(nodes.findDefault())
        val network = eras.networkKey(node)
        require(!observer.isDraft && !observer.isDeleted && eras.networkKey(observer) == network)
        val era = observerEra(observer)
        val keyId = if (era == "dijkstra") {
            val selectedId = requireNotNull(transaction.execute {
                val locked = requireNotNull(journal("SELECT * FROM pool_deployment_previews WHERE id=? FOR UPDATE", row.id))
                val available = keys.findByNetworkKeyAndPoolId(network, requireNotNull(node.poolId))
                    .filter { it.signingFileId != null && it.publicKey.size == 96 && it.proof.size == 48 &&
                        it.validationProvenance.isNotBlank() }
                val savedId = locked.details.path("keyId").takeIf { it.isIntegralNumber }?.asLong()
                val selected = savedId ?: request.keyId
                require(savedId == null || request.keyId == null || savedId == request.keyId)
                val key = if (selected != null) available.singleOrNull { it.id == selected }
                    ?: throw IllegalArgumentException("Selected voting key unavailable")
                else if (available.size == 1) available.single()
                else if (available.isNotEmpty()) throw PoolCreationFailure("Multiple voting keys exist; select a key to resume setup.", "retry")
                else keyService.prepare(LeiosKeyRequest(UUID.randomUUID().toString(),
                    LeiosController.Target(node.hostId, requireNotNull(node.id), node.poolId, network), "prepare",
                    request.spendingPassword, observerNodeId = observer.id), requireNotNull(hosts.findByIdOrNull(node.hostId)), node)
                requireNotNull(key.id).also { locked.details.put("keyId", it); saveDetails(locked) }
            })
            row.details.put("keyId", selectedId)
            selectedId
        } else {
            require(request.keyId == null && !row.details.path("keyId").isIntegralNumber)
            null
        }
        val registrationId = row.details.path("registrationRequestId").takeIf { it.isString }?.asString()
            ?: UUID.randomUUID().toString().also { row.details.put("registrationRequestId", it); saveDetails(row) }
        val savedTxId = row.details.path("transactionId").takeIf { it.isString }?.asString()
        val outcome = if (savedTxId != null && row.details.path("registrationState").asString() in setOf("submitted", "confirmed")) {
            "submitted" to savedTxId
        } else {
            val result = registration.execute(PoolRegistrationController.SubmitRequest(registrationId, row.nodeId,
                requireNotNull(request.registrationFeesAccount), keyId, "register", metadata = request.metadata,
                spendingPassword = request.spendingPassword))
            require(result.state == "submitted") { "Registration transaction ${result.txId} was not acknowledged" }
            row.details.put("registrationState", "submitted")
            row.details.put("transactionId", result.txId)
            result.publication?.let { row.details.set("publication", json.valueToTree(it)) }
            saveDetails(row)
            "submitted" to result.txId
        }
        jdbc.update("UPDATE pool_deployment_previews SET state='registered' WHERE id=? AND state='prepared'", row.id)
        val result = try {
            installer.install(row.nodeId, keyId, row.id, request.spendingPassword, request.sudoPassword)
        } catch (_: Exception) {
            throw PoolCreationFailure("Registration transaction ${outcome.second} is ${outcome.first}; setup failed. Inspect the host and operational certificate counter before retrying.")
        }
        if (result.state != "installed") throw PoolCreationFailure("Registration transaction ${outcome.second} is ${outcome.first}; setup is ${result.state}.")
        return requireNotNull(nodes.findByIdOrNull(row.nodeId))
    }

    fun context(request: PoolSetupContextRequest): PoolSetupContext {
        require(request.requestId.isNotBlank())
        val node = requireNotNull(nodes.findByIdOrNull(request.nodeId))
        require(node.isDraft && !node.isDeleted)
        val observer = requireNotNull(nodes.findDefault())
        require(!observer.isDeleted && !observer.isDraft)
        val network = eras.networkKey(node)
        require(eras.networkKey(observer) == network)
        val era = observerEra(observer)
        val config = requireNotNull(files.findByIdOrNull(node.configFileId)).content
        val row = journalForNode(request.nodeId)?.takeIf { it.details.path("intent").isObject }
        val saved = jdbc.query("SELECT request_json FROM pool_draft_config WHERE node_id=?", { rs, _ -> rs.getString(1) }, request.nodeId).singleOrNull()
        val intent = row?.details?.path("intent")?.takeIf { it.isObject } ?: saved?.let { json.readTree(it) } ?: json.createObjectNode()
        val available = if (era == "dijkstra") keys.findByNetworkKeyAndPoolId(network, requireNotNull(node.poolId))
            .filter { it.signingFileId != null && it.publicKey.size == 96 && it.proof.size == 48 &&
                it.validationProvenance.isNotBlank() }
            .map { PoolSetupKey(requireNotNull(it.id), it.publicKey.toHexString()) } else emptyList()
        return PoolSetupContext(request.requestId, request.nodeId, node.hostId, requireNotNull(observer.id), network,
            digest(config.toByteArray(Charsets.UTF_8)), intent,
            row?.details?.path("keyId")?.takeIf { it.isIntegralNumber }?.asLong(),
            row?.details?.path("registrationRequestId")?.takeIf { it.isString }?.asString(), available, row?.requestId)
    }

    private fun prepare(request: CreateNodeRequest): Node {
        require(walletUtils.isValidSpendingPassword(request.spendingPassword)) { "Authorization required" }
        require(request.type in setOf("core", "pool") && !request.isDefault && request.name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*")))
        require(request.poolPledge != null && request.poolPledge.signum() >= 0 && request.poolCost != null && request.poolCost.signum() >= 0)
        requireNotNull(request.metadata) { "Pool metadata is required" }
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
        val network = eras.networkKey(observer)
        val selectedEra = observerEra(observer)
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
            val publicRequest = intent(request)
            jdbc.update("INSERT INTO pool_draft_config(node_id, request_json, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)", id, json.writeValueAsString(publicRequest))
            insertJournal(request.requestId, node, publicRequest)
            return node
        } finally {
            connection.commandArgs(listOf("rm", "-rf", directory))
        }
    }
}
