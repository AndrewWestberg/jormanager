package com.swiftmako.jormanager.services

import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.CreateNodeRequest
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.GenesisLedgerDecoder
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.utils.Blake2b
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

/** Validates ordinary catalog selections before any node files are installed. */
@Service
class NodeConfigurationFiles(private val files: FileRepository) {
    private val mapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()
    private val assetNames = setOf("byron-genesis.json", "shelley-genesis.json", "alonzo-genesis.json", "conway-genesis.json",
        "dijkstra-genesis.json", "config.json", "topology.json", "peer-snapshot.json", "checkpoints.json")

    fun resolve(request: CreateNodeRequest): Map<String, ByteArray> {
        fun selected(id: Long, name: String) = requireNotNull(files.findByIdOrNull(id)) { "Selected $name file not found: $id" }
        val configFile = selected(request.configFileId, "config")
        val assets = linkedMapOf(
            "byron-genesis.json" to selected(request.genesisByronFileId, "Byron genesis").content.toByteArray(Charsets.UTF_8),
            "shelley-genesis.json" to selected(request.genesisShelleyFileId, "Shelley genesis").content.toByteArray(Charsets.UTF_8),
            "alonzo-genesis.json" to selected(request.genesisAlonzoFileId, "Alonzo genesis").content.toByteArray(Charsets.UTF_8),
            "conway-genesis.json" to selected(request.genesisConwayFileId, "Conway genesis").content.toByteArray(Charsets.UTF_8),
            "config.json" to configFile.content.toByteArray(Charsets.UTF_8),
            "topology.json" to selected(request.topologyFileId, "topology").content.toByteArray(Charsets.UTF_8),
        )
        request.genesisDijkstraFileId?.let { assets["dijkstra-genesis.json"] = selected(it, "Dijkstra genesis").content.toByteArray(Charsets.UTF_8) }
        request.peerSnapshotFileId?.let { assets["peer-snapshot.json"] = selected(it, "peer snapshot").content.toByteArray(Charsets.UTF_8) }
        val config = parse(assets.getValue("config.json"), "config.json")
        if (config.hasNonNull("CheckpointsFile")) {
            val name = configFile.name.removeSuffix("-config.json") + "-checkpoints.json"
            assets["checkpoints.json"] = requireNotNull(files.findByName(name)) { "Referenced checkpoints file not found: $name" }
                .content.toByteArray(Charsets.UTF_8)
        }
        validate(assets, config)
        return assets
    }

    fun validate(assets: Map<String, ByteArray>) {
        validate(assets, parse(requireNotNull(assets["config.json"]) { "Selected config file is required" }, "config.json"))
    }

    private fun validate(assets: Map<String, ByteArray>, config: JsonNode) {
        require(assetNames.containsAll(assets.keys)) { "Unsupported configuration filename" }
        val trees = assets.mapValues { (name, bytes) -> if (name == "config.json") config else parse(bytes, name) }
        rejectUnsupportedFileReferences(config, setOf("GenesisFile", "ByronGenesisFile", "ShelleyGenesisFile",
            "AlonzoGenesisFile", "ConwayGenesisFile", "DijkstraGenesisFile", "CheckpointsFile"))
        val eras = listOf("Byron", "Shelley", "Alonzo", "Conway") + if (config.hasNonNull("DijkstraGenesisFile")) listOf("Dijkstra") else emptyList()
        require(config.hasNonNull("DijkstraGenesisFile") == assets.containsKey("dijkstra-genesis.json")) {
            "Select Dijkstra genesis exactly when the selected config references DijkstraGenesisFile"
        }
        require(!config.hasNonNull("DijkstraGenesisHash") || config.hasNonNull("DijkstraGenesisFile")) { "Dijkstra genesis hash has no file reference" }
        eras.forEach { era ->
            val name = "${era.lowercase()}-genesis.json"
            val bytes = requireNotNull(assets[name]) { "Selected $era genesis file is required" }
            val reference = config.get("${era}GenesisFile")
            require(reference == null || reference.isString && reference.asString().isNotBlank()) { "Invalid $era genesis file reference" }
            val expected = config.get("${era}GenesisHash")
            if (expected != null) {
                require(expected.isString && expected.asString().matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid configured $era genesis hash" }
                val matches = if (era == "Byron") GenesisLedgerDecoder.verifyByronGenesisHash(utf8(bytes), expected.asString().hexToByteArray())
                    else Blake2b.hash256(bytes).toHexString().equals(expected.asString(), ignoreCase = true)
                require(matches) { "$era genesis hash does not match the selected config" }
            }
        }
        val byron = trees.getValue("byron-genesis.json")
        val shelley = trees.getValue("shelley-genesis.json")
        val magic = shelley.path("networkMagic")
        require(magic.isIntegralNumber && magic.canConvertToLong() && magic.asLong() in 0..4294967295L) { "Invalid Shelley network magic" }
        val byronMagic = byron.path("protocolConsts").path("protocolMagic")
        require(byronMagic.isIntegralNumber && byronMagic.canConvertToLong() && byronMagic.asLong() == magic.asLong()) { "Genesis network magic mismatch" }
        val start = byron.path("startTime")
        require(start.isIntegralNumber && start.canConvertToLong() && start.asLong() >= 0 &&
            Instant.parse(shelley.path("systemStart").stringValue()).epochSecond == start.asLong()) { "Genesis system start mismatch" }
        require(config.path("RequiresNetworkMagic").asString() in setOf("RequiresMagic", "RequiresNoMagic")) { "Invalid network magic policy" }
        val topology = requireNotNull(trees["topology.json"]) { "Selected topology file is required" }
        rejectUnsupportedFileReferences(topology, setOf("peerSnapshotFile"))
        val peerReference = topology.get("peerSnapshotFile")
        val needsPeers = peerReference != null && !peerReference.isNull
        require(needsPeers == assets.containsKey("peer-snapshot.json")) { "Select a peer snapshot exactly when the selected topology references peerSnapshotFile" }
        if (needsPeers) {
            require(peerReference.isString && peerReference.asString().isNotBlank()) { "Invalid peer snapshot reference" }
            val peers = trees.getValue("peer-snapshot.json")
            val peerMagic = peers.path("NetworkMagic")
            require(peerMagic.isIntegralNumber && peerMagic.canConvertToLong() && peerMagic.asLong() == magic.asLong() &&
                peers.path("Point").isObject && peers.path("bigLedgerPools").isArray) { "Peer snapshot does not match the selected network" }
        }
        if (config.hasNonNull("CheckpointsFile")) {
            require(config.path("CheckpointsFile").isString && assets.containsKey("checkpoints.json")) { "Referenced checkpoints file is required" }
        }
    }

    fun renderTopology(assets: Map<String, ByteArray>): String {
        val topology = parse(assets.getValue("topology.json"), "topology.json") as ObjectNode
        if (topology.hasNonNull("peerSnapshotFile")) {
            require(assets.containsKey("peer-snapshot.json")) { "Referenced peer snapshot is required" }
            topology.put("peerSnapshotFile", "peer-snapshot.json")
        }
        return mapper.writeValueAsString(topology) + "\n"
    }

    private val fileReference = Regex("(file|files|path|paths|directory|directories|folder|folders)$", RegexOption.IGNORE_CASE)

    private fun rejectUnsupportedFileReferences(node: JsonNode, allowed: Set<String> = emptySet()) {
        if (node.isObject) node.properties().forEach { (key, value) ->
            require(!fileReference.containsMatchIn(key) || key in allowed || value.isNull) { "Unsupported local file reference: $key" }
            if (value.isContainer) rejectUnsupportedFileReferences(value)
        } else if (node.isArray) node.forEach { rejectUnsupportedFileReferences(it) }
    }

    private fun parse(bytes: ByteArray, name: String): JsonNode = mapper.readTree(utf8(bytes)).also {
        require(it != null && it.isObject) { "Invalid JSON object in $name" }
    }

    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}
