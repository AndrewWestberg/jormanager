package com.swiftmako.jormanager.services

import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.repositories.CardanoRepository
import java.security.MessageDigest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/** Qualified evidence is tied to the network, node, operation and actual installed artifacts. */
@Service
class CardanoCapabilities(private val cardanoRepository: CardanoRepository, private val jdbc: JdbcTemplate) {
    data class OperationCapability(
        val operation: String, val status: String, val reason: String, val evidenceId: String? = null,
        val qualificationFingerprint: String? = null,
    )
    private data class Record(val status: String, val cliHash: String, val nodeHash: String, val configHash: String, val evidenceId: String, val evidenceHash: String, val generation: Long)

    data class Qualification(
        val operation: String, val status: String, val evidenceId: String, val evidenceSha256: String,
        val cliSha256: String, val nodeSha256: String, val configSha256: String,
        val expectedEra: String, val expectedGeneration: Long,
    )

    companion object {
        val operations = setOf(
            "wallet_send", "wallet_reward_withdrawal", "wallet_stake_registration",
            "wallet_stake_deregistration", "wallet_fee_preview", "wallet_stake_delegation",
            "node.create", "pool.register", "pool.update", "pool.retire", "governance.vote",
            "leios.key.prepare", "leios.key.import", "leios.key.install", "leios.status",
        )
        private val hostOperations = setOf("node.create", "leios.key.prepare", "leios.key.import")
        private val digestPattern = Regex("[0-9a-f]{64}")
        private val pathPattern = Regex("[A-Za-z0-9_./-]+")
    }
    private fun qualificationFingerprint(record: Record, network: String, era: String, target: String, operation: String): String {
        val fields = listOf(network, era, target, operation, record.generation.toString(), record.status,
            record.cliHash.lowercase(), record.nodeHash.lowercase(), record.configHash.lowercase(),
            record.evidenceId, record.evidenceHash.lowercase())
        val canonical = fields.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }


    private fun record(host: Host, node: Node, era: String, network: String, operation: String): Record? =
        jdbc.query(
            "SELECT status, cli_sha256, node_sha256, config_sha256, evidence_id, evidence_sha256, era_generation FROM qualified_operations WHERE network_key = ? AND era = ? AND host_id = ? AND node_id = ? AND operation = ?",
            { rs, _ -> Record(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7)) },
            network, era, host.id, node.id, operation,
        ).singleOrNull()

    private fun fingerprints(host: Host, node: Node): List<String> {
        val paths = listOf(host.cardanoCliPath, host.cardanoNodePath, "${host.nodeHomePath}/${node.name}/config.json")
        require(paths.all { pathPattern.matches(it) && !it.startsWith("-") }) { "Unsafe artifact path" }
        val connection = HostConnection(host, node)
        return paths.map { path ->
            val digest = connection.command(listOf("sha256sum", path)).trim().substringBefore(' ').lowercase()
            require(digestPattern.matches(digest)) { "Artifact fingerprint unavailable" }
            digest
        }
    }
    private fun hostFingerprints(host: Host): List<String> {
        val paths = listOf(host.cardanoCliPath, host.cardanoNodePath)
        require(paths.all { pathPattern.matches(it) && !it.startsWith("-") }) { "Unsafe artifact path" }
        val connection = HostConnection(host)
        return paths.map {
            val hash = connection.command(listOf("sha256sum", it)).trim().substringBefore(' ').lowercase()
            require(digestPattern.matches(hash)) { "Artifact fingerprint unavailable" }
            hash
        }
    }

    private fun observerEvidence(observerHost: Host, observerNode: Node, networkKey: String): CardanoRepository.EraEvidence {
        require(observerNode.id != null && !observerNode.isDeleted && observerHost.id == observerNode.hostId) { "Observer host does not own an active node" }
        val evidence = cardanoRepository.evidence(observerNode)
        require(evidence.freshness == "current" && evidence.networkKey == networkKey &&
            evidence.source == "host:${observerHost.id}/node:${observerNode.id}" && evidence.era != null) {
            "Current observer era unavailable for selected network"
        }
        return evidence
    }

    fun recordHostQualification(targetHost: Host, observerHost: Host, observerNode: Node, networkKey: String, qualification: Qualification) {
        require(qualification.operation in hostOperations)
        require(qualification.status in setOf("verified", "unsupported", "unverified", "unavailable"))
        require(qualification.evidenceId.isNotBlank() && qualification.evidenceId.length <= 255)
        val hashes = listOf(qualification.cliSha256, qualification.nodeSha256, qualification.configSha256, qualification.evidenceSha256).map { it.lowercase() }
        require(hashes.all { digestPattern.matches(it) }) { "Invalid evidence digest" }
        val before = observerEvidence(observerHost, observerNode, networkKey)
        require(qualification.expectedEra == before.era && qualification.expectedGeneration == before.generation) { "Qualification was reviewed for another era generation" }
        require(hostFingerprints(targetHost) == hashes.take(2)) { "Qualification artifacts do not match target host" }
        val after = observerEvidence(observerHost, observerNode, networkKey)
        require(after.era == before.era && after.generation == before.generation) { "Observer era changed" }
        jdbc.update(
            """INSERT INTO host_qualified_operations (network_key, era, host_id, config_sha256, operation, status, cli_sha256, node_sha256, evidence_id, evidence_sha256, era_generation)
               SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
               WHERE EXISTS (SELECT 1 FROM era_observations WHERE network_key = ? AND era = ? AND generation = ?)
               ON CONFLICT (network_key, era, host_id, config_sha256, operation) DO UPDATE SET
               status = EXCLUDED.status, cli_sha256 = EXCLUDED.cli_sha256, node_sha256 = EXCLUDED.node_sha256,
               evidence_id = EXCLUDED.evidence_id, evidence_sha256 = EXCLUDED.evidence_sha256, era_generation = EXCLUDED.era_generation""",
            networkKey, before.era, targetHost.id, hashes[2], qualification.operation, qualification.status,
            hashes[0], hashes[1], qualification.evidenceId, hashes[3], before.generation,
            networkKey, before.era, before.generation,
        ).also { check(it == 1) { "Observer era changed" } }
    }

    fun hostCapability(targetHost: Host, observerHost: Host, observerNode: Node, networkKey: String, configSha256: String, operation: String): OperationCapability {
        require(operation in hostOperations)
        return try {
            val config = configSha256.lowercase()
            require(digestPattern.matches(config)) { "Invalid configuration fingerprint" }
            val before = observerEvidence(observerHost, observerNode, networkKey)
            val record = jdbc.query(
                """SELECT status, cli_sha256, node_sha256, config_sha256, evidence_id, evidence_sha256, era_generation
                   FROM host_qualified_operations WHERE network_key = ? AND era = ? AND host_id = ? AND config_sha256 = ? AND operation = ?""",
                { rs, _ -> Record(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7)) },
                networkKey, before.era, targetHost.id, config, operation,
            ).singleOrNull() ?: return OperationCapability(operation, "unverified", "No host qualification evidence for this configuration")
            if (record.generation != before.generation) return OperationCapability(operation, "unverified", "Qualification predates the current era observation", record.evidenceId)
            if (record.status != "verified") return OperationCapability(operation, record.status, "Operation is not qualified", record.evidenceId)
            if (record.evidenceId.isBlank() || !digestPattern.matches(record.evidenceHash.lowercase()) ||
                hostFingerprints(targetHost) != listOf(record.cliHash.lowercase(), record.nodeHash.lowercase())) {
                return OperationCapability(operation, "unverified", "Installed host artifacts differ from qualification evidence", record.evidenceId)
            }
            val after = observerEvidence(observerHost, observerNode, networkKey)
            if (after.era != before.era || after.generation != before.generation)
                OperationCapability(operation, "unavailable", "Observer era changed while checking capability")
            else OperationCapability(operation, "verified", "Qualified host, configuration and observed network", record.evidenceId,
                qualificationFingerprint(record, networkKey, requireNotNull(before.era), "host:${targetHost.id}", operation))
        } catch (_: Exception) {
            OperationCapability(operation, "unavailable", "Unable to verify observer, host artifacts or qualification evidence")
        }
    }

    fun requireHostOperation(targetHost: Host, observerHost: Host, observerNode: Node, networkKey: String, configSha256: String, operation: String) {
        val result = hostCapability(targetHost, observerHost, observerNode, networkKey, configSha256, operation)
        check(result.status == "verified") { "Operation $operation unavailable: ${result.reason}" }
    }

    /** Records operator-supplied immutable evidence; fingerprints must match the live target. */
    fun recordQualification(host: Host, node: Node, expectedNetworkKey: String, qualification: Qualification) {
        require(host.id == node.hostId && qualification.operation in operations)
        require(qualification.status in setOf("verified", "unsupported", "unverified", "unavailable"))
        require(qualification.evidenceId.isNotBlank() && qualification.evidenceId.length <= 255)
        val hashes = listOf(qualification.cliSha256, qualification.nodeSha256, qualification.configSha256, qualification.evidenceSha256)
            .map { it.lowercase() }
        require(hashes.all { digestPattern.matches(it) }) { "Invalid evidence digest" }
        val before = cardanoRepository.evidence(node)
        require(before.freshness == "current" && before.networkKey == expectedNetworkKey &&
            before.source == "host:${host.id}/node:${node.id}" && before.era != null) { "Current target era unavailable" }
        require(qualification.expectedEra == before.era && qualification.expectedGeneration == before.generation) { "Qualification was reviewed for another era generation" }
        require(fingerprints(host, node) == hashes.take(3)) { "Qualification artifacts do not match target" }
        val after = cardanoRepository.evidence(node)
        require(after.freshness == "current" && after.era == before.era && after.generation == before.generation &&
            after.source == before.source && after.networkKey == before.networkKey) { "Target era changed" }
        jdbc.update(
            """INSERT INTO qualified_operations (network_key, era, host_id, node_id, operation, status, cli_sha256, node_sha256, config_sha256, evidence_id, evidence_sha256, era_generation)
               SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
               WHERE EXISTS (SELECT 1 FROM era_observations WHERE network_key = ? AND era = ? AND generation = ?)
               ON CONFLICT (network_key, era, host_id, node_id, operation) DO UPDATE SET
               status = EXCLUDED.status, cli_sha256 = EXCLUDED.cli_sha256, node_sha256 = EXCLUDED.node_sha256,
               config_sha256 = EXCLUDED.config_sha256, evidence_id = EXCLUDED.evidence_id,
               evidence_sha256 = EXCLUDED.evidence_sha256, era_generation = EXCLUDED.era_generation""",
            before.networkKey, before.era, host.id, node.id, qualification.operation, qualification.status,
            hashes[0], hashes[1], hashes[2], qualification.evidenceId, hashes[3], before.generation,
            before.networkKey, before.era, before.generation,
        ).also { check(it == 1) { "Target era changed" } }
    }

    private fun legacyEra(era: String?) = era in setOf("byron", "shelley", "allegra", "mary", "alonzo", "babbage", "conway")

    fun capability(host: Host, node: Node, operation: String): OperationCapability =
        capability(host, node, operation) { fingerprints(host, node) }

    private fun capability(host: Host, node: Node, operation: String, artifactFingerprints: () -> List<String>): OperationCapability {
        require(operation in operations) { "Unknown operation: $operation" }
        if (host.id != node.hostId) return OperationCapability(operation, "unavailable", "Host does not own target node")
        val evidence = try { cardanoRepository.evidence(node) } catch (_: Exception) {
            return OperationCapability(operation, "unavailable", "Target network is unavailable")
        }
        if (evidence.freshness != "current" || evidence.source != "host:${host.id}/node:${node.id}") {
            return OperationCapability(operation, "unavailable", evidence.reason ?: "Current target era is unavailable")
        }
        // Existing era workflows predate Dijkstra qualification.
        if (legacyEra(evidence.era)) return OperationCapability(operation, "unverified", "Existing era workflow is available; no new qualification is asserted")
        val era = evidence.era ?: return OperationCapability(operation, "unavailable", "Current target era is unavailable")
        return try {
            val record = record(host, node, era, evidence.networkKey, operation)
                ?: return OperationCapability(operation, "unverified", "No qualified operation evidence for this target")
            if (record.generation != evidence.generation) return OperationCapability(operation, "unverified", "Qualification predates the current era observation", record.evidenceId)
            if (record.status != "verified") {
                OperationCapability(operation, record.status.takeIf { it in setOf("unsupported", "unverified", "unavailable") } ?: "unverified", "Operation is not qualified", record.evidenceId)
            } else if (record.evidenceId.isBlank() || !digestPattern.matches(record.evidenceHash.lowercase())) {
                OperationCapability(operation, "unverified", "Qualification provenance is incomplete")
            } else {
                val actual = artifactFingerprints()
                if (actual != listOf(record.cliHash.lowercase(), record.nodeHash.lowercase(), record.configHash.lowercase())) {
                    OperationCapability(operation, "unverified", "Installed artifacts differ from qualification evidence", record.evidenceId)
                } else {
                    val current = cardanoRepository.evidence(node)
                    if (current.freshness != "current" || current.era != era || current.generation != evidence.generation || current.source != evidence.source) {
                        OperationCapability(operation, "unavailable", "Target era changed while checking capability")
                    } else {
                        OperationCapability(operation, "verified", "Qualified for the observed target and installed artifacts", record.evidenceId,
                            qualificationFingerprint(record, evidence.networkKey, era, "host:${host.id}/node:${node.id}", operation))
                    }
                }
            }
        } catch (_: Exception) {
            OperationCapability(operation, "unavailable", "Unable to verify capability evidence or installed artifacts")
        }
    }

    fun capabilities(host: Host, node: Node): List<OperationCapability> {
        val artifacts by lazy { fingerprints(host, node) }
        return operations.sorted().map { capability(host, node, it) { artifacts } }
    }

    fun requireOperation(host: Host, node: Node, operation: String) {
        val result = capability(host, node, operation)
        val evidence = cardanoRepository.evidence(node)
        val legacy = evidence.freshness == "current" && legacyEra(evidence.era) &&
            evidence.source == "host:${host.id}/node:${node.id}" && host.id == node.hostId
        check(result.status == "verified" || legacy) { "Operation $operation unavailable: ${result.reason}" }
    }
}
