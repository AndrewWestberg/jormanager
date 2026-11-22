package com.swiftmako.jormanager.services

import com.swiftmako.jormanager.controllers.NodeController
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.repositories.FileRepository
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode

@Service
class PoolDeploymentService(
    private val files: FileRepository,
    private val configurationFiles: NodeConfigurationFiles,
    private val walletUtils: WalletUtils,
    private val nodeController: NodeController,
    private val json: ObjectMapper,
) {
    data class TargetSpec(
        val host: Host,
        val parent: Node?,
        val name: String,
        val type: String,
        val processorThreads: Int,
        val listen: String,
        val port: Int,
        val promPort: Int,
        val tracingHost: String?,
        val tracingPort: Int?,
        val assets: Map<String, ByteArray>,
        val config: String,
    )

    data class CredentialMaterial(
        val kesSKey: String,
        val vrfSKey: String,
        val opcert: String,
        val blsSKey: String? = null,
        val blsPublicKey: ByteArray? = null,
    )

    data class DeploymentResult(
        val configurationFingerprint: String,
        val installedFingerprint: String?,
        val kesExpireTimeSec: Long?,
        val kesExpireDate: String?,
        val rollback: () -> Unit = {},
        val cleanup: () -> Unit = {},
    )

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHexString()

    private fun bytesHash(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).toHexString()

    private fun safe(value: String): String =
        value.also {
            require(it.matches(Regex("[A-Za-z0-9_./-]+")) && !it.startsWith("-") && !it.split('/').contains("..")) {
                "Invalid host path"
            }
        }

    private fun file(id: Long) = requireNotNull(files.findByIdOrNull(id)) { "Referenced file $id not found" }

    private fun remoteHash(
        c: HostConnection,
        path: String
    ): String =
        if (c.commandFileExists(path)) {
            c
                .commandArgs(listOf("sha256sum", path))
                .trim()
                .substringBefore(' ')
                .also { require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid sha256sum output" } }
        } else {
            "absent"
        }

    internal fun isValidBlsSigningEnvelope(node: JsonNode): Boolean =
        node.isObject &&
            node.path("type").asString() == BLS_SIGNING_KEY_TYPE &&
            node.path("cborHex").asString().matches(BLS_SIGNING_KEY_CBOR_HEX_REGEX)

    internal fun validateBulkCredentials(node: JsonNode): ArrayNode {
        require(node is ArrayNode) { "Invalid existing bulk credentials" }
        node.forEach { entry ->
            require(entry.isArray && entry.size() in 3..4) { "Invalid existing bulk credential entry" }
            require(entry[0].isObject && entry[1].isObject && entry[1].path("cborHex").asString().isNotBlank() && entry[2].isObject) {
                "Invalid existing bulk credential entry"
            }
            if (entry.size() == 4 && !entry[3].isNull) {
                val blsSlot = entry[3]
                val envelopes = if (blsSlot.isArray) blsSlot.toList() else listOf(blsSlot)
                envelopes.forEach { envelope ->
                    require(isValidBlsSigningEnvelope(envelope)) { "Invalid BLS envelope in existing bulk credentials" }
                }
            }
        }
        return node
    }

    fun parseAndValidateBulkCredentials(raw: String): ArrayNode {
        val parsed =
            try {
                json.readTree(raw)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid existing bulk credentials", e)
            }
        return validateBulkCredentials(parsed)
    }

    fun computeKesExpiration(
        shelleyGenesis: JsonNode,
        nowEpochSec: Long = Instant.now().epochSecond,
    ): Pair<Long?, String?> {
        val periods = shelleyGenesis.path("slotsPerKESPeriod").asLong(0L)
        val maxKesEvolutions = shelleyGenesis.path("maxKESEvolutions").asLong(0L)
        val slotLengthSec = shelleyGenesis.path("slotLength").asDouble(0.0)
        return if (periods > 0L && maxKesEvolutions > 0L && slotLengthSec > 0.0) {
            val expireSec = nowEpochSec + (maxKesEvolutions * periods * slotLengthSec).toLong()
            expireSec to KES_EXPIRE_DATE_FORMATTER.format(Instant.ofEpochSecond(expireSec))
        } else {
            null to null
        }
    }

    fun validateTarget(
        spec: TargetSpec,
        spendingPassword: String,
        sudoPassword: String,
        verifySudo: Boolean = false,
        candidateVrfSKey: String? = null,
    ): ArrayNode? {
        require(spec.type in setOf("core", "pool")) { "Unsupported producer type" }
        require(spec.name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*"))) { "Invalid node name" }
        require(spec.processorThreads > 0 && spec.port in 1..65535) { "Invalid processor threads or port" }
        require(
            spec.listen.matches(Regex("[A-Za-z0-9.:-]+")) &&
                (spec.tracingHost == null || spec.tracingHost.matches(Regex("[A-Za-z0-9.:-]+"))),
        ) { "Invalid listen address" }
        safe(spec.host.nodeHomePath)
        safe(spec.host.cardanoNodePath)
        safe(spec.host.cardanoCliPath)
        configurationFiles.validate(spec.assets)

        val connection = HostConnection(spec.host, spec.parent)
        val folder = safe("${spec.host.nodeHomePath}/${spec.parent?.name ?: spec.name}")
        val systemd = connection.hasSystemd
        val startup = safe(if (systemd) "/etc/systemd/system/${spec.parent?.name ?: spec.name}-node.service" else "$folder/startNode.sh")
        if (verifySudo && systemd) {
            connection.sudoCommand(listOf("pwd"), sudoPassword)
        }

        val parentCredentials: ArrayNode? =
            if (spec.parent == null) {
                require(spec.type == "core") { "Standalone producer must have core type" }
                require(!connection.commandFileExists(folder) && (!systemd || !connection.commandFileExists(startup))) {
                    "Target directory or service already exists"
                }
                val occupied = connection.commandArgs(listOf("ss", "-tulw"))
                require(
                    listOfNotNull(spec.port, spec.promPort, spec.tracingPort).none {
                        Regex("[:.]$it(?:\\s|$)").containsMatchIn(occupied)
                    },
                ) { "Reviewed port is already in use" }
                null
            } else {
                require(spec.type == "pool") { "Child producer must have pool type" }
                val parent = spec.parent
                require(!parent.isDeleted && parent.type == "core" && parent.hostId == spec.host.id) {
                    "Parent core node is unavailable"
                }
                require(connection.commandReadFile("$folder/config.json") == spec.config) { "Parent configuration changed" }
                spec.assets.filterKeys { it != "config.json" && it != "topology.json" }.forEach { (name, content) ->
                    require(remoteHash(connection, "$folder/$name") == hash(content.toString(Charsets.UTF_8))) {
                        "Parent files differ from selected snapshot"
                    }
                }
                require(
                    remoteHash(connection, "$folder/topology.json") ==
                        hash(spec.assets.getValue("topology.json").toString(Charsets.UTF_8)),
                ) { "Parent topology changed" }
                require(connection.commandFileExists(startup)) { "Parent startup file not found" }
                val startupContent = connection.commandReadFile(startup)
                val credentialPath = "$folder/credentials.json"
                val existing: ArrayNode =
                    if (startupContent.contains("--bulk-credentials-file")) {
                        require(connection.commandFileExists(credentialPath)) { "Existing bulk credentials file not found" }
                        parseAndValidateBulkCredentials(connection.commandReadFile(credentialPath))
                    } else {
                        require(!connection.commandFileExists(credentialPath)) {
                            "Unexpected existing credentials require inspection"
                        }
                        require(
                            startupContent.contains("--shelley-kes-key") &&
                                startupContent.contains("--shelley-vrf-key") &&
                                startupContent.contains("--shelley-operational-certificate"),
                        ) { "Parent startup lacks Shelley credential flags" }
                        val parentKes = walletUtils.getSKeyContent(file(parent.kesSKeyId), spendingPassword)
                        val parentVrf = walletUtils.getSKeyContent(file(parent.vrfSKeyId), spendingPassword)
                        val parentOpcert = file(parent.opcertId).content
                        require(
                            remoteHash(connection, "$folder/${parent.name}.kes.skey") == hash(parentKes) &&
                                remoteHash(connection, "$folder/${parent.name}.vrf.skey") == hash(parentVrf) &&
                                remoteHash(connection, "$folder/${parent.name}.node.opcert") == hash(parentOpcert),
                        ) { "Parent singleton credentials differ from persisted material" }
                        val parentEntry =
                            json
                                .createArrayNode()
                                .add(json.readTree(parentOpcert))
                                .add(json.readTree(parentVrf))
                                .add(json.readTree(parentKes))
                        if (startupContent.contains("--shelley-bls-key")) {
                            val match =
                                Regex("--shelley-bls-key\\s+([^\\s\\\\]+)").find(startupContent)
                                    ?: error("Cannot identify parent BLS key")
                            val parentBls = json.readTree(connection.commandReadFile(safe(match.groupValues[1])))
                            require(isValidBlsSigningEnvelope(parentBls)) { "Invalid BLS envelope in existing bulk credentials" }
                            parentEntry.add(parentBls)
                        }
                        validateBulkCredentials(json.createArrayNode().add(parentEntry))
                    }
                if (candidateVrfSKey != null) {
                    val vrfHex = json.readTree(candidateVrfSKey).path("cborHex").asString()
                    require(vrfHex.isNotBlank() && existing.none { it[1].path("cborHex").asString() == vrfHex }) {
                        "VRF key already exists in bulk credentials"
                    }
                }
                existing.deepCopy()
            }

        if (systemd) {
            val owner = connection.commandArgs(listOf("id", "-un")).trim()
            if (spec.parent == null) {
                require(spec.host.sshUser == owner) { "Service user differs from credential owner" }
            } else {
                require(
                    Regex("(?m)^User=([^\\r\\n]+)$").find(connection.commandReadFile(startup))?.groupValues?.get(1) == owner,
                ) { "Service user differs from credential owner" }
            }
        }
        return parentCredentials
    }

    fun deploy(
        spec: TargetSpec,
        credentials: CredentialMaterial,
        spendingPassword: String,
        sudoPassword: String,
        operationId: String = UUID.randomUUID().toString(),
        deferBackupCleanup: Boolean = false,
    ): DeploymentResult {
        require(walletUtils.isValidSpendingPassword(spendingPassword)) { "Authorization required" }
        require((credentials.blsSKey == null) == (credentials.blsPublicKey == null)) { "Incomplete BLS credential material" }
        val blsJson =
            credentials.blsSKey?.let { raw ->
                json.readTree(raw).also { parsed ->
                    require(isValidBlsSigningEnvelope(parsed)) { "Invalid BLS signing key envelope" }
                }
            }
        if (credentials.blsPublicKey != null) {
            require(credentials.blsPublicKey.size == 96) { "Invalid BLS public key length" }
        }

        val validatedParentCredentials =
            validateTarget(
                spec = spec,
                spendingPassword = spendingPassword,
                sudoPassword = sudoPassword,
                verifySudo = false,
                candidateVrfSKey = credentials.vrfSKey,
            )

        val connection = HostConnection(spec.host, spec.parent)
        val folder = safe("${spec.host.nodeHomePath}/${spec.parent?.name ?: spec.name}")
        val systemd = connection.hasSystemd
        val startup = safe(if (systemd) "/etc/systemd/system/${spec.parent?.name ?: spec.name}-node.service" else "$folder/startNode.sh")
        val serviceName = "${spec.parent?.name ?: spec.name}-node.service"
        val topology = configurationFiles.renderTopology(spec.assets)

        fun writePrivate(
            path: String,
            content: String
        ) {
            val temp = "/tmp/jormanager-deploy-${UUID.randomUUID()}"
            try {
                connection.commandWriteSecret(temp, content)
                connection.commandArgs(listOf("install", "-m", "600", temp, safe(path)))
                val expectedOwner = "${connection.commandArgs(listOf("id", "-un")).trim()}:600"
                require(connection.commandArgs(listOf("stat", "-c", "%U:%a", path)).trim() == expectedOwner) {
                    "Installed secret permissions mismatch"
                }
                require(remoteHash(connection, path) == hash(content)) { "Installed secret hash mismatch" }
            } finally {
                runCatching { connection.commandArgs(listOf("rm", "-f", temp)) }
            }
        }

        val rollbackAction: () -> Unit
        val cleanupAction: () -> Unit

        if (spec.parent == null) {
            var folderCreated = false
            var systemdUnitInstalled = false
            rollbackAction = {
                if (systemd && systemdUnitInstalled) {
                    runCatching { connection.sudoCommand(listOf("systemctl", "stop", serviceName), sudoPassword) }
                    runCatching { connection.sudoCommand(listOf("systemctl", "disable", serviceName), sudoPassword) }
                    runCatching { connection.sudoCommand(listOf("rm", "-f", startup), sudoPassword) }
                    runCatching { connection.sudoCommand(listOf("systemctl", "daemon-reload"), sudoPassword) }
                }
                if (folderCreated) {
                    runCatching { connection.commandArgs(listOf("rm", "-rf", folder)) }
                }
            }
            cleanupAction = {}

            try {
                connection.commandArgs(listOf("mkdir", folder))
                folderCreated = true
                connection.commandArgs(listOf("mkdir", "$folder/db"))
                spec.assets.filterKeys { it != "config.json" && it != "topology.json" }.forEach { (name, bytes) ->
                    val content = bytes.toString(Charsets.UTF_8)
                    connection.commandWriteFile("$folder/$name", content)
                    require(remoteHash(connection, "$folder/$name") == hash(content)) { "Installed $name hash mismatch" }
                }
                listOf("topology.json" to topology, "config.json" to spec.config).forEach { (name, content) ->
                    connection.commandWriteFile("$folder/$name", content)
                    require(remoteHash(connection, "$folder/$name") == hash(content)) { "Installed $name hash mismatch" }
                }
                writePrivate("$folder/${spec.name}.kes.skey", credentials.kesSKey)
                writePrivate("$folder/${spec.name}.vrf.skey", credentials.vrfSKey)
                writePrivate("$folder/${spec.name}.node.opcert", credentials.opcert)
                if (credentials.blsSKey != null) {
                    writePrivate("$folder/${spec.name}.bls.skey", credentials.blsSKey)
                }
                val env =
                    nodeController.renderEnvContent(
                        "core",
                        spec.name,
                        folder,
                        spec.listen,
                        spec.port,
                        spec.tracingHost ?: "127.0.0.1",
                        spec.tracingPort,
                    )
                connection.commandWriteFile("$folder/env", env)
                require(remoteHash(connection, "$folder/env") == hash(env)) { "Installed env hash mismatch" }
                if (systemd) {
                    val base =
                        nodeController.renderSystemdContent(
                            "core",
                            spec.host,
                            spec.name,
                            spec.processorThreads,
                            spec.tracingHost ?: "127.0.0.1",
                            spec.tracingPort,
                        )
                    val renderedStartup =
                        if (credentials.blsSKey != null) {
                            base.replace(
                                "--shelley-operational-certificate",
                                "--shelley-bls-key $folder/${spec.name}.bls.skey \\\n  --shelley-operational-certificate",
                            )
                        } else {
                            base
                        }
                    val temp = "/tmp/jormanager-deploy-${UUID.randomUUID()}"
                    try {
                        connection.commandWriteSecret(temp, renderedStartup)
                        systemdUnitInstalled = true
                        connection.sudoCommand(listOf("install", "-m", "644", temp, startup), sudoPassword)
                        require(remoteHash(connection, startup) == hash(renderedStartup)) { "Installed systemd unit hash mismatch" }
                    } finally {
                        runCatching { connection.commandArgs(listOf("rm", "-f", temp)) }
                    }
                    connection.sudoCommand(listOf("systemctl", "daemon-reload"), sudoPassword)
                    connection.sudoCommand(listOf("systemctl", "restart", serviceName), sudoPassword)
                    connection.sudoCommand(listOf("systemctl", "enable", serviceName), sudoPassword)
                } else {
                    val trace = if (spec.tracingPort == null) "" else " --tracer-socket-network-accept \${TRACING_HOST}:\${TRACING_PORT}"
                    val blsArgument = if (credentials.blsSKey == null) "" else " --shelley-bls-key $folder/${spec.name}.bls.skey"
                    val renderedStartup =
                        "#!/bin/sh\ncd $folder\n. $folder/env\nexec ${spec.host.cardanoNodePath} +RTS -N${spec.processorThreads} -RTS run --topology \${TOPOLOGY} --database-path \${DATABASE_PATH} --socket-path \${SOCKET_PATH} --host-addr \${HOST_ADDR} --port \${PORT} --config \${CONFIG} --shelley-kes-key \${SHELLEY_KES_KEY} --shelley-vrf-key \${SHELLEY_VRF_KEY} --shelley-operational-certificate \${SHELLEY_OPCERT}$blsArgument$trace\n"
                    connection.commandWriteFile("$folder/startNode.sh", renderedStartup)
                    connection.commandArgs(listOf("chmod", "500", "$folder/startNode.sh"))
                    require(remoteHash(connection, "$folder/startNode.sh") == hash(renderedStartup)) {
                        "Installed startNode.sh hash mismatch"
                    }
                }
            } catch (e: Throwable) {
                rollbackAction()
                throw e
            }
        } else {
            val originalStartup = connection.commandReadFile(startup)
            val credentialPath = "$folder/credentials.json"
            val envPath = "$folder/env"
            val credentialBackup = safe("$credentialPath.jormanager-backup-$operationId")
            val envBackup = safe("$envPath.jormanager-backup-$operationId")
            val startupBackup = safe("$startup.jormanager-backup-$operationId")
            val hadExistingCredentials = connection.commandFileExists(credentialPath)
            var credentialBackupCreated = false
            var credentialsModified = false
            var envBackupCreated = false
            var envModified = false
            var startupBackupCreated = false
            var startupModified = false

            rollbackAction = {
                if (credentialsModified) {
                    if (hadExistingCredentials && credentialBackupCreated) {
                        runCatching { connection.commandArgs(listOf("install", "-m", "600", credentialBackup, credentialPath)) }
                    } else if (!hadExistingCredentials) {
                        runCatching { connection.commandArgs(listOf("rm", "-f", credentialPath)) }
                    }
                }
                if (envModified && envBackupCreated) {
                    runCatching { connection.commandArgs(listOf("cp", "-p", envBackup, envPath)) }
                }
                if (startupModified && startupBackupCreated) {
                    if (systemd) {
                        runCatching { connection.sudoCommand(listOf("install", "-m", "644", startupBackup, startup), sudoPassword) }
                    } else {
                        runCatching { connection.commandArgs(listOf("install", "-m", "500", startupBackup, startup)) }
                    }
                }
                if (systemd && (credentialsModified || envModified || startupModified)) {
                    runCatching { connection.sudoCommand(listOf("systemctl", "daemon-reload"), sudoPassword) }
                    runCatching { connection.sudoCommand(listOf("systemctl", "restart", serviceName), sudoPassword) }
                }
            }

            cleanupAction = {
                if (credentialBackupCreated) {
                    runCatching { connection.commandArgs(listOf("rm", "-f", credentialBackup)) }
                }
                if (envBackupCreated) {
                    runCatching { connection.commandArgs(listOf("rm", "-f", envBackup)) }
                }
                if (startupBackupCreated) {
                    if (systemd) {
                        runCatching { connection.sudoCommand(listOf("rm", "-f", startupBackup), sudoPassword) }
                    } else {
                        runCatching { connection.commandArgs(listOf("rm", "-f", startupBackup)) }
                    }
                }
            }

            try {
                val bulkCredentials = requireNotNull(validatedParentCredentials).deepCopy()
                val entry =
                    json
                        .createArrayNode()
                        .add(json.readTree(credentials.opcert))
                        .add(json.readTree(credentials.vrfSKey))
                        .add(json.readTree(credentials.kesSKey))
                if (blsJson != null) {
                    entry.add(blsJson)
                }
                bulkCredentials.add(entry)
                if (hadExistingCredentials) {
                    require(originalStartup.contains("--bulk-credentials-file")) {
                        "Unexpected existing credentials require inspection"
                    }
                    connection.commandArgs(listOf("cp", "-p", credentialPath, credentialBackup))
                    credentialBackupCreated = true
                }
                credentialsModified = true
                writePrivate(credentialPath, json.writeValueAsString(bulkCredentials))

                if (!originalStartup.contains("--bulk-credentials-file")) {
                    val updatedEnv = connection.commandReadFile(envPath) + "\nBULK_CREDENTIALS=$credentialPath\n"
                    connection.commandArgs(listOf("cp", "-p", envPath, envBackup))
                    envBackupCreated = true
                    envModified = true
                    connection.commandArgs(listOf("chmod", "600", envPath))
                    connection.commandWriteFile(envPath, updatedEnv)
                    require(remoteHash(connection, envPath) == hash(updatedEnv)) { "Updated env hash mismatch" }

                    val replaced =
                        originalStartup
                            .replace(Regex("--shelley-(?:kes-key|vrf-key|operational-certificate|bls-key)[ \\t]+[^\\s\\\\]+[ \\t]*\\\\?"), "")
                            .replace("--config \${CONFIG}", "--config \${CONFIG} \\\n  --bulk-credentials-file \${BULK_CREDENTIALS}")
                    require(replaced.contains("--bulk-credentials-file") && !replaced.contains("--shelley-kes-key")) {
                        "Failed to render bulk-credentials startup command"
                    }
                    val temp = "/tmp/jormanager-deploy-${UUID.randomUUID()}"
                    try {
                        connection.commandWriteSecret(temp, replaced)
                        if (systemd) {
                            connection.sudoCommand(listOf("cp", startup, startupBackup), sudoPassword)
                            startupBackupCreated = true
                            startupModified = true
                            connection.sudoCommand(listOf("install", "-m", "644", temp, startup), sudoPassword)
                        } else {
                            connection.commandArgs(listOf("cp", startup, startupBackup))
                            startupBackupCreated = true
                            startupModified = true
                            connection.commandArgs(listOf("install", "-m", "500", temp, startup))
                        }
                        require(remoteHash(connection, startup) == hash(replaced)) { "Updated startup hash mismatch" }
                    } finally {
                        runCatching { connection.commandArgs(listOf("rm", "-f", temp)) }
                    }
                }

                if (systemd) {
                    connection.sudoCommand(listOf("systemctl", "daemon-reload"), sudoPassword)
                    connection.sudoCommand(listOf("systemctl", "restart", serviceName), sudoPassword)
                    connection.sudoCommand(listOf("systemctl", "enable", serviceName), sudoPassword)
                }
                if (!deferBackupCleanup) {
                    cleanupAction()
                }
            } catch (e: Throwable) {
                rollbackAction()
                cleanupAction()
                throw e
            }
        }

        val shelleyGenesis = json.readTree(spec.assets.getValue("shelley-genesis.json").toString(Charsets.UTF_8))
        val (kesExpireTimeSec, kesExpireDate) = computeKesExpiration(shelleyGenesis)
        val installedFingerprint = credentials.blsPublicKey?.let(::bytesHash)
        val configurationFingerprint =
            hash(
                listOf(
                    spec.host.id.toString(),
                    spec.parent?.id?.toString() ?: "standalone",
                    spec.name,
                    spec.type,
                    hash(spec.config),
                    hash(topology),
                    installedFingerprint ?: "no-bls",
                    spec.assets.entries
                        .sortedBy { it.key }
                        .joinToString("|") { "${it.key}:${bytesHash(it.value)}" },
                ).joinToString("|"),
            )

        return DeploymentResult(
            configurationFingerprint = configurationFingerprint,
            installedFingerprint = installedFingerprint,
            kesExpireTimeSec = kesExpireTimeSec,
            kesExpireDate = kesExpireDate,
            rollback = rollbackAction,
            cleanup = cleanupAction,
        )
    }

    companion object {
        internal const val BLS_SIGNING_KEY_TYPE = "BlsSigningKey_bls12-381-BLS-Signature-Minimal-Signature-Size"
        internal val BLS_SIGNING_KEY_CBOR_HEX_REGEX = Regex("5820[0-9a-fA-F]{64}")
        private val KES_EXPIRE_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss 'UTC' yyyy", Locale.US).withZone(ZoneOffset.UTC)
    }
}
