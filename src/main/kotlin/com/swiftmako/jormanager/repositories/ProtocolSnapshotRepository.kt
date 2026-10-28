package com.swiftmako.jormanager.repositories

import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.QueryTip
import com.swiftmako.jormanager.tables.ProtocolSnapshotsTable
import java.security.MessageDigest
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Repository

/** Captures the exact node parameters together with the node point at which they were observed. */
@Repository
class ProtocolSnapshotRepository(
    private val eras: CardanoRepository,
    private val tipAdapter: JsonAdapter<QueryTip>,
) {
    fun query(host: Host, node: Node, connection: HostConnection, era: String, magic: String, socket: String): String {
        val network = eras.networkKey(node)
        require(eras.getEra(host, node, magic, socket) == era) { "Target era changed" }
        val flags = magic.split(" ") + socket.split(" ")
        fun tip(): QueryTip = tipAdapter.fromJson(connection.commandArgs(listOf(host.cardanoCliPath, era, "query", "tip") + flags + "--output-json"))
            ?: error("Invalid node tip")
        val before = tip()
        require(before.era?.lowercase() == era && before.eraNumber >= 0 && before.slot >= 0 && before.hash.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid protocol snapshot point or era" }
        val raw = connection.commandArgs(listOf(host.cardanoCliPath, era, "query", "protocol-parameters") + flags + "--output-json").trim()
        require(raw.isNotEmpty()) { "Protocol parameters unavailable" }
        val after = tip()
        check(after.slot == before.slot && after.hash.equals(before.hash, true) && after.era?.lowercase() == era &&
            eras.networkKey(node) == network && eras.getEra(host, node, magic, socket) == era) { "Node point or era changed during protocol query" }
        val evidence = MessageDigest.getInstance("SHA-256").digest(
            listOf(network, host.id.toString(), node.id.toString(), before.slot.toString(), before.hash.lowercase(), era, raw)
                .joinToString("\u0000").toByteArray(Charsets.UTF_8)
        ).toHexString()
        transaction {
            ProtocolSnapshotsTable.insertIgnore {
                it[ProtocolSnapshotsTable.network] = network
                it[slot] = before.slot.toBigDecimal()
                it[hash] = before.hash.lowercase().hexToByteArray()
                it[ProtocolSnapshotsTable.era] = before.eraNumber
                it[ProtocolSnapshotsTable.raw] = raw
                it[ProtocolSnapshotsTable.evidence] = evidence
            }
        }
        return raw
    }
}
