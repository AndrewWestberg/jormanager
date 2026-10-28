package com.swiftmako.jormanager.services

import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.repositories.CardanoRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/** Qualified evidence is tied to the network, node, operation and actual installed artifacts. */
@Service
class CardanoCapabilities(private val cardanoRepository: CardanoRepository, private val jdbc: JdbcTemplate) {
    data class OperationCapability(val operation: String, val status: String, val reason: String, val evidenceId: String? = null)
    private data class Record(val status: String, val cliHash: String, val nodeHash: String, val configHash: String, val evidenceId: String, val evidenceHash: String)

    companion object {
        val operations = setOf(
            "wallet_send", "wallet_reward_withdrawal", "wallet_stake_registration",
            "wallet_stake_deregistration", "wallet_fee_preview", "wallet_stake_delegation",
            "node.create", "pool.register", "pool.update", "pool.retire", "governance.vote",
        )
        private val digestPattern = Regex("[0-9a-f]{64}")
        private val pathPattern = Regex("[A-Za-z0-9_./-]+")
    }

    private fun record(host: Host, node: Node, era: String, network: String, operation: String): Record? =
        jdbc.query(
            "SELECT status, cli_sha256, node_sha256, config_sha256, evidence_id, evidence_sha256 FROM qualified_operations WHERE network_key = ? AND era = ? AND host_id = ? AND node_id = ? AND operation = ?",
            { rs, _ -> Record(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)) },
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

    fun capability(host: Host, node: Node, operation: String): OperationCapability =
        capability(host, node, operation) { fingerprints(host, node) }

    private fun capability(host: Host, node: Node, operation: String, artifactFingerprints: () -> List<String>): OperationCapability {
        require(operation in operations) { "Unknown operation: $operation" }
        if (host.id != node.hostId) return OperationCapability(operation, "unavailable", "Host does not own target node")
        val evidence = try { cardanoRepository.evidence(node) } catch (_: Exception) {
            return OperationCapability(operation, "unavailable", "Target network is unavailable")
        }
        if (evidence.freshness != "current" || evidence.source != "host:${host.id}/node:${node.id}") {
            return OperationCapability(operation, "unavailable", "Current target era is unavailable")
        }
        // Existing Conway behavior predates Dijkstra qualification; do not label it newly verified.
        if (evidence.era == "conway") return OperationCapability(operation, "unverified", "Existing Conway workflow is available; no new qualification is asserted")
        val era = evidence.era ?: return OperationCapability(operation, "unavailable", "Current target era is unavailable")
        return try {
            val record = record(host, node, era, evidence.networkKey, operation)
                ?: return OperationCapability(operation, "unverified", "No qualified operation evidence for this target")
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
                        OperationCapability(operation, "verified", "Qualified for the observed target and installed artifacts", record.evidenceId)
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
        val legacyConway = evidence.freshness == "current" && evidence.era == "conway" &&
            evidence.source == "host:${host.id}/node:${node.id}" && host.id == node.hostId
        check(result.status == "verified" || legacyConway) { "Operation $operation unavailable: ${result.reason}" }
    }
}
