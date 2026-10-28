package com.swiftmako.jormanager.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.swiftmako.jormanager.controllers.NodeController
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.KeyInstallation
import com.swiftmako.jormanager.entities.LeiosVotingKey
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.KeyInstallationRepository
import com.swiftmako.jormanager.repositories.LeiosKeyRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.NodeConfigurationSnapshotRepository
import com.swiftmako.jormanager.utils.CborSpans
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.Base64
import kotlinx.coroutines.flow.MutableSharedFlow
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller
import org.springframework.stereotype.Service
import com.swiftmako.jormanager.entities.SocketResponse

data class PoolDeploymentPreviewRequest(val requestId: String, val nodeId: Long, val keyId: Long? = null)
data class PoolDeploymentInstallRequest(val requestId: String, val previewId: String, val nodeId: Long, val keyId: Long? = null,
    val approved: Boolean, val spendingPassword: String, val sudoPassword: String = "") {
    override fun toString() = "PoolDeploymentInstallRequest(requestId=$requestId, previewId=$previewId, nodeId=$nodeId, keyId=$keyId, approved=$approved, passwords='********')"
}

@Controller
class PoolDeploymentController(private val service: PoolDeploymentService) {
    @MessageMapping("/node/pool-deployment-preview")
    @SendTo("/topic/messages")
    fun preview(request: PoolDeploymentPreviewRequest): SocketResponse<PoolDeploymentService.Preview> = try {
        SocketResponse.Success("node_pool_deployment_preview", service.preview(request))
    } catch (e: Exception) {
        SocketResponse.Error("node_pool_deployment_preview", SocketResponse.PublicFailure(e.javaClass.simpleName,
            "Deployment review unavailable; inspect protected diagnostics and registration status.", "node_pool_deployment_preview", "review", request.requestId))
    }

    @MessageMapping("/node/pool-deployment-install")
    @SendTo("/topic/messages")
    fun install(request: PoolDeploymentInstallRequest): SocketResponse<PoolDeploymentService.Result> = try {
        SocketResponse.Success("node_pool_deployment_install", service.install(request))
    } catch (e: Exception) {
        SocketResponse.Error("node_pool_deployment_install", SocketResponse.PublicFailure(e.javaClass.simpleName,
            "Deployment failed or its outcome is uncertain; inspect the host before retrying.", "node_pool_deployment_install", "reconcile", request.requestId))
    }
}

@Service
class PoolDeploymentService(
    private val nodes: NodeRepository, private val hosts: HostRepository, private val files: FileRepository,
    private val keys: LeiosKeyRepository, private val installations: KeyInstallationRepository,
    private val eras: CardanoRepository, private val capabilities: CardanoCapabilities,
    private val bundles: NetworkBundleValidator, private val walletUtils: WalletUtils,
    private val snapshots: NodeConfigurationSnapshotRepository,
    private val nodeController: NodeController, private val jdbc: JdbcTemplate, private val json: ObjectMapper,
    @param:Qualifier("nodesChannel") private val nodesChannel: MutableSharedFlow<Node>,
) {
    data class Preview(val requestId: String, val previewId: String, val nodeId: Long, val hostId: Long,
        val parentId: Long?, val networkKey: String, val poolId: String, val keyId: Long?, val publicFingerprint: String?,
        val configurationFingerprint: String, val paths: List<String>, val mode: String, val consequences: String)
    data class Result(val requestId: String, val previewId: String, val nodeId: Long, val state: String,
        val installedFingerprint: String?, val loadedFingerprint: String?, val reason: String)
    private data class Context(val node: Node, val host: Host, val observer: Node, val observerHost: Host,
        val key: LeiosVotingKey?, val parent: Node?, val connection: HostConnection, val network: String,
        val folder: String, val startup: String, val systemd: Boolean, val assets: Map<String, ByteArray>,
        val catalogPrefix: String?, val era: String, val config: String, val fingerprint: String, val tipSlot: Long)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHexString()
    private fun publicHash(key: LeiosVotingKey) = MessageDigest.getInstance("SHA-256").digest(key.publicKey).toHexString()
    private fun safe(value: String) = value.also { require(it.matches(Regex("[A-Za-z0-9_./-]+")) && !it.startsWith("-") && !it.split('/').contains("..")) }
    private fun file(id: Long) = requireNotNull(files.findByIdOrNull(id))
    private fun remoteHash(c: HostConnection, path: String): String = if (c.commandFileExists(path)) {
        c.commandArgs(listOf("sha256sum", path)).trim().substringBefore(' ').also { require(it.matches(Regex("[0-9a-f]{64}"))) }
    } else "absent"

    private fun context(nodeId: Long, keyId: Long?): Context {
        val n = requireNotNull(nodes.findByIdOrNull(nodeId)); require(n.isDraft && !n.isDeleted && n.type in setOf("core", "pool"))
        val host = requireNotNull(hosts.findByIdOrNull(n.hostId)); val observer = requireNotNull(nodes.findDefault())
        val observerHost = requireNotNull(hosts.findByIdOrNull(observer.hostId))
        val network = eras.networkKey(n); require(!observer.isDraft && !observer.isDeleted && eras.networkKey(observer) == network)
        val evidence = eras.evidence(observer)
        val era = requireNotNull(evidence.era)
        require(evidence.freshness == "current" && era in setOf("dijkstra", "conway") && evidence.networkKey == network && evidence.source == "host:${observerHost.id}/node:${observer.id}")
        val bundle = n.configurationBundleId?.let { bundles.resolve(it, requireNotNull(n.configurationResetId)) }
        require((era == "dijkstra") == (bundle != null))
        val assets = if (bundle != null) bundle.assets else {
            require(n.configurationResetId == null && n.genesisDijkstraFileId == null)
            val snapshot = snapshots.findByNodeId(nodeId).single()
            val encoded = json.readTree(snapshot.assetsJson)
            val decoded = encoded.fields().asSequence().associate { it.key to Base64.getDecoder().decode(it.value.asText()) }
            val fingerprints = json.readTree(snapshot.publicFingerprints)
            require(decoded.keys == setOf("byron-genesis.json", "shelley-genesis.json", "alonzo-genesis.json", "conway-genesis.json", "config.json", "topology.json"))
            decoded.forEach { (name, content) ->
                require(MessageDigest.getInstance("SHA-256").digest(content).toHexString() == fingerprints.path(name).asText())
            }
            require(snapshot.effectiveConfigDigest == hash(file(n.configFileId).content))
            decoded
        }
        val key = if (era == "dijkstra") requireNotNull(keyId?.let(keys::findByIdOrNull)) else null
        require(era != "conway" || keyId == null)
        val pool = requireNotNull(n.poolId)
        if (key != null) require(key.networkKey == network && key.poolId == pool && key.signingFileId != null && key.validationProvenance.isNotBlank() && key.publicKey.size == 96 && key.proof.size == 48)
        val config = file(n.configFileId).content
        if (bundle != null) capabilities.requireHostOperation(host, observerHost, observer, network, hash(config), "node.create")
        else capabilities.requireOperation(observerHost, observer, "node.create")
        val confirmed = jdbc.query("SELECT request_payload FROM authorized_operations WHERE network_key=? AND operation='pool.register' AND state='confirmed'", { rs, _ -> rs.getString(1) }, network)
            .any { payload -> json.readTree(payload).let { it.path("nodeId").asLong(-1) == nodeId && (if (keyId == null) it.path("keyId").isNull else it.path("keyId").asLong(-1) == keyId) && it.path("poolId").asText() == pool } }
        require(confirmed) { "Confirmed reviewed registration required" }
        val genesis = json.readTree(file(observer.genesisShelleyFileId).content)
        val flags = if (genesis.path("networkId").asText().equals("mainnet", true)) listOf("--mainnet") else listOf("--testnet-magic", genesis.path("networkMagic").asText().also { require(it.matches(Regex("[0-9]+"))) })
        val socket = safe("${observerHost.nodeHomePath}/${observer.name}/db/socket")
        val oc = HostConnection(observerHost, observer)
        fun query(vararg args: String) = json.readTree(oc.commandArgs(listOf(safe(observerHost.cardanoCliPath), era, "query") + args + flags + listOf("--socket-path", socket, "--output-json")))
        val state = query("pool-state", "--stake-pool-id", pool)
        require(state.isObject && state.size() == 1)
        val entry = state.fields().next(); require(entry.key.equals(pool, true))
        require(entry.value.isObject && entry.value.path("deposit").isIntegralNumber) { "Registered pool state unavailable" }
        if (key != null) require(listOf(
            entry.value.path("blsKey").path("bksKey").path("blsPubKey").asText(),
            entry.value.path("futureStakePoolParams").path("blsKey").path("blsPubKey").asText()
        ).any { it.equals(key.publicKey.toHexString(), true) }) { "Registered pool key does not match the selected key" }
        val tip = query("tip").path("slot").takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong() ?: error("Tip slot unavailable")
        require(eras.evidence(observer) == evidence) { "Era evidence changed" }
        val parent = if (n.type == "pool") requireNotNull(n.parentId?.let(nodes::findByIdOrNull)).also {
            require(!it.isDeleted && !it.isDraft && it.type == "core" && it.hostId == n.hostId && it.configurationBundleId == n.configurationBundleId && it.configurationResetId == n.configurationResetId && eras.networkKey(it) == network)
        } else null
        val connection = HostConnection(host, parent); val folder = safe("${host.nodeHomePath}/${parent?.name ?: n.name}")
        safe(host.cardanoNodePath); safe(host.cardanoCliPath); require(n.name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*")))
        val systemd = connection.hasSystemd
        val tracingHost = n.tracingHost
        require(n.listen.matches(Regex("[A-Za-z0-9.:-]+")) && (tracingHost == null || tracingHost.matches(Regex("[A-Za-z0-9.:-]+"))))
        val startup = safe(if (systemd) "/etc/systemd/system/${parent?.name ?: n.name}-node.service" else "$folder/startNode.sh")
        if (parent == null) {
            require(!connection.commandFileExists(folder) && (!systemd || !connection.commandFileExists(startup))) { "Target directory or service already exists" }
            val occupied = connection.commandArgs(listOf("ss", "-tulw"))
            require(listOfNotNull(n.port, n.promPort, n.tracingPort).none { Regex("[:.]$it(?:\\s|$)").containsMatchIn(occupied) }) { "Reviewed port is already in use" }
        }
        else {
            require(connection.commandReadFile("$folder/config.json") == config) { "Parent configuration changed" }
            listOf("byron-genesis.json", "shelley-genesis.json", "alonzo-genesis.json", "conway-genesis.json", "dijkstra-genesis.json", "peer-snapshot.json").filter { it in assets }.forEach { name ->
                require(remoteHash(connection, "$folder/$name") == hash(assets.getValue(name).toString(Charsets.UTF_8))) { "Parent bundle differs from selected snapshot" }
            }
            require(connection.commandFileExists(requireNotNull(startup)))
        }
        if (systemd) {
            val owner = connection.commandArgs(listOf("id", "-un")).trim()
            if (parent == null) require(host.sshUser == owner) { "Service user differs from credential owner" }
            else require(Regex("(?m)^User=([^\\r\\n]+)$").find(connection.commandReadFile(startup))?.groupValues?.get(1) == owner) { "Service user differs from credential owner" }
        }
        val assetIds = listOf(n.genesisByronFileId, n.genesisShelleyFileId, n.genesisAlonzoFileId, n.genesisConwayFileId) + listOfNotNull(n.genesisDijkstraFileId)
        listOf("byron-genesis.json", "shelley-genesis.json", "alonzo-genesis.json", "conway-genesis.json", "dijkstra-genesis.json").take(assetIds.size).zip(assetIds).forEach { (name, id) -> require(file(id).content.toByteArray().contentEquals(assets.getValue(name))) }
        val ids = assetIds + listOf(n.configFileId, n.coreSKeyId, n.coreVKeyId, n.coreCounterId, n.vrfSKeyId, n.vrfVKeyId, n.kesSKeyId, n.kesVKeyId) + listOfNotNull(key?.signingFileId)
        val relevant = ids.joinToString("|") { "$it:${hash(file(it).content)}" }
        val existing = if (parent == null) "absent" else listOf(startup, "$folder/env", "$folder/credentials.json",
            "$folder/${parent.name}.kes.skey", "$folder/${parent.name}.vrf.skey", "$folder/${parent.name}.node.opcert")
            .joinToString("|") { "$it:${remoteHash(connection, it)}" } + "|" +
            listOf(parent.kesSKeyId, parent.vrfSKeyId, parent.opcertId).joinToString("|") { "$it:${hash(file(it).content)}" }
        val fingerprint = hash(json.writeValueAsString(n) + "|" + (key?.let(::publicHash) ?: "no-bls") + "|" + relevant + "|" + existing + "|" + evidence.generation + "|" + assets.entries.sortedBy { it.key }.joinToString("|") { it.key + ":" + MessageDigest.getInstance("SHA-256").digest(it.value).toHexString() })
        return Context(n, host, observer, observerHost, key, parent, connection, network, folder, startup, systemd, assets, bundle?.bundle?.catalogPrefix, era, config, fingerprint, tip)
    }

    fun preview(request: PoolDeploymentPreviewRequest): Preview {
        UUID.fromString(request.requestId)
        val c = context(request.nodeId, request.keyId)
        val id = UUID.randomUUID().toString()
        val paths = if (c.parent == null) listOf(c.folder, c.startup) + (if (c.key != null) listOf("${c.folder}/${c.node.name}.bls.skey") else emptyList()) else listOf(c.folder, c.startup, "${c.folder}/env", "${c.folder}/credentials.json")
        val details = json.writeValueAsString(mapOf("nodeId" to request.nodeId, "keyId" to request.keyId, "paths" to paths))
        jdbc.update("INSERT INTO pool_deployment_previews(id,request_id,node_id,host_id,fingerprint,details,state,created_at) VALUES (?,?,?,?,?,?,?,?)", id, request.requestId, request.nodeId, c.host.id, c.fingerprint, details, "previewed", java.sql.Timestamp.from(Instant.now()))
        return Preview(request.requestId, id, request.nodeId, c.node.hostId, c.node.parentId, c.network, requireNotNull(c.node.poolId), request.keyId,
            c.key?.let(::publicHash), c.fingerprint, paths, if (c.parent == null) (if (c.systemd) "systemd-singleton" else "manual-singleton") else "bulk",
            "Issues an operational certificate and installs producer configuration and credentials. No transaction is signed or submitted; the producer is not restarted. Loaded key remains unobserved.")
    }

    fun install(request: PoolDeploymentInstallRequest): Result {
        UUID.fromString(request.requestId); require(request.approved && walletUtils.isValidSpendingPassword(request.spendingPassword))
        val c = context(request.nodeId, request.keyId)
        val row = jdbc.query("SELECT node_id,host_id,fingerprint,details,state FROM pool_deployment_previews WHERE id=?", { rs, _ -> (1..5).map { rs.getString(it) } }, request.previewId).singleOrNull() ?: error("Preview not found")
        val reviewedKey = json.readTree(row[3]).path("keyId")
        require(row[0] == request.nodeId.toString() && row[1] == c.node.hostId.toString() && row[2] == c.fingerprint && row[4] == "previewed" && (if (request.keyId == null) reviewedKey.isNull else reviewedKey.asLong(-1) == request.keyId))
        val n = c.node; val connection = c.connection
        val kes = walletUtils.getSKeyContent(file(n.kesSKeyId), request.spendingPassword)
        val vrf = walletUtils.getSKeyContent(file(n.vrfSKeyId), request.spendingPassword)
        val cold = walletUtils.getSKeyContent(file(n.coreSKeyId), request.spendingPassword)
        val bls = c.key?.let { walletUtils.getSKeyContent(file(requireNotNull(it.signingFileId)), request.spendingPassword) }
        val blsJson = bls?.let { json.readTree(it).also { value -> require(value.path("type").asText() == "BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size" && value.path("cborHex").asText().matches(Regex("5820[0-9a-fA-F]{64}"))) } }
        require(jdbc.update("UPDATE pool_deployment_previews SET state='installing' WHERE id=? AND state='previewed'", request.previewId) == 1)
        val prefix = "/tmp/jormanager-deploy-${UUID.randomUUID()}"
        val temporary = listOf("$prefix.cold", "$prefix.counter", "$prefix.kes.vkey", "$prefix.opcert", "$prefix.bls", "$prefix.bls.vkey")
        try {
            require(context(request.nodeId, request.keyId).fingerprint == c.fingerprint)
            connection.commandWriteSecret("$prefix.cold", cold)
            if (bls != null) {
                connection.commandWriteSecret("$prefix.bls", bls)
                connection.commandArgs(listOf(safe(c.host.cardanoCliPath), c.era, "key", "verification-key", "--signing-key-file", "$prefix.bls", "--verification-key-file", "$prefix.bls.vkey"))
                val derived = json.readTree(connection.commandReadFile("$prefix.bls.vkey")).path("cborHex").asText().hexToByteArray()
                require(CborSpans.parse(derived).payload().contentEquals(requireNotNull(c.key).publicKey))
            }
            connection.commandWriteFile("$prefix.counter", file(n.coreCounterId).content)
            connection.commandWriteFile("$prefix.kes.vkey", file(n.kesVKeyId).content)
            val periods = json.readTree(file(n.genesisShelleyFileId).content).path("slotsPerKESPeriod").asLong()
            require(periods > 0 && c.tipSlot >= 0)
            connection.commandArgs(listOf(safe(c.host.cardanoCliPath), c.era, "node", "issue-op-cert", "--hot-kes-verification-key-file", "$prefix.kes.vkey", "--cold-signing-key-file", "$prefix.cold", "--operational-certificate-issue-counter", "$prefix.counter", "--kes-period", (c.tipSlot / periods).toString(), "--out-file", "$prefix.opcert"))
            val counter = connection.commandReadFile("$prefix.counter")
            files.save(file(n.coreCounterId).copy(content = counter))
            val cert = connection.commandReadFile("$prefix.opcert")
            val certId = requireNotNull(files.save(File(name = "${n.name}.node.opcert", content = cert)).id)
            fun writePrivate(path: String, content: String) {
                val temp = "/tmp/jormanager-deploy-${UUID.randomUUID()}"
                try {
                    connection.commandWriteSecret(temp, content)
                    connection.commandArgs(listOf("install", "-m", "600", temp, safe(path)))
                    require(connection.commandArgs(listOf("stat", "-c", "%U:%a", path)).trim() == "${connection.commandArgs(listOf("id", "-un")).trim()}:600")
                    require(remoteHash(connection, path) == hash(content))
                } finally { connection.commandArgs(listOf("rm", "-f", temp)) }
            }
            if (c.parent == null) {
                connection.commandArgs(listOf("mkdir", c.folder))
                connection.commandArgs(listOf("mkdir", "${c.folder}/db"))
                val assets = listOf("byron-genesis.json", "shelley-genesis.json", "alonzo-genesis.json", "conway-genesis.json", "dijkstra-genesis.json", "peer-snapshot.json").filter { it in c.assets }
                assets.forEach { name -> val content = c.assets.getValue(name).toString(Charsets.UTF_8); connection.commandWriteFile("${c.folder}/$name", content); require(remoteHash(connection, "${c.folder}/$name") == hash(content)) }
                val rawTopology = c.assets.getValue("topology.json").toString(Charsets.UTF_8)
                val topology = c.catalogPrefix?.let { rawTopology.replace("$it-peer-snapshot.json", "peer-snapshot.json").replace("musashi-peer-snapshot.json", "peer-snapshot.json") } ?: rawTopology
                listOf("topology.json" to topology, "config.json" to c.config).forEach { (name, content) -> connection.commandWriteFile("${c.folder}/$name", content); require(remoteHash(connection, "${c.folder}/$name") == hash(content)) }
                writePrivate("${c.folder}/${n.name}.kes.skey", kes); writePrivate("${c.folder}/${n.name}.vrf.skey", vrf)
                writePrivate("${c.folder}/${n.name}.node.opcert", cert)
                if (bls != null) writePrivate("${c.folder}/${n.name}.bls.skey", bls)
                val env = nodeController.renderEnvContent("core", n.name, c.folder, n.listen, n.port, n.tracingHost ?: "127.0.0.1", n.tracingPort)
                connection.commandWriteFile("${c.folder}/env", env); require(remoteHash(connection, "${c.folder}/env") == hash(env))
                if (c.systemd) {
                    val base = nodeController.renderSystemdContent("core", c.host, n.name, n.processorThreads, n.tracingHost ?: "127.0.0.1", n.tracingPort)
                    val startup = if (bls != null) base.replace("--shelley-operational-certificate", "--shelley-bls-key ${c.folder}/${n.name}.bls.skey \\\n  --shelley-operational-certificate") else base
                    val temp = "/tmp/jormanager-deploy-${UUID.randomUUID()}"
                    try {
                        connection.commandWriteSecret(temp, startup)
                        connection.sudoCommand(listOf("install", "-m", "644", temp, requireNotNull(c.startup)), request.sudoPassword)
                        connection.sudoCommand(listOf("systemctl", "daemon-reload"), request.sudoPassword)
                        require(remoteHash(connection, c.startup) == hash(startup))
                    } finally { connection.commandArgs(listOf("rm", "-f", temp)) }
                } else {
                    val trace = if (n.tracingPort == null) "" else " --tracer-socket-network-accept \${TRACING_HOST}:\${TRACING_PORT}"
                    val blsArgument = if (bls == null) "" else " --shelley-bls-key ${c.folder}/${n.name}.bls.skey"
                    val startup = "#!/bin/sh\ncd ${c.folder}\n. ${c.folder}/env\nexec ${c.host.cardanoNodePath} +RTS -N${n.processorThreads} -RTS run --topology \${TOPOLOGY} --database-path \${DATABASE_PATH} --socket-path \${SOCKET_PATH} --host-addr \${HOST_ADDR} --port \${PORT} --config \${CONFIG} --shelley-kes-key \${SHELLEY_KES_KEY} --shelley-vrf-key \${SHELLEY_VRF_KEY} --shelley-operational-certificate \${SHELLEY_OPCERT}$blsArgument$trace\n"
                    connection.commandWriteFile("${c.folder}/startNode.sh", startup); connection.commandArgs(listOf("chmod", "500", "${c.folder}/startNode.sh")); require(remoteHash(connection, "${c.folder}/startNode.sh") == hash(startup))
                }
            } else {
                val startupPath = requireNotNull(c.startup); val original = connection.commandReadFile(startupPath)
                val credentialPath = "${c.folder}/credentials.json"
                val credentials: ArrayNode = if (original.contains("--bulk-credentials-file")) {
                    json.readTree(connection.commandReadFile(credentialPath)) as ArrayNode
                } else {
                    require(original.contains("--shelley-kes-key") && original.contains("--shelley-vrf-key") && original.contains("--shelley-operational-certificate"))
                    val p = c.parent
                    val parentKes = walletUtils.getSKeyContent(file(p.kesSKeyId), request.spendingPassword)
                    val parentVrf = walletUtils.getSKeyContent(file(p.vrfSKeyId), request.spendingPassword)
                    require(remoteHash(connection, "${c.folder}/${p.name}.kes.skey") == hash(parentKes) &&
                        remoteHash(connection, "${c.folder}/${p.name}.vrf.skey") == hash(parentVrf) &&
                        remoteHash(connection, "${c.folder}/${p.name}.node.opcert") == hash(file(p.opcertId).content)) {
                        "Parent singleton credentials differ from persisted material"
                    }
                    val parentEntry = json.createArrayNode().add(json.readTree(file(p.opcertId).content)).add(json.readTree(parentVrf)).add(json.readTree(parentKes))
                    if (original.contains("--shelley-bls-key")) {
                        val match = Regex("--shelley-bls-key\\s+([^\\s\\\\]+)").find(original) ?: error("Cannot identify parent BLS key")
                        parentEntry.add(json.readTree(connection.commandReadFile(safe(match.groupValues[1]))))
                    }
                    json.createArrayNode().add(parentEntry)
                }
                require(credentials.all { it.isArray && it.size() in 3..4 })
                val vrfHex = json.readTree(vrf).path("cborHex").asText(); require(vrfHex.isNotBlank() && credentials.none { it[1].path("cborHex").asText() == vrfHex })
                val entry = json.createArrayNode().add(json.readTree(cert)).add(json.readTree(vrf)).add(json.readTree(kes))
                if (blsJson != null) entry.add(blsJson)
                credentials.add(entry)
                writePrivate(credentialPath, json.writeValueAsString(credentials))
                if (!original.contains("--bulk-credentials-file")) {
                    val envPath = "${c.folder}/env"; val env = connection.commandReadFile(envPath) + "\nBULK_CREDENTIALS=$credentialPath\n"
                    connection.commandArgs(listOf("chmod", "600", envPath)); connection.commandWriteFile(envPath, env); require(remoteHash(connection, envPath) == hash(env))
                    val replaced = original.replace(Regex("--shelley-(?:kes-key|vrf-key|operational-certificate|bls-key)[ \\t]+[^\\s\\\\]+[ \\t]*\\\\?"), "")
                        .replace("--config \${CONFIG}", "--config \${CONFIG} \\\n  --bulk-credentials-file \${BULK_CREDENTIALS}")
                    require(replaced.contains("--bulk-credentials-file") && !replaced.contains("--shelley-kes-key"))
                    val temp = "/tmp/jormanager-deploy-${UUID.randomUUID()}"
                    try {
                        connection.commandWriteSecret(temp, replaced)
                        if (c.systemd) {
                            connection.sudoCommand(listOf("cp", startupPath, "$startupPath.jormanager-backup-${request.previewId}"), request.sudoPassword)
                            connection.sudoCommand(listOf("install", "-m", "644", temp, startupPath), request.sudoPassword)
                            connection.sudoCommand(listOf("systemctl", "daemon-reload"), request.sudoPassword)
                        } else {
                            connection.commandArgs(listOf("cp", startupPath, "$startupPath.jormanager-backup-${request.previewId}"))
                            connection.commandArgs(listOf("install", "-m", "500", temp, startupPath))
                        }
                        require(remoteHash(connection, startupPath) == hash(replaced))
                    } finally { connection.commandArgs(listOf("rm", "-f", temp)) }
                }
            }
            val savedNode = nodes.save(n.copy(isDraft = false, opcertId = certId))
            val fingerprint = c.key?.let(::publicHash)
            if (c.key != null) installations.save(KeyInstallation(keyId = requireNotNull(request.keyId), nodeId = request.nodeId, hostId = n.hostId,
                configurationFingerprint = c.fingerprint, installedFingerprint = fingerprint, permissionResult = "owner:600", state = "installed", observedAt = Instant.now()))
            jdbc.update("UPDATE pool_deployment_previews SET state='installed' WHERE id=?", request.previewId)
            nodesChannel.tryEmit(savedNode)
            return Result(request.requestId, request.previewId, request.nodeId, "installed", fingerprint, null, "Installation verified; producer has not been restarted and loaded key is unobserved.")
        } catch (e: Exception) {
            jdbc.update("UPDATE pool_deployment_previews SET state='failed' WHERE id=?", request.previewId)
            throw IllegalStateException("Deployment failed; inspect host and operational certificate counter before retrying", e)
        } finally { connection.commandArgs(listOf("rm", "-f") + temporary) }
    }
}
