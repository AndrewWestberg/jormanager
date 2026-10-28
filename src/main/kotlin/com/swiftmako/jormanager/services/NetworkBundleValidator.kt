package com.swiftmako.jormanager.services

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import com.swiftmako.jormanager.entities.NetworkConfigurationBundle
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.GenesisLedgerDecoder
import com.swiftmako.jormanager.repositories.NetworkConfigurationBundleRepository
import com.swiftmako.jormanager.utils.Blake2b
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.erdtman.jcs.JsonCanonicalizer
import org.springframework.stereotype.Service
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

data class ResolvedNetworkBundle(val bundle: NetworkConfigurationBundle, val assets: Map<String, ByteArray>)
data class NetworkBundleOption(val bundleId: String, val resetId: String, val catalogPrefix: String, val networkMagic: Long, val systemStart: String)
data class NetworkBundleOptions(val bundles: List<NetworkBundleOption>, val status: String)

@Service
class NetworkBundleValidator(private val repository: NetworkConfigurationBundleRepository) {
    private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    @Volatile private var status = "No immutable Musashi manifest has been observed in the catalog."
    private val names = listOf("byron-genesis.json", "shelley-genesis.json", "alonzo-genesis.json", "conway-genesis.json", "dijkstra-genesis.json", "config.json", "topology.json", "peer-snapshot.json")
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    private val config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()
    private val schema by lazy {
        val formatSchema = factory.getSchema("""{"type":"object","properties":{"date":{"type":"string","format":"date-time"},"uri":{"type":"string","format":"uri"}}}""", config)
        check(formatSchema.validate(mapper.readTree("""{"date":"2026-02-30T00:00:00Z"}""")).isNotEmpty()) { "JSON Schema date-time assertions unavailable" }
        check(formatSchema.validate(mapper.readTree("""{"uri":"https://[broken"}""")).isNotEmpty()) { "JSON Schema URI assertions unavailable" }
        factory.getSchema(requireNotNull(javaClass.classLoader.getResourceAsStream("network-configuration.schema.json")), config)
    }

    fun options(): NetworkBundleOptions = NetworkBundleOptions(repository.findByValidationState("validated").map {
        NetworkBundleOption(it.bundleId, it.resetId, it.catalogPrefix, it.networkMagic, it.systemStart)
    }, status)

    fun catalogFailure(message: String) { status = "Catalog refresh failed: $message" }

    fun resolve(bundleId: String, expectedResetId: String): ResolvedNetworkBundle {
        val bundle = repository.findById(bundleId).orElseThrow { IllegalArgumentException("Unknown network bundle") }
        return validateArchivedBundle(bundle, expectedResetId)
    }

    fun validateArchivedBundle(bundle: NetworkConfigurationBundle, expectedResetId: String = bundle.resetId): ResolvedNetworkBundle {
        require(bundle.validationState == "validated" && bundle.resetId == expectedResetId) { "Bundle is unvalidated or belongs to another reset" }
        val json = mapper.readTree(bundle.assetsJson)
        require(json.isObject && json.fieldNames().asSequence().toSet() == names.toSet()) { "Stored bundle asset set is invalid" }
        val assets = names.associateWith { Base64.getDecoder().decode(requireNotNull(json.get(it)).asText()) }
        val verified = validate(bundle.manifest.toByteArray(Charsets.UTF_8), bundle.catalogPrefix, assets)
        require(verified.bundleId == bundle.bundleId && verified.resetId == bundle.resetId && verified.networkMagic == bundle.networkMagic && verified.systemStart == bundle.systemStart) { "Stored bundle identity mismatch" }
        return ResolvedNetworkBundle(bundle, assets)
    }

    fun refresh(links: List<String>, client: OkHttpClient, catalogUrl: String) {
        val origin = catalogUrl.toHttpUrl()
        val trusted = links.mapNotNull { it.toHttpUrlOrNull() }.filter {
            it.scheme == "https" && it.host == origin.host && it.port == origin.port && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null && it.query == null
        }
        val grouped = trusted.groupBy { it.pathSegments.last() }
        val manifests = grouped.keys.filter { it.startsWith("musashi-") && it.endsWith("-manifest.json") }
        if (manifests.isEmpty()) {
            status = "No immutable Musashi manifest is linked; legacy aliases are not selectable as a bundle."
            return
        }
        val failures = mutableListOf<String>()
        for (manifestName in manifests) {
            try {
                val prefix = manifestName.removeSuffix("-manifest.json")
                val expected = listOf(manifestName) + names.map { "$prefix-$it" }
                require(expected.all { grouped[it]?.size == 1 }) { "Incomplete bundle or duplicate basename for $prefix" }
                val urls = expected.associateWith { grouped.getValue(it).single() }
                require(urls.values.all { it.encodedPath.substringBeforeLast('/') == origin.encodedPath.substringBeforeLast('/') }) { "Bundle asset outside catalog directory" }
                fun download(name: String): ByteArray = client.newCall(Request.Builder().url(urls.getValue(name)).build()).execute().use {
                    require(it.isSuccessful) { "$name returned HTTP ${it.code}" }
                    it.body.bytes()
                }
                val manifest = download(manifestName)
                val assets = names.associateWith { download("$prefix-$it") }
                val bundle = validate(manifest, prefix, assets)
                persist(bundle)
            } catch (e: Exception) {
                failures.add("$manifestName: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        status = if (failures.isEmpty()) "Immutable bundle validation succeeded." else failures.joinToString("; ")
    }

    private fun persist(bundle: NetworkConfigurationBundle) {
        val existing = repository.findById(bundle.bundleId).orElse(null)
        require(existing == null || existing == bundle) { "Conflicting content for immutable bundle ${bundle.bundleId}" }
        if (existing == null) repository.save(bundle)
    }

    private fun validate(manifestBytes: ByteArray, prefix: String, assets: Map<String, ByteArray>): NetworkConfigurationBundle {
        val text = utf8(manifestBytes)
        val root = mapper.readTree(text)
        validateUnicode(root)
        val errors = schema.validate(root)
        require(errors.isEmpty()) { "Invalid ManifestV1: ${errors.joinToString()}" }
        val payload = root["payload"]
        val identity = payload["identity"]
        val resetId = sha(JsonCanonicalizer(mapper.writeValueAsString(identity)).encodedUTF8)
        val bundleId = sha(JsonCanonicalizer(mapper.writeValueAsString(payload)).encodedUTF8)
        require(root["resetId"].asText() == resetId && root["bundleId"].asText() == bundleId) { "Manifest identity hash mismatch" }
        require(prefix == "musashi-$resetId-$bundleId") { "Manifest filename does not match immutable identities" }
        Instant.parse(payload["retrievedAt"].asText())
        val trees = names.associateWith { name ->
            val entry = payload["assets"][name]
            val uri = URI(entry["sourceUrl"].asText())
            require(uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.userInfo == null && uri.fragment == null) { "Invalid source URL for $name" }
            val bytes = requireNotNull(assets[name])
            require(sha(bytes) == entry["sha256"].asText()) { "Asset digest mismatch: $name" }
            mapper.readTree(utf8(bytes)).also { require(it != null && it.isObject && it.size() > 0) { "Invalid JSON asset: $name" } }
        }
        val cfg = trees.getValue("config.json")
        rejectUnsupportedFileReferences(cfg, setOf("ByronGenesisFile", "ShelleyGenesisFile", "AlonzoGenesisFile", "ConwayGenesisFile", "DijkstraGenesisFile"))
        val genesis = names.take(5)
        genesis.forEach { name ->
            val era = name.substringBefore('-').replaceFirstChar { it.uppercase() }
            val ref = cfg["${era}GenesisFile"]?.asText() ?: error("Missing ${era}GenesisFile")
            require(ref == name || ref == "musashi-$name" || ref == "$prefix-$name") { "Invalid $era genesis reference" }
            val expectedHash = identity["genesisHashes"][name].asText()
            val matches = if (era == "Byron") {
                GenesisLedgerDecoder.verifyByronGenesisHash(utf8(assets.getValue(name)), expectedHash.hexToByteArray())
            } else {
                hex(Blake2b.hash256(assets.getValue(name))) == expectedHash
            }
            require(matches && expectedHash == cfg["${era}GenesisHash"]?.asText()) { "$era genesis hash mismatch" }
        }
        val byron = trees.getValue("byron-genesis.json")
        val shelley = trees.getValue("shelley-genesis.json")
        val magic = identity["networkMagic"].asLong()
        val start = identity["systemStart"].asText()
        require(byron.path("protocolConsts").path("protocolMagic").asLong(-1) == magic && shelley.path("networkMagic").asLong(-1) == magic) { "Genesis network magic mismatch" }
        require(byron.path("startTime").asText() == start && Instant.parse(shelley.path("systemStart").asText()).epochSecond.toString() == start) { "Genesis system start mismatch" }
        val peers = trees.getValue("peer-snapshot.json")
        require(peers.path("NetworkMagic").asLong(-1) == magic && peers.path("Point").isObject && peers.path("bigLedgerPools").isArray) { "Peer snapshot does not match the selected network" }
        val topology = trees.getValue("topology.json")
        rejectUnsupportedFileReferences(topology, setOf("peerSnapshotFile"))
        require(topology.path("peerSnapshotFile").asText() in listOf("peer-snapshot.json", "musashi-peer-snapshot.json", "$prefix-peer-snapshot.json")) { "Topology does not reference the matching peer snapshot" }
        val encoded = mapper.createObjectNode()
        names.forEach { encoded.put(it, Base64.getEncoder().encodeToString(assets.getValue(it))) }
        return NetworkConfigurationBundle(bundleId, resetId, prefix, magic, start, text, mapper.writeValueAsString(encoded), "validated")
    }

    private fun rejectUnsupportedFileReferences(node: JsonNode, allowed: Set<String>, root: Boolean = true) {
        if (node.isObject) {
            node.fields().forEachRemaining { (key, value) ->
                val lower = key.lowercase()
                val isFileReference = listOf("file", "files", "path", "paths", "directory", "directories", "folder", "folders")
                    .any { lower.endsWith(it) }
                require(!isFileReference || (root && key in allowed) || value.isNull) {
                    "Unsupported local file reference: $key"
                }
                if (value.isContainerNode) rejectUnsupportedFileReferences(value, emptySet(), false)
            }
        } else if (node.isArray) {
            node.forEach { rejectUnsupportedFileReferences(it, emptySet(), false) }
        }
    }

    private fun validateUnicode(node: JsonNode) {
        fun checkString(value: String) {
            var i = 0
            while (i < value.length) {
                val c = value[i++]
                if (Character.isHighSurrogate(c)) {
                    require(i < value.length && Character.isLowSurrogate(value[i++])) { "Invalid Unicode surrogate" }
                } else require(!Character.isLowSurrogate(c)) { "Invalid Unicode surrogate" }
            }
        }
        if (node.isTextual) checkString(node.asText())
        if (node.isObject) node.fieldNames().forEachRemaining { checkString(it) }
        if (node.isContainerNode) node.elements().forEachRemaining { validateUnicode(it) }
    }

    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    private fun sha(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
