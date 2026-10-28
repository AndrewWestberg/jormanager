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
    )

    data class ChainPoint(val slot: String, val hash: String)

    private class Entry(key: String) {
        var evidence = EraEvidence(key)
        var context: String? = null
        var queryGeneration = 0L
        var initialized = false
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    private fun genesis(node: Node): Pair<GenesisShelley, String> {
        val file = requireNotNull(fileRepository.findByIdOrNull(node.genesisShelleyFileId)) { "Shelley genesis unavailable" }
        return requireNotNull(shelleyGenesisAdapter.fromJson(file.content)) { "Invalid Shelley genesis" } to file.content
    }

    /** Includes system start and genesis identity, not just the reusable network magic. */
    fun networkKey(node: Node): String {
        val (genesis, content) = genesis(node)
        val config = requireNotNull(fileRepository.findByIdOrNull(node.configFileId)) { "Node configuration unavailable" }
        val genesisHash = requireNotNull(configAdapter.fromJson(config.content)) { "Invalid node configuration" }.shelleyGenesisHash
        require(genesisHash.isNotBlank() && genesis.systemStart.isNotBlank() && genesis.networkMagic != null) { "Incomplete network identity" }
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "${genesis.networkMagic}:${genesis.systemStart}:${genesisHash.lowercase()}:$digest"
    }

    private fun context(host: Host, node: Node): String {
        require(host.id == node.hostId) { "Host does not own target node" }
        return "${host.id}:${node.id}:${host.hostname}:${host.nodeHomePath}:${node.name}:${host.cardanoCliPath}"
    }

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
        val entry = entries[key] ?: return EraEvidence(key)
        return synchronized(entry) { entry.evidence }
    }

    /** Call when a verified target connection is established or re-established. */
    fun reestablishTarget(host: Host, node: Node) {
        refresh(host, node, context(host, node))
    }

    fun getEra(host: Host, node: Node, magicString: String, socketPath: String): String {
        val target = context(host, node)
        require(magicString == magic(node) && socketPath == socket(host, node)) { "Wrong network or node socket" }
        val key = networkKey(node)
        val entry = entries.computeIfAbsent(key) { Entry(it) }
        val initialize = synchronized(entry) { !entry.initialized || entry.context != target }
        if (initialize) refresh(host, node, target)
        return synchronized(entry) {
            check(entry.context == target && entry.evidence.freshness == "current") { "Current era unavailable; reconnect the target node" }
            requireNotNull(entry.evidence.era)
        }
    }

    fun observeBlockEra(host: Host, node: Node, eraNumber: Int) {
        val target = context(host, node)
        val key = networkKey(node)
        val entry = entries.computeIfAbsent(key) { Entry(it) }
        val name = when (eraNumber) {
            QueryTip.ERA_BYRON -> "byron"
            QueryTip.ERA_SHELLEY -> "shelley"
            QueryTip.ERA_ALLEGRA -> "allegra"
            QueryTip.ERA_MARY -> "mary"
            QueryTip.ERA_ALONZO -> "alonzo"
            QueryTip.ERA_BABBAGE -> "babbage"
            QueryTip.ERA_CONWAY -> "conway"
            QueryTip.ERA_DIJKSTRA -> "dijkstra"
            else -> null
        }
        val needsRefresh = synchronized(entry) {
            val changed = entry.context != target || entry.evidence.freshness == "current" && entry.evidence.era != name || !entry.initialized
            if (changed) {
                entry.queryGeneration += 1
                entry.evidence = entry.evidence.copy(freshness = "refresh_required")
            }
            changed
        }
        if (needsRefresh) refresh(host, node, target)
    }

    private fun refresh(host: Host, node: Node, target: String) {
        val key = networkKey(node)
        val entry = entries.computeIfAbsent(key) { Entry(it) }
        val serial = synchronized(entry) {
            entry.initialized = true
            entry.context = target
            entry.queryGeneration += 1
            entry.evidence = entry.evidence.copy(freshness = "refresh_required")
            entry.queryGeneration
        }
        try {
            val connection = HostConnection(host, node)
            val selected = requireNotNull(fileRepository.findByIdOrNull(node.configFileId)) { "Node configuration unavailable" }
            val selectedHash = requireNotNull(configAdapter.fromJson(selected.content)).shelleyGenesisHash
            val installed = connection.commandReadFile("${host.nodeHomePath}/${node.name}/config.json")
            val installedHash = requireNotNull(configAdapter.fromJson(installed)).shelleyGenesisHash
            require(installedHash.equals(selectedHash, ignoreCase = true)) { "Installed node network does not match the selected network" }
            val tipJson = connection.command("${host.cardanoCliPath} query tip ${magic(node)} ${socket(host, node)} --output-json").trim()
            val tip = requireNotNull(queryTipAdapter.fromJson(tipJson)) { "Node tip unavailable" }
            val era = tip.era?.lowercase()
            require(era in setOf("byron", "shelley", "allegra", "mary", "alonzo", "babbage", "conway", "dijkstra")) { "Unknown node era" }
            // Configuration may have changed while the query was in flight.
            require(networkKey(node) == key && context(host, node) == target) { "Target network changed during query" }
            synchronized(entry) {
                if (serial == entry.queryGeneration) {
                    val old = entry.evidence
                    entry.evidence = EraEvidence(
                        networkKey = key,
                        era = era,
                        freshness = "current",
                        source = "host:${host.id}/node:${node.id}",
                        observedPoint = if (tip.hash.isNotBlank()) ChainPoint(tip.slot.toString(), tip.hash) else null,
                        observedAt = Instant.now().toString(),
                        generation = if (old.era != null && old.era != era) old.generation + 1 else old.generation,
                    )
                }
            }
        } catch (e: Exception) {
            synchronized(entry) {
                if (serial == entry.queryGeneration) entry.evidence = entry.evidence.copy(freshness = "unavailable")
            }
            throw e
        }
    }
}
