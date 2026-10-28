package com.swiftmako.jormanager.services

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.NetworkConfigurationBundle
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.NodeConfigurationSnapshot
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NetworkConfigurationBundleRepository
import com.swiftmako.jormanager.repositories.NodeConfigurationSnapshotRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ArchivedNodeSnapshot(val snapshot: NodeConfigurationSnapshot, val node: Node, val host: Host)
data class NetworkConfigurationArchive(val version: Int, val bundles: List<NetworkConfigurationBundle>, val snapshots: List<ArchivedNodeSnapshot>)
data class NetworkConfigurationRestoreResult(val bundles: Int, val snapshots: Int)

@Service
class NetworkConfigurationBackup(
    objectMapper: ObjectMapper,
    private val bundles: NetworkConfigurationBundleRepository,
    private val snapshots: NodeConfigurationSnapshotRepository,
    private val nodes: NodeRepository,
    private val hosts: HostRepository,
    private val files: FileRepository,
    private val validator: NetworkBundleValidator,
) {
    private val mapper = objectMapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    @Transactional(readOnly = true)
    fun export(): ByteArray {
        val records = snapshots.findAll().map { snapshot ->
            val node = nodes.findById(snapshot.nodeId).orElseThrow { IllegalStateException("Snapshot node is missing") }
            val host = hosts.findById(node.hostId).orElseThrow { IllegalStateException("Snapshot host is missing") }
            ArchivedNodeSnapshot(snapshot, node, host)
        }
        return mapper.writeValueAsBytes(NetworkConfigurationArchive(1, bundles.findAll(), records))
    }

    @Transactional
    fun restore(bytes: ByteArray): NetworkConfigurationRestoreResult {
        val archive = mapper.readValue(bytes, NetworkConfigurationArchive::class.java)
        require(archive.version == 1) { "Unsupported configuration backup version" }
        require(archive.bundles.map { it.bundleId }.toSet().size == archive.bundles.size) { "Duplicate bundle in backup" }
        require(archive.snapshots.map { it.snapshot.id }.toSet().size == archive.snapshots.size) { "Duplicate snapshot in backup" }
        val resolved = archive.bundles.associate { bundle -> bundle.bundleId to validator.validateArchivedBundle(bundle) }
        archive.bundles.forEach { bundle ->
            val existing = bundles.findById(bundle.bundleId).orElse(null)
            require(existing == null || existing == bundle) { "Conflicting immutable bundle ${bundle.bundleId}" }
        }
        archive.snapshots.forEach { archived ->
            val snapshot = archived.snapshot
            val node = nodes.findById(snapshot.nodeId).orElseThrow { IllegalArgumentException("Snapshot ${snapshot.id} requires its original existing node") }
            val host = hosts.findById(node.hostId).orElseThrow { IllegalArgumentException("Snapshot ${snapshot.id} requires its original host") }
            require(node == archived.node && host == archived.host && node.type == snapshot.role) { "Snapshot ${snapshot.id} node or host identity changed" }
            val bundleId = requireNotNull(snapshot.bundleId) { "Snapshot ${snapshot.id} has no immutable bundle" }
            val bundle = resolved[bundleId] ?: bundles.findById(bundleId).orElse(null)?.let { validator.validateArchivedBundle(it) }
                ?: throw IllegalArgumentException("Snapshot ${snapshot.id} references a missing bundle")
            require(node.configurationBundleId == bundleId && node.configurationResetId == bundle.bundle.resetId && snapshot.assetsJson == bundle.bundle.assetsJson) { "Snapshot ${snapshot.id} does not match the node bundle and reset" }
            val genesisIds = mapOf("byron-genesis.json" to node.genesisByronFileId, "shelley-genesis.json" to node.genesisShelleyFileId,
                "alonzo-genesis.json" to node.genesisAlonzoFileId, "conway-genesis.json" to node.genesisConwayFileId,
                "dijkstra-genesis.json" to requireNotNull(node.genesisDijkstraFileId))
            genesisIds.forEach { (name, id) ->
                val file = files.findById(id).orElseThrow { IllegalArgumentException("Snapshot ${snapshot.id} genesis file is missing") }
                require(file.content.toByteArray(Charsets.UTF_8).contentEquals(bundle.assets.getValue(name))) { "Snapshot ${snapshot.id} genesis bytes differ" }
            }
            val existing = snapshots.findById(snapshot.id).orElse(null)
            require(existing == null || existing == snapshot) { "Conflicting immutable snapshot ${snapshot.id}" }
        }
        var addedBundles = 0
        var addedSnapshots = 0
        archive.bundles.forEach { if (!bundles.existsById(it.bundleId)) { bundles.save(it); addedBundles++ } }
        archive.snapshots.forEach { if (!snapshots.existsById(it.snapshot.id)) { snapshots.save(it.snapshot); addedSnapshots++ } }
        return NetworkConfigurationRestoreResult(addedBundles, addedSnapshots)
    }
}
