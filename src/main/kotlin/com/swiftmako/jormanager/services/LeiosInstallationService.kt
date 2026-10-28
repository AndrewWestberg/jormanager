package com.swiftmako.jormanager.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.swiftmako.jormanager.controllers.LeiosController
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.KeyInstallation
import com.swiftmako.jormanager.entities.LeiosVotingKey
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.utils.CborSpans
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.LeiosInstallPreviewRequest
import com.swiftmako.jormanager.model.LeiosInstallRequest
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.KeyInstallationRepository
import com.swiftmako.jormanager.repositories.LeiosKeyRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.springframework.data.repository.findByIdOrNull
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class LeiosInstallationService(
    private val nodes: NodeRepository,
    private val hosts: HostRepository,
    private val keys: LeiosKeyRepository,
    private val files: FileRepository,
    private val installations: KeyInstallationRepository,
    private val walletUtils: WalletUtils,
    private val eras: CardanoRepository,
    private val capabilities: CardanoCapabilities,
    private val jdbc: JdbcTemplate,
    private val json: ObjectMapper,
) {
    data class Preview(val requestId: String, val previewId: String, val target: LeiosController.Target, val keyId: Long,
        val publicFingerprint: String, val configurationFingerprint: String, val startupPath: String, val credentialPath: String,
        val mode: String, val disruption: String, val backupPath: String)
    data class Result(val requestId: String, val previewId: String, val target: LeiosController.Target, val keyId: Long,
        val state: String, val installedFingerprint: String?, val loadedFingerprint: String?, val reason: String)
    private data class Context(val host: Host, val node: Node, val producer: Node, val key: LeiosVotingKey,
        val target: LeiosController.Target, val connection: HostConnection, val startup: String, val credential: String,
        val systemd: Boolean, val bulk: Boolean, val fingerprint: String)
    private val safe = Regex("[A-Za-z0-9_./-]+")
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
    private fun checkedPath(path: String): String = path.also { require(safe.matches(it) && !it.startsWith("-") && !it.split('/').contains("..")) }
    private fun hashFile(connection: HostConnection, path: String): String {
        if (!connection.commandFileExists(path)) return "absent"
        val hash = connection.command(listOf("sha256sum", path)).trim().substringBefore(' ').lowercase()
        require(hash.matches(Regex("[0-9a-f]{64}")))
        return hash
    }
    private fun context(target: LeiosController.Target, keyId: Long): Context {
        val node = requireNotNull(nodes.findByIdOrNull(target.nodeId))
        val host = requireNotNull(hosts.findByIdOrNull(target.hostId))
        require(!node.isDeleted && !node.isDraft && node.hostId == host.id && node.type in setOf("core", "pool"))
        val producer = if (node.type == "pool") requireNotNull(node.parentId?.let(nodes::findByIdOrNull)) else node
        require(producer.type == "core" && !producer.isDeleted && !producer.isDraft && producer.hostId == host.id)
        val network = eras.networkKey(node)
        require(eras.networkKey(producer) == network)
        val pool = requireNotNull(node.poolId)
        require(target.poolId == null || target.poolId == pool)
        require(target.networkKey == null || target.networkKey == network)
        val key = requireNotNull(keys.findByIdOrNull(keyId))
        require(key.networkKey == network && key.poolId == pool && key.signingFileId != null)
        val era = eras.evidence(node)
        require(era.freshness == "current" && era.era == "dijkstra" && era.source == "host:${host.id}/node:${node.id}")
        require(capabilities.capability(host, node, "leios.key.install").status == "verified") { "Installation is not qualified" }
        checkedPath(host.cardanoCliPath)
        val connection = HostConnection(host, producer)
        val folder = checkedPath("${host.nodeHomePath}/${producer.name}")
        val systemd = connection.hasSystemd
        val startup = checkedPath(if (systemd) "/etc/systemd/system/${producer.name}-node.service" else "$folder/startNode.sh")
        val text = connection.command(listOf("cat", startup))
        if (systemd) {
            val user = Regex("(?m)^User=([^\\r\\n]+)$").find(text)?.groupValues?.get(1)
            require(user != null && user == connection.command(listOf("id", "-un")).trim()) {
                "Producer service user differs from the credential owner"
            }
        }
        val bulk = text.contains("--bulk-credentials-file")
        require(bulk || text.contains("--shelley-operational-certificate")) { "Unsupported producer startup configuration" }
        require(node.type != "pool" || bulk) { "Pool child requires bulk credentials" }
        val credential = checkedPath(if (bulk) "$folder/credentials.json" else "$folder/leios-${key.id}.skey")
        val paths = listOf(startup, "$folder/env", "$folder/config.json", credential).map(::checkedPath)
        val fingerprint = digest(paths.joinToString("\n") { "$it:${hashFile(connection, it)}" }.toByteArray())
        return Context(host, node, producer, key, LeiosController.Target(target.hostId, target.nodeId, pool, network), connection,
            startup, credential, systemd, bulk, fingerprint)
    }
    fun preview(request: LeiosInstallPreviewRequest): Preview {
        require(request.requestId.isNotBlank())
        val c = context(request.target, request.keyId)
        val selected = digest(c.key.publicKey)
        val prior = jdbc.query("SELECT id,node_id,host_id,key_id,configuration_fingerprint,installed_fingerprint,disruption,state FROM leios_install_previews WHERE request_id=?",
            { rs, _ -> (1..8).map { rs.getString(it) } }, request.requestId).singleOrNull()
        val id = if (prior == null) UUID.randomUUID().toString() else {
            require(prior[1] == c.node.id.toString() && prior[2] == c.host.id.toString() && prior[3] == c.key.id.toString() &&
                prior[4] == c.fingerprint && prior[5] == selected && prior[7] == "previewed") { "Earlier installation review is no longer valid" }
            prior[0]
        }
        val backup = "${if (c.bulk) c.credential else c.startup}.jormanager-backup-$id"
        val disruption = prior?.get(6) ?: "Changes ${if (c.bulk) "bulk credentials" else "producer startup and key file"}${if (c.systemd && !c.bulk) " and reloads systemd configuration" else ""}; the producer must be restarted manually to load it. No restart is performed."
        if (prior == null) jdbc.update("INSERT INTO leios_install_previews(id,request_id,node_id,host_id,key_id,configuration_fingerprint,installed_fingerprint,disruption,created_at,state) VALUES (?,?,?,?,?,?,?,?,?,?)",
            id, request.requestId, c.node.id, c.host.id, c.key.id, c.fingerprint, selected, disruption, java.sql.Timestamp.from(Instant.now()), "previewed")
        return Preview(request.requestId, id, c.target, request.keyId, selected, c.fingerprint, c.startup, c.credential,
            if (c.bulk) "bulk" else if (c.systemd) "systemd" else "manual", disruption, backup)
    }
    fun install(request: LeiosInstallRequest): Result {
        require(request.requestId.isNotBlank() && request.approved && walletUtils.isValidSpendingPassword(request.spendingPassword))
        val c = context(request.target, request.keyId)
        val rows = jdbc.query("SELECT node_id,host_id,key_id,configuration_fingerprint,installed_fingerprint,state FROM leios_install_previews WHERE id=?",
            { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)) }, request.previewId)
        val reviewed = rows.singleOrNull() ?: throw IllegalArgumentException("Installation preview not found")
        require(reviewed[0] == c.node.id.toString() && reviewed[1] == c.host.id.toString() && reviewed[2] == c.key.id.toString() &&
            reviewed[3] == c.fingerprint && reviewed[4] == digest(c.key.publicKey) && reviewed[5] == "previewed") { "Installation preview is stale" }
        val secret = walletUtils.getSKeyContent(requireNotNull(files.findByIdOrNull(requireNotNull(c.key.signingFileId))), request.spendingPassword)
        val envelope = json.readTree(secret)
        require(envelope.path("type").asText() == "BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size" &&
            envelope.path("cborHex").asText().matches(Regex("5820[0-9a-fA-F]{64}")))
        require(jdbc.update("UPDATE leios_install_previews SET state='installing' WHERE id=? AND state='previewed'", request.previewId) == 1)
        try {
            require(context(request.target, request.keyId).fingerprint == c.fingerprint) { "Installation preview is stale" }
            val verifyKey = "/tmp/jormanager-leios-verify-${UUID.randomUUID()}"
            val verifyPublic = "$verifyKey.vkey"
            try {
                c.connection.commandWriteSecret(verifyKey, secret)
                c.connection.command(listOf(c.host.cardanoCliPath, "dijkstra", "key", "verification-key", "--signing-key-file", verifyKey, "--verification-key-file", verifyPublic))
                val hex = json.readTree(c.connection.command(listOf("cat", verifyPublic))).path("cborHex").asText()
                require(hex.matches(Regex("[0-9a-fA-F]+")) && hex.length % 2 == 0)
                require(CborSpans.parse(hex.hexToByteArray()).payload().contentEquals(c.key.publicKey)) { "Signing key no longer matches the reviewed public key" }
            } finally {
                c.connection.command(listOf("rm", "-f", verifyKey, verifyPublic))
            }
            val connection = c.connection
            val content = if (c.bulk) {
                val oldContent = connection.command(listOf("cat", c.credential))
                val existing = json.readTree(oldContent)
                require(existing is ArrayNode)
                existing.forEach { item ->
                    require(item.isArray && item.size() in 3..4 && item[1].path("cborHex").asText().isNotBlank())
                    if (item.size() == 4 && !item[3].isNull) {
                        val values = if (item[3].isArray) item[3].toList() else listOf(item[3])
                        values.forEach { value ->
                            require(value.isObject && value.path("type").asText() == "BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size" &&
                                value.path("cborHex").asText().matches(Regex("5820[0-9a-fA-F]{64}")))
                        }
                    }
                }
                val vrfSecret = walletUtils.getSKeyContent(requireNotNull(files.findByIdOrNull(c.node.vrfSKeyId)), request.spendingPassword)
                val vrfHex = json.readTree(vrfSecret).path("cborHex").asText()
                require(vrfHex.isNotBlank())
                val matches = existing.filter { it.isArray && it.size() in 3..4 && it[1].path("cborHex").asText() == vrfHex }
                require(matches.size == 1) { "Cannot identify the target pool in bulk credentials" }
                val entry = matches.single() as ArrayNode
                if (entry.size() == 3) entry.add(envelope)
                else if (entry[3].isNull) entry.set(3, envelope)
                else {
                    val old = entry[3]
                    val array = if (old.isArray) old.deepCopy<ArrayNode>() else json.createArrayNode().add(old)
                    if (array.none { it == envelope }) array.add(envelope)
                    entry.set(3, array)
                }
                val oldStage = "/tmp/jormanager-leios-backup-${UUID.randomUUID()}"
                try {
                    connection.commandWriteSecret(oldStage, oldContent)
                    connection.command(listOf("cp", oldStage, "${c.credential}.jormanager-backup-${request.previewId}"))
                } finally { connection.command(listOf("rm", "-f", oldStage)) }
                json.writeValueAsString(existing)
            } else secret
            val staged = "/tmp/jormanager-leios-install-${UUID.randomUUID()}"
            val adjacent = "${c.credential}.jormanager-stage-${UUID.randomUUID()}"
            try {
                connection.commandWriteSecret(staged, content)
                connection.command(listOf("cp", staged, adjacent))
                connection.command(listOf("chmod", "600", adjacent))
                connection.command(listOf("mv", "-f", adjacent, c.credential))
            } finally {
                connection.command(listOf("rm", "-f", staged, adjacent))
            }
            if (!c.bulk) {
                val original = connection.command(listOf("cat", c.startup))
                val replacement = if (original.contains("--shelley-bls-key")) {
                    original.replace(Regex("--shelley-bls-key\\s+[^\\s\\\\]+"), "--shelley-bls-key ${c.credential}")
                } else original.replace("--shelley-operational-certificate", "--shelley-bls-key ${c.credential} \\\n  --shelley-operational-certificate")
                require(replacement.contains("--shelley-bls-key ${c.credential}"))
                val temp = "/tmp/jormanager-leios-startup-${UUID.randomUUID()}"
                val backup = "${c.startup}.jormanager-backup-${request.previewId}"
                val adjacentStartup = "${c.startup}.jormanager-stage-${UUID.randomUUID()}"
                try {
                    connection.commandWriteSecret(temp, replacement)
                    if (c.systemd) {
                        connection.sudoCommand(listOf("cp", c.startup, backup), request.sudoPassword)
                        connection.sudoCommand(listOf("install", "-m", "644", temp, adjacentStartup), request.sudoPassword)
                        connection.sudoCommand(listOf("mv", "-f", adjacentStartup, c.startup), request.sudoPassword)
                        connection.sudoCommand(listOf("systemctl", "daemon-reload"), request.sudoPassword)
                    } else {
                        connection.command(listOf("cp", c.startup, backup))
                        connection.command(listOf("cp", temp, adjacentStartup))
                        connection.command(listOf("chmod", "555", adjacentStartup))
                        connection.command(listOf("mv", "-f", adjacentStartup, c.startup))
                    }
                } finally { connection.command(listOf("rm", "-f", temp)) }
            }
            val actualHash = hashFile(connection, c.credential)
            require(actualHash == digest(content.toByteArray())) { "Installed credential differs from reviewed key material" }
            val ownerMode = connection.command(listOf("stat", "-c", "%U:%a", c.credential)).trim()
            val owner = connection.command(listOf("id", "-un")).trim()
            require(ownerMode == "$owner:600") { "Installed credential ownership or permissions are incorrect" }
            val publicFingerprint = digest(c.key.publicKey)
            installations.save(KeyInstallation(keyId = request.keyId, nodeId = request.target.nodeId, hostId = request.target.hostId,
                configurationFingerprint = c.fingerprint, installedFingerprint = publicFingerprint, permissionResult = ownerMode,
                state = "installed", observedAt = Instant.now()))
            jdbc.update("UPDATE leios_install_previews SET state='installed' WHERE id=?", request.previewId)
            return Result(request.requestId, request.previewId, c.target, request.keyId, "installed", publicFingerprint, null,
                "Producer restart is required; actual loaded key is not observable by the qualified interface")
        } catch (error: Exception) {
            jdbc.update("UPDATE leios_install_previews SET state='failed' WHERE id=?", request.previewId)
            installations.save(KeyInstallation(keyId = request.keyId, nodeId = request.target.nodeId, hostId = request.target.hostId,
                configurationFingerprint = c.fingerprint, state = "failed", observedAt = Instant.now(), sanitizedError = "Installation failed; inspect the producer configuration before retrying"))
            throw IllegalStateException("Installation failed; inspect the producer configuration before retrying")
        }
    }
}
