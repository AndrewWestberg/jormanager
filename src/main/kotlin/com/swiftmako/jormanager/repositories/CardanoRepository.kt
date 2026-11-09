package com.swiftmako.jormanager.repositories

import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.model.Config
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.model.QueryTip
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Controller

/** A node response is the only authority for the current era. Block eras are refresh triggers only. */
@Controller
class CardanoRepository(
    private val queryTipAdapter: JsonAdapter<QueryTip>,
    private val shelleyGenesisAdapter: JsonAdapter<GenesisShelley>,
    private val configAdapter: JsonAdapter<Config>,
    private val fileRepository: FileRepository,
) {
    data class EraEvidence(
        val networkKey: String,
        val era: String? = null,
        val freshness: String = "unavailable",
        val source: String? = null,
        val observedPoint: ChainPoint? = null,
        val observedAt: String? = null,
        // Changes only when the actual era changes, not when provenance is refreshed.
        val generation: Long = 0,
        val reason: String? = null,
    )

    data class ChainPoint(val slot: String, val hash: String)

    private class Entry(key: String) {
        var evidence = EraEvidence(key)
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private fun entryKey(key: String, node: Node) = "$key:${node.id}"

    private fun genesis(node: Node): Pair<GenesisShelley, String> {
        val file = requireNotNull(fileRepository.findByIdOrNull(node.genesisShelleyFileId)) { "Shelley genesis unavailable" }
        return requireNotNull(shelleyGenesisAdapter.fromJson(file.content)) { "Invalid Shelley genesis" } to file.content
    }

    /** Includes system start and genesis identity, not just the reusable network magic. */
    fun networkKey(node: Node): String {
        val (genesis, content) = genesis(node)
        val config = requireNotNull(fileRepository.findByIdOrNull(node.configFileId)) { "Node configuration unavailable" }
        val parsed = requireNotNull(configAdapter.fromJson(config.content)) { "Invalid node configuration" }
        val byronHash = requireNotNull(parsed.byronGenesisHash) { "Byron genesis identity unavailable" }
        require(parsed.shelleyGenesisHash.isNotBlank() && byronHash.isNotBlank() &&
            parsed.requiresNetworkMagic in setOf("RequiresMagic", "RequiresNoMagic") &&
            genesis.systemStart.isNotBlank() && genesis.networkMagic != null) { "Incomplete network identity" }
        val hashes = listOf(byronHash, parsed.shelleyGenesisHash, parsed.alonzoGenesisHash, parsed.conwayGenesisHash, parsed.dijkstraGenesisHash)
        require(hashes.filterNotNull().all { it.matches(Regex("[0-9a-fA-F]{64}")) }) { "Invalid genesis identity" }
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        // Preserve existing persisted network keys; new networks use the reserved empty slot.
        return listOf(genesis.networkMagic.toString(), genesis.systemStart, digest, parsed.requiresNetworkMagic!!,
            node.networkKeyDiscriminator ?: "-", *hashes.map { it?.lowercase() ?: "-" }.toTypedArray()).joinToString(":")
    }

    fun networkArguments(node: Node): List<String> = magic(node).split(" ")

    private fun magic(node: Node): String {
        val genesis = genesis(node).first
        return if (genesis.networkId.equals("testnet", ignoreCase = true)) {
            "--testnet-magic ${requireNotNull(genesis.networkMagic)}"
        } else {
            require(genesis.networkId.equals("mainnet", ignoreCase = true)) { "Unknown network id" }
            "--mainnet"
        }
    }

    private fun socket(host: Host, node: Node) = "--socket-path ${host.nodeHomePath}/${node.name}/db/socket"


    fun evidence(node: Node): EraEvidence {
        val key = networkKey(node)
        val entry = entries[entryKey(key, node)] ?: return EraEvidence(key)
        return synchronized(entry) { entry.evidence }
    }

    fun reestablishTarget(host: Host, node: Node) {
        getEra(host, node, magic(node), socket(host, node))
    }

    fun getEra(host: Host, node: Node, magicString: String, socketPath: String): String {
        require(host.id == node.hostId && magicString == magic(node) && socketPath == socket(host, node)) { "Wrong network or node socket" }
        val key = networkKey(node)
        val entry = entries.computeIfAbsent(entryKey(key, node)) { Entry(key) }
        return synchronized(entry) {
            try {
                val tip = requireNotNull(queryTipAdapter.fromJson(HostConnection(host, node).commandArgs(
                    listOf(host.cardanoCliPath, "query", "tip") + magicString.split(" ") + socketPath.split(" ") + "--output-json"
                ))) { "Node tip unavailable" }
                val era = tip.era?.lowercase()
                require(era in setOf("byron", "shelley", "allegra", "mary", "alonzo", "babbage", "conway", "dijkstra")) { "Unknown node era" }
                val generation = entry.evidence.generation + if (entry.evidence.era != era) 1 else 0
                entry.evidence = EraEvidence(key, era, "current", "host:${host.id}/node:${node.id}",
                    tip.hash.takeIf { it.isNotBlank() }?.let { ChainPoint(tip.slot.toString(), it) }, Instant.now().toString(), generation)
                requireNotNull(era)
            } catch (e: Exception) {
                entry.evidence = entry.evidence.copy(freshness = "unavailable")
                throw e
            }
        }
    }

    fun observeBlockEra(host: Host, node: Node, eraNumber: Int) {
        val current = evidence(node)
        if (current.freshness != "current" || QueryTip(current.era.orEmpty()).eraNumber != eraNumber) {
            reestablishTarget(host, node)
        }
    }

}
