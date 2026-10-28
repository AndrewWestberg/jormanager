package com.swiftmako.jormanager.repositories

import com.fasterxml.jackson.databind.ObjectMapper
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.stereotype.Controller

/** A node response is the only authority for the current era. Block eras are refresh triggers only. */
@Controller
class CardanoRepository(
    private val queryTipAdapter: JsonAdapter<QueryTip>,
    private val shelleyGenesisAdapter: JsonAdapter<GenesisShelley>,
    private val configAdapter: JsonAdapter<Config>,
    private val fileRepository: FileRepository,
    private val bundles: NetworkConfigurationBundleRepository,
    private val json: ObjectMapper,
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
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
    private class SocketIdentityUnavailable : IllegalStateException("Cannot verify configured socket owner")

    private class Entry(key: String) {
        var evidence = EraEvidence(key)
        var context: String? = null
        var queryGeneration = 0L
        var initialized = false
        var refreshing = false
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
        val reset = node.configurationResetId
        val bundleId = node.configurationBundleId
        if (bundleId != null) {
            require(reset != null && node.genesisDijkstraFileId != null && hashes.all { it != null }) { "Incomplete selected network reset" }
            val bundle = requireNotNull(bundles.findByIdOrNull(bundleId)) { "Selected configuration bundle unavailable" }
            require(bundle.validationState == "validated" && bundle.resetId == reset &&
                bundle.networkMagic == genesis.networkMagic.toLong()) { "Selected configuration reset mismatch" }
            val manifestHashes = json.readTree(bundle.manifest).path("payload").path("identity").path("genesisHashes")
            val names = listOf("byron-genesis.json", "shelley-genesis.json", "alonzo-genesis.json", "conway-genesis.json", "dijkstra-genesis.json")
            require(names.zip(hashes).all { (name, hash) -> manifestHashes.path(name).asText().equals(hash, ignoreCase = true) }) {
                "Selected genesis identities do not match configuration reset"
            }
        } else {
            require(reset == null) { "Configuration reset has no selected bundle" }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return listOf(genesis.networkMagic.toString(), genesis.systemStart, digest, parsed.requiresNetworkMagic!!, reset ?: "-", *hashes.map { it?.lowercase() ?: "-" }.toTypedArray()).joinToString(":")
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
    /** Bind the queried socket to the configured node process, not merely to a path or network magic. */
    private fun verifySocketOwner(connection: HostConnection, host: Host, node: Node): String {
        val socketPath = "${host.nodeHomePath}/${node.name}/db/socket"
        val configPath = "${host.nodeHomePath}/${node.name}/config.json"
        val safe = Regex("[A-Za-z0-9_./-]+")
        require(listOf(socketPath, configPath, host.cardanoNodePath, host.cardanoCliPath).all {
            safe.matches(it) && !it.startsWith("-")
        }) { "Unsafe node path" }
        val canonicalSocket = connection.command(listOf("readlink", "-f", socketPath)).trim()
        val canonicalConfig = connection.command(listOf("readlink", "-f", configPath)).trim()
        val canonicalNode = connection.command(listOf("readlink", "-f", host.cardanoNodePath)).trim()
        require(canonicalSocket.isNotBlank() && canonicalConfig.isNotBlank() && canonicalNode.isNotBlank()) { "Node paths unavailable" }
        val lines = connection.command(listOf("ss", "-xlpn")).lineSequence().filter { line ->
            line.split(Regex("\\s+")).any { it == socketPath || it == canonicalSocket }
        }.toList()
        val pids = lines.flatMap { Regex("pid=(\\d+)").findAll(it).map { match -> match.groupValues[1] }.toList() }.distinct()
        require(pids.size == 1) { "Socket owner unavailable or ambiguous" }
        val pid = pids.single()
        val executable = connection.command(listOf("readlink", "-f", "/proc/$pid/exe")).trim()
        require(executable == canonicalNode) { "Socket owner is not the configured node executable" }
        val args = connection.command(listOf("cat", "/proc/$pid/cmdline")).split('\u0000').filter { it.isNotEmpty() }
        fun option(name: String): String? {
            val index = args.indexOf(name)
            return if (index >= 0) args.getOrNull(index + 1) else args.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
        }
        fun resolve(path: String): String {
            require(safe.matches(path) && !path.startsWith("-")) { "Unsafe process path" }
            val absolute = if (path.startsWith("/")) path else {
                val cwd = connection.command(listOf("readlink", "-f", "/proc/$pid/cwd")).trim()
                "$cwd/$path"
            }
            return connection.command(listOf("readlink", "-f", absolute)).trim()
        }
        require(option("--config")?.let(::resolve) == canonicalConfig && option("--socket-path")?.let(::resolve) == canonicalSocket) {
            "Socket owner does not use the configured node paths"
        }
        return pid
    }


    fun evidence(node: Node): EraEvidence {
        val key = networkKey(node)
        val entry = entries[entryKey(key, node)] ?: return EraEvidence(key)
        return synchronized(entry) {
            val generation = jdbc.query("SELECT generation FROM era_observations WHERE network_key = ?", { rs, _ -> rs.getLong(1) }, key).singleOrNull()
            if (entry.evidence.freshness == "current" && generation != entry.evidence.generation)
                entry.evidence = entry.evidence.copy(freshness = "refresh_required")
            entry.evidence
        }
    }

    /** Call when a verified target connection is established or re-established. */
    fun reestablishTarget(host: Host, node: Node) {
        refresh(host, node, context(host, node), force = true)
    }

    fun getEra(host: Host, node: Node, magicString: String, socketPath: String): String {
        val target = context(host, node)
        require(magicString == magic(node) && socketPath == socket(host, node)) { "Wrong network or node socket" }
        val key = networkKey(node)
        val entry = entries.computeIfAbsent(entryKey(key, node)) { Entry(key) }
        val before = evidence(node)
        val needsRefresh = synchronized(entry) {
            !entry.initialized || entry.context != target || before.freshness == "refresh_required"
        }
        if (needsRefresh) refresh(host, node, target)
        val current = evidence(node)
        check(entry.context == target && current.freshness == "current") { "Current era unavailable; reconnect the target node" }
        return requireNotNull(current.era)
    }

    fun observeBlockEra(host: Host, node: Node, eraNumber: Int) {
        val target = context(host, node)
        val key = networkKey(node)
        val entry = entries.computeIfAbsent(entryKey(key, node)) { Entry(key) }
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
        evidence(node)
        val needsRefresh = synchronized(entry) {
            val changed = entry.context != target || entry.evidence.freshness == "refresh_required" ||
                entry.evidence.freshness == "current" && entry.evidence.era != name || !entry.initialized
            if (changed && entry.evidence.freshness != "refresh_required") {
                entry.queryGeneration += 1
                entry.evidence = entry.evidence.copy(freshness = "refresh_required")
            }
            changed
        }
        if (needsRefresh) refresh(host, node, target)
    }

    private fun refresh(host: Host, node: Node, target: String, force: Boolean = false) {
        val key = networkKey(node)
        val entry = entries.computeIfAbsent(entryKey(key, node)) { Entry(key) }
        val serial = synchronized(entry) {
            if (entry.refreshing || (!force && entry.initialized && entry.context == target && entry.evidence.freshness == "current")) return
            entry.refreshing = true
            entry.initialized = true
            entry.context = target
            entry.queryGeneration += 1
            entry.evidence = entry.evidence.copy(freshness = "refresh_required")
            entry.queryGeneration
        }
        try {
            val connection = HostConnection(host, node)
            val selected = requireNotNull(fileRepository.findByIdOrNull(node.configFileId)) { "Node configuration unavailable" }
            val selectedConfig = requireNotNull(configAdapter.fromJson(selected.content))
            val installed = connection.commandReadFile("${host.nodeHomePath}/${node.name}/config.json")
            val installedConfig = requireNotNull(configAdapter.fromJson(installed))
            val owner = try { verifySocketOwner(connection, host, node) } catch (_: Exception) {
                throw SocketIdentityUnavailable()
            }
            require(installedConfig.shelleyGenesisHash.equals(selectedConfig.shelleyGenesisHash, ignoreCase = true) &&
                installedConfig.byronGenesisHash.equals(selectedConfig.byronGenesisHash, ignoreCase = true) &&
                installedConfig.requiresNetworkMagic == selectedConfig.requiresNetworkMagic &&
                installedConfig.alonzoGenesisHash.equals(selectedConfig.alonzoGenesisHash, ignoreCase = true) &&
                installedConfig.conwayGenesisHash.equals(selectedConfig.conwayGenesisHash, ignoreCase = true) &&
                installedConfig.dijkstraGenesisHash.equals(selectedConfig.dijkstraGenesisHash, ignoreCase = true)) {
                "Installed node network does not match the selected network"
            }
            val tipJson = connection.command("${host.cardanoCliPath} query tip ${magic(node)} ${socket(host, node)} --output-json").trim()
            val tip = requireNotNull(queryTipAdapter.fromJson(tipJson)) { "Node tip unavailable" }
            val era = tip.era?.lowercase()
            require(era in setOf("byron", "shelley", "allegra", "mary", "alonzo", "babbage", "conway", "dijkstra")) { "Unknown node era" }
            // Configuration may have changed while the query was in flight.
            try {
                if (verifySocketOwner(connection, host, node) != owner) throw SocketIdentityUnavailable()
            } catch (_: Exception) {
                throw SocketIdentityUnavailable()
            }
            require(networkKey(node) == key && context(host, node) == target) { "Target network changed during query" }
            synchronized(entry) {
                if (serial == entry.queryGeneration) {
                    val source = "host:${host.id}/node:${node.id}"
                    val point = if (tip.hash.isNotBlank()) ChainPoint(tip.slot.toString(), tip.hash) else null
                    val now = Instant.now().toString()
                    val generation = requireNotNull(transactions.execute {
                        jdbc.queryForObject(
                            """INSERT INTO era_observations (network_key, era, generation, source, observed_slot, observed_hash, observed_at)
                               VALUES (?, ?, 0, ?, ?, ?, ?)
                               ON CONFLICT (network_key) DO UPDATE SET
                               generation = era_observations.generation + CASE WHEN era_observations.era <> EXCLUDED.era THEN 1 ELSE 0 END,
                               era = EXCLUDED.era, source = EXCLUDED.source, observed_slot = EXCLUDED.observed_slot,
                               observed_hash = EXCLUDED.observed_hash, observed_at = EXCLUDED.observed_at
                               RETURNING generation""",
                            Long::class.java, key, era, source, point?.slot, point?.hash, now,
                        )!!
                    })
                    entry.evidence = EraEvidence(
                        networkKey = key, era = era, freshness = "current", source = source,
                        observedPoint = point, observedAt = now, generation = generation,
                    )
                }
            }
        } catch (e: Exception) {
            synchronized(entry) {
                if (serial == entry.queryGeneration) entry.evidence = entry.evidence.copy(
                    freshness = "unavailable",
                    reason = if (e is SocketIdentityUnavailable) "Cannot verify socket owner and node process; check ss and /proc permissions and node paths" else "Node era query or network verification failed",
                )
            }
            throw e
        } finally {
            synchronized(entry) { entry.refreshing = false }
        }
    }
}
