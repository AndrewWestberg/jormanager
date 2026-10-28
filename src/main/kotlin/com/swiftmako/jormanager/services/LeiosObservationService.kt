package com.swiftmako.jormanager.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.PoolKeyObservation
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.PoolKeyObservationRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.utils.Bech32
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/** Query results supplement, but never replace, the adopted chain projection. */
@Service
class LeiosObservationService(
    private val eras: CardanoRepository,
    private val capabilities: CardanoCapabilities,
    private val files: FileRepository,
    private val observations: PoolKeyObservationRepository,
    private val nodes: NodeRepository,
    private val hosts: HostRepository,
    private val json: ObjectMapper,
) {
    fun refresh(host: Host, node: Node): PoolKeyObservation? {
        val observer = if (node.isDraft) nodes.findDefault() ?: return null else node
        val queryHost = if (node.isDraft) hosts.findByIdOrNull(observer.hostId) ?: return null else host
        if (observer.isDeleted || observer.isDraft || eras.networkKey(node) != eras.networkKey(observer)) return null
        val era = eras.evidence(observer)
        if (era.freshness != "current" || era.era != "dijkstra" || era.source != "host:${queryHost.id}/node:${observer.id}") return null
        val qualification = capabilities.capability(queryHost, observer, "leios.status")
        if (qualification.status != "verified" || qualification.qualificationFingerprint == null) return null
        val pool = requireNotNull(node.poolId)
        val poolHash = if (pool.startsWith("pool1")) Bech32.decode(pool).let {
            require(it.prefix == "pool" && it.bytes.size == 28)
            it.bytes.toHexString()
        } else pool.lowercase().also { require(it.matches(Regex("[0-9a-f]{56}"))) }
        val socket = "${queryHost.nodeHomePath}/${observer.name}/db/socket"
        require(listOf(queryHost.cardanoCliPath, socket).all { it.matches(Regex("[A-Za-z0-9_./-]+")) && !it.startsWith("-") })
        val genesis = json.readTree(requireNotNull(files.findByIdOrNull(node.genesisShelleyFileId)).content)
        val network = if (genesis.path("networkId").asText().equals("Mainnet", true)) listOf("--mainnet") else {
            val magic = genesis.path("networkMagic").asText()
            require(magic.matches(Regex("[0-9]+")))
            listOf("--testnet-magic", magic)
        }
        val connection = HostConnection(queryHost, observer)
        fun query(kind: String, extra: List<String>): JsonNode = json.readTree(connection.command(
            listOf(queryHost.cardanoCliPath, "dijkstra", "query", kind) + extra + network + listOf("--socket-path", socket, "--output-json")
        ))
        fun point(tip: JsonNode): Pair<BigDecimal, ByteArray> {
            val slot = tip.path("slot")
            require(slot.isIntegralNumber && slot.bigIntegerValue().signum() >= 0 &&
                slot.bigIntegerValue() <= BigInteger("18446744073709551615")) { "Node tip slot unavailable" }
            val hash = tip.path("hash").asText()
            require(hash.matches(Regex("[0-9a-fA-F]{64}"))) { "Node tip hash unavailable" }
            return BigDecimal(slot.bigIntegerValue()) to hash.hexToByteArray()
        }
        val startTip = query("tip", emptyList())
        val (startSlot, startHash) = point(startTip)
        fun epoch(value: JsonNode?): BigDecimal? {
            if (value == null || value.isMissingNode || value.isNull) return null
            require(value.isIntegralNumber && value.bigIntegerValue().signum() >= 0 &&
                value.bigIntegerValue() <= BigInteger("18446744073709551615")) { "Invalid observed epoch" }
            return BigDecimal(value.bigIntegerValue())
        }
        val poolState = query("pool-state", listOf("--stake-pool-id", pool))
        require(poolState.isObject)
        val entries = poolState.fields().asSequence().toList()
        require(entries.all { it.key.equals(poolHash, true) || it.key.equals(pool, true) }) { "Pool query returned a different target" }
        require(entries.size <= 1)
        val state = entries.singleOrNull()?.value
        if (state != null) require(state.isObject && state.has("blsKey") &&
            state.has("retiring") && state.has("futureStakePoolParams")) { "Invalid pool-state result" }
        fun publicKey(value: JsonNode?): ByteArray? {
            if (value == null || value.isMissingNode || value.isNull) return null
            val key = if (value.has("blsPubKey")) value else value.path("bksKey")
            val hex = key.path("blsPubKey").asText()
            require(hex.matches(Regex("[0-9a-fA-F]{192}")))
            return hex.hexToByteArray()
        }
        // GetStakePoolState is a map of pool IDs to flat QueryResultStakePoolState.
        // Current blsKey is BlsKeyState; futureStakePoolParams.blsKey is a bare BlsKey.
        val currentState = state?.path("blsKey")
        val current = publicKey(currentState)
        val future = publicKey(state?.path("futureStakePoolParams")?.path("blsKey"))
        val effectiveEpoch = if (current != null) {
            epoch(currentState?.path("bksRegisteredIn"))
                ?: throw IllegalStateException("Effective key epoch unavailable")
        } else null
        val retirement = epoch(state?.path("retiring"))
        val snapshot = query("stake-snapshot", listOf("--all-stake-pools"))
        require(snapshot.path("pools").isObject && snapshot.path("total").isObject) {
            "Stake snapshot unavailable"
        }
        val committee = query("leios-committee", emptyList())
        val setCommittee = committee.path("currentLeiosCommittee")
        val markCommittee = committee.path("futureLeiosCommittee")
        require(setCommittee.isArray && (markCommittee.isArray || markCommittee.isNull)) {
            "Leios committee unavailable"
        }
        // The node emits seat weights and rendered verification keys, but no pool IDs.
        // A nonempty committee cannot safely be mapped back to this pool.
        val selected = if (setCommittee.isEmpty) false else null
        val (endSlot, endHash) = point(query("tip", emptyList()))
        check(startSlot == endSlot && startHash.contentEquals(endHash)) {
            "Node tip changed while observing pool and stake snapshot"
        }
        val after = eras.evidence(observer)
        check(after.freshness == "current" && after.networkKey == era.networkKey && after.era == era.era &&
            after.generation == era.generation && after.source == era.source)
        val finalQualification = capabilities.capability(queryHost, observer, "leios.status")
        check(finalQualification.status == "verified" &&
            finalQualification.qualificationFingerprint == qualification.qualificationFingerprint)
        return observations.save(PoolKeyObservation(networkKey = era.networkKey, poolId = pool, observedAt = Instant.now(),
            chainSlot = endSlot, chainHash = endHash,
            currentPublicKey = current, futurePublicKey = future, effectiveEpoch = effectiveEpoch,
            scheduledRetirement = retirement, selected = selected))
    }
}
