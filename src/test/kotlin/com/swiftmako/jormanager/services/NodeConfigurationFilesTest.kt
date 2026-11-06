package com.swiftmako.jormanager.services

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper

class NodeConfigurationFilesTest {
    private val validator = NodeConfigurationFiles(mockk())
    private val json = JsonMapper()

    @Test
    fun musashiReferencesBecomeLocalWithoutLosingBootstrapPeers() {
        val assets = configuration(true)
        validator.validate(assets)
        val topology = json.readTree(validator.renderTopology(assets))
        assertThat(topology.path("peerSnapshotFile").asString()).isEqualTo("peer-snapshot.json")
        assertThat(topology.path("bootstrapPeers")[0].path("address").asString()).isEqualTo("relay.example.test")
        assertThat(topology.path("bootstrapPeers")[0].path("port").asInt()).isEqualTo(3001)
    }

    @Test
    fun conwayDoesNotAcquireDijkstraOrPeerSnapshotRequirements() {
        val assets = configuration(false)
        validator.validate(assets)
        assertThat(json.readTree(validator.renderTopology(assets))).isEqualTo(json.readTree(assets.getValue("topology.json")))
    }

    @Test
    fun missingReferencedFilesAreRejected() {
        val missingGenesis = configuration(true).apply { remove("dijkstra-genesis.json") }
        assertThat(assertThrows<IllegalArgumentException> { validator.validate(missingGenesis) }.message).contains("Dijkstra genesis")
        val missingPeers = configuration(true).apply { remove("peer-snapshot.json") }
        assertThat(assertThrows<IllegalArgumentException> { validator.validate(missingPeers) }.message).contains("peer snapshot")
    }

    @Test
    fun genesisTamperingAndDifferentPeerNetworkAreRejected() {
        val tampered = configuration(true).apply { put("conway-genesis.json", "{\"changed\":true}".toByteArray()) }
        assertThat(assertThrows<IllegalArgumentException> { validator.validate(tampered) }.message).contains("Conway genesis hash")
        val wrongPeers = configuration(true).apply { put("peer-snapshot.json", "{\"NetworkMagic\":1,\"Point\":{},\"bigLedgerPools\":[]}".toByteArray()) }
        assertThat(assertThrows<IllegalArgumentException> { validator.validate(wrongPeers) }.message).contains("Peer snapshot")
    }

    @Test
    fun duplicateAndTrailingJsonAreRejected() {
        val duplicate = configuration(false).apply { put("topology.json", "{\"localRoots\":[],\"localRoots\":[{}]}".toByteArray()) }
        assertThrows<JacksonException> { validator.validate(duplicate) }
        val trailing = configuration(false).apply { put("topology.json", "{} {}".toByteArray()) }
        assertThrows<JacksonException> { validator.validate(trailing) }
    }

    @Test
    fun archivePathsAndUnmanagedFileReferencesAreRejected() {
        val escaped = configuration(false).apply { put("../outside.json", "{}".toByteArray()) }
        assertThat(assertThrows<IllegalArgumentException> { validator.validate(escaped) }.message).contains("filename")
        val reference = configuration(false).apply { put("topology.json", "{\"otherFile\":\"/etc/passwd\"}".toByteArray()) }
        assertThat(assertThrows<IllegalArgumentException> { validator.validate(reference) }.message).contains("Unsupported local file reference")
    }

    // Hashes independently calculated with Python hashlib.blake2b(digest_size=32).
    private fun configuration(dijkstra: Boolean): MutableMap<String, ByteArray> {
        val emptyHash = "c09da522dac261c3d2566230bed10d2f3ef13f8e7654576c12e857e07f786098"
        val config = """{
            "RequiresNetworkMagic":"RequiresMagic",
            "ByronGenesisHash":"05df5fb533f1c30bdca8821131a5160e707ca393b19df4ecee2cd036a60c8b1e",
            "ShelleyGenesisHash":"fa6b1cef09327da20e27dddf40d44dc5cfb01f0e06906d7c76f48157784e5acb",
            "AlonzoGenesisHash":"$emptyHash","ConwayGenesisHash":"$emptyHash"
            ${if (dijkstra) ",\"DijkstraGenesisFile\":\"musashi-dijkstra-genesis.json\",\"DijkstraGenesisHash\":\"$emptyHash\"" else ""}
        }"""
        val topology = """{"bootstrapPeers":[{"address":"relay.example.test","port":3001}]${if (dijkstra) ",\"peerSnapshotFile\":\"musashi-peer-snapshot.json\"" else ""}}"""
        return mutableMapOf(
            "byron-genesis.json" to """{"protocolConsts":{"protocolMagic":164},"startTime":0}""".toByteArray(),
            "shelley-genesis.json" to """{"networkMagic":164,"systemStart":"1970-01-01T00:00:00Z"}""".toByteArray(),
            "alonzo-genesis.json" to "{}".toByteArray(), "conway-genesis.json" to "{}".toByteArray(),
            "config.json" to config.toByteArray(), "topology.json" to topology.toByteArray(),
        ).apply {
            if (dijkstra) {
                put("dijkstra-genesis.json", "{}".toByteArray())
                put("peer-snapshot.json", """{"NetworkMagic":164,"Point":{},"bigLedgerPools":[]}""".toByteArray())
            }
        }
    }
}
