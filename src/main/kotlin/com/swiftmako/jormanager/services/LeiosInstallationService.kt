package com.swiftmako.jormanager.services

import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
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
    private val jdbc: JdbcTemplate,
    private val json: ObjectMapper,
) {
    data class Result(val requestId: String, val target: LeiosController.Target, val keyId: Long,
        val state: String, val installedFingerprint: String?, val loadedFingerprint: String?)
    class InstallationFailure(message: String, val disposition: String) : IllegalStateException(message)
    private data class Journal(val id: String, val nodeId: Long, val hostId: Long, val keyId: Long,
        val installedFingerprint: String?, val intent: String, val state: String)
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
        require(era.freshness == "current" && era.era == "dijkstra" && era.networkKey == network &&
            era.source == "host:${host.id}/node:${node.id}")
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
    private fun journal(requestId: String): Journal? = jdbc.query(
        "SELECT id,node_id,host_id,key_id,installed_fingerprint,disruption,state FROM leios_install_previews WHERE request_id=?",
        { rs, _ -> Journal(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7)) },
        requestId,
    ).singleOrNull()

    private fun recorded(request: LeiosInstallRequest, row: Journal): Result {
        require(row.nodeId == request.target.nodeId && row.hostId == request.target.hostId && row.keyId == request.keyId &&
            row.intent.startsWith("direct:")) { "Installation request ID belongs to another action" }
        val intent = json.readTree(row.intent.removePrefix("direct:"))
        fun value(index: Int): String? = if (intent[index].isNull) null else intent[index].asString()
        require(value(0) == request.target.poolId && value(1) == request.target.networkKey) {
            "Installation request ID belongs to another target"
        }
        val node = requireNotNull(nodes.findByIdOrNull(row.nodeId))
        val host = requireNotNull(hosts.findByIdOrNull(row.hostId))
        val key = requireNotNull(keys.findByIdOrNull(row.keyId))
        require(!node.isDeleted && node.hostId == host.id && node.poolId == value(2) &&
            key.poolId == value(2) && key.networkKey == value(3) && eras.networkKey(node) == value(3)) {
            "Installation target has changed"
        }
        if (row.state != "installed") throw InstallationFailure(
            "Installation outcome is uncertain; inspect the producer configuration before retrying", "wait")
        return Result(request.requestId, LeiosController.Target(row.hostId, row.nodeId, value(2), value(3)),
            row.keyId, "installed", row.installedFingerprint, null)
    }

    fun install(request: LeiosInstallRequest): Result {
        require(request.requestId.isNotBlank() && walletUtils.isValidSpendingPassword(request.spendingPassword)) { "Authorization required" }
        journal(request.requestId)?.let { return recorded(request, it) }
        val c = context(request.target, request.keyId)
        val secret = walletUtils.getSKeyContent(requireNotNull(files.findByIdOrNull(requireNotNull(c.key.signingFileId))), request.spendingPassword)
        val envelope = json.readTree(secret)
        require(envelope.path("type").asString() == "BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size" &&
            envelope.path("cborHex").asString().matches(Regex("5820[0-9a-fA-F]{64}"))) { "Invalid signing key" }
        val id = UUID.randomUUID().toString()
        val intent = "direct:" + json.writeValueAsString(listOf(request.target.poolId, request.target.networkKey, c.target.poolId, c.target.networkKey))
        val inserted = jdbc.update("INSERT INTO leios_install_previews(id,request_id,node_id,host_id,key_id,configuration_fingerprint,installed_fingerprint,disruption,created_at,state) VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT (request_id) DO NOTHING",
            id, request.requestId, c.node.id, c.host.id, c.key.id, c.fingerprint, digest(c.key.publicKey), intent, java.sql.Timestamp.from(Instant.now()), "prepared")
        if (inserted == 0) return recorded(request, requireNotNull(journal(request.requestId)))
        require(jdbc.update("UPDATE leios_install_previews SET state='installing' WHERE id=? AND state='prepared'", id) == 1)
        var writesStarted = false
        try {
            require(context(request.target, request.keyId).fingerprint == c.fingerprint) { "Installation target configuration changed" }
            val verifyKey = "/tmp/jormanager-leios-verify-${UUID.randomUUID()}"
            val verifyPublic = "$verifyKey.vkey"
            try {
                c.connection.commandWriteSecret(verifyKey, secret)
                c.connection.command(listOf(c.host.cardanoCliPath, "dijkstra", "key", "verification-key", "--signing-key-file", verifyKey, "--verification-key-file", verifyPublic))
                val hex = json.readTree(c.connection.command(listOf("cat", verifyPublic))).path("cborHex").asString()
                require(hex.matches(Regex("[0-9a-fA-F]+")) && hex.length % 2 == 0)
                require(CborSpans.parse(hex.hexToByteArray()).payload().contentEquals(c.key.publicKey)) { "Signing key does not match the selected public key" }
            } finally {
                c.connection.command(listOf("rm", "-f", verifyKey, verifyPublic))
            }
            val connection = c.connection
            val content = if (c.bulk) {
                val oldContent = connection.command(listOf("cat", c.credential))
                val existing = json.readTree(oldContent)
                require(existing is ArrayNode)
                existing.forEach { item ->
                    require(item.isArray && item.size() in 3..4 && item[1].path("cborHex").asString().isNotBlank())
                    if (item.size() == 4 && !item[3].isNull) {
                        val values = if (item[3].isArray) item[3].toList() else listOf(item[3])
                        values.forEach { value ->
                            require(value.isObject && value.path("type").asString() == "BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size" &&
                                value.path("cborHex").asString().matches(Regex("5820[0-9a-fA-F]{64}")))
                        }
                    }
                }
                val vrfSecret = walletUtils.getSKeyContent(requireNotNull(files.findByIdOrNull(c.node.vrfSKeyId)), request.spendingPassword)
                val vrfHex = json.readTree(vrfSecret).path("cborHex").asString()
                require(vrfHex.isNotBlank())
                val matches = existing.filter { it.isArray && it.size() in 3..4 && it[1].path("cborHex").asString() == vrfHex }
                require(matches.size == 1) { "Cannot identify the target pool in bulk credentials" }
                val entry = matches.single() as ArrayNode
                if (entry.size() == 3) entry.add(envelope)
                else if (entry[3].isNull) entry.set(3, envelope)
                else {
                    val old = entry[3]
                    val array = if (old.isArray) (old as ArrayNode).deepCopy() else json.createArrayNode().add(old)
                    if (array.none { it == envelope }) array.add(envelope)
                    entry.set(3, array)
                }
                val oldStage = "/tmp/jormanager-leios-backup-${UUID.randomUUID()}"
                try {
                    connection.commandWriteSecret(oldStage, oldContent)
                    writesStarted = true
                    connection.command(listOf("cp", oldStage, "${c.credential}.jormanager-backup-$id"))
                } finally { connection.command(listOf("rm", "-f", oldStage)) }
                json.writeValueAsString(existing)
            } else secret
            val staged = "/tmp/jormanager-leios-install-${UUID.randomUUID()}"
            val adjacent = "${c.credential}.jormanager-stage-${UUID.randomUUID()}"
            try {
                connection.commandWriteSecret(staged, content)
                writesStarted = true
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
                val backup = "${c.startup}.jormanager-backup-$id"
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
            require(actualHash == digest(content.toByteArray())) { "Installed credential differs from selected key material" }
            val ownerMode = connection.command(listOf("stat", "-c", "%U:%a", c.credential)).trim()
            val owner = connection.command(listOf("id", "-un")).trim()
            require(ownerMode == "$owner:600") { "Installed credential ownership or permissions are incorrect" }
            val publicFingerprint = digest(c.key.publicKey)
            installations.save(KeyInstallation(keyId = request.keyId, nodeId = request.target.nodeId, hostId = request.target.hostId,
                configurationFingerprint = c.fingerprint, installedFingerprint = publicFingerprint, permissionResult = ownerMode,
                state = "installed", observedAt = Instant.now()))
            jdbc.update("UPDATE leios_install_previews SET state='installed' WHERE id=?", id)
            return Result(request.requestId, c.target, request.keyId, "installed", publicFingerprint, null)
        } catch (error: Exception) {
            jdbc.update("UPDATE leios_install_previews SET state='failed' WHERE id=?", id)
            val safeReasons = setOf("Installation target configuration changed", "Signing key does not match the selected public key",
                "Cannot identify the target pool in bulk credentials", "Installed credential differs from selected key material",
                "Installed credential ownership or permissions are incorrect")
            val reason = error.message?.takeIf { it in safeReasons } ?: "Installation failed; inspect the producer configuration before retrying"
            installations.save(KeyInstallation(keyId = request.keyId, nodeId = request.target.nodeId, hostId = request.target.hostId,
                configurationFingerprint = c.fingerprint, state = "failed", observedAt = Instant.now(), sanitizedError = reason))
            throw InstallationFailure(reason, if (writesStarted) "wait" else "retry")
        }
    }
}
