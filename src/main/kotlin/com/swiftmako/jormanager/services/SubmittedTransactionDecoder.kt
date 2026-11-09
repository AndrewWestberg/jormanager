package com.swiftmako.jormanager.services

import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.DecodedOutput
import com.swiftmako.jormanager.model.DecodedTransactionBatch
import com.swiftmako.jormanager.model.QueryTip
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.DijkstraBlockDecoder
import com.swiftmako.jormanager.utils.CborSpan
import com.swiftmako.jormanager.utils.CborSpans
import java.math.BigInteger
import java.util.HexFormat

/** Decodes CLI submission envelopes without treating them as confirmed ledger effects. */
object SubmittedTransactionDecoder {
    data class Submitted(
        val body: CborSpan,
        val id: String,
        val batch: DecodedTransactionBatch,
        val normalInputs: Set<Pair<String, Int>>,
        val collateralInputs: Set<Pair<String, Int>>,
        val outputs: Map<Pair<String, Int>, DecodedOutput>,
    ) {
        val inputs: Set<Pair<String, Int>> get() = normalInputs + collateralInputs
    }

    private val hex = HexFormat.of()

    fun decodeSubmitted(hex: String, era: String): Submitted {
        require(hex.isNotEmpty() && hex.length % 2 == 0 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Invalid transaction hex" }
        val eraNumber = QueryTip(era).eraNumber
        require(eraNumber in QueryTip.ERA_SHELLEY..QueryTip.ERA_DIJKSTRA) { "Unsupported transaction era" }
        val bytes = this.hex.parseHex(hex)
        val raw = CborSpans.parse(bytes)
        val parts = raw.array()
        require(parts.size == 3 || parts.size == 4) { "Invalid submitted transaction envelope" }
        if (eraNumber in QueryTip.ERA_ALONZO..QueryTip.ERA_CONWAY) require(parts.size == 4) { "Invalid transaction representation for target era" }
        if (parts.size == 4) require(parts[2].boolean()) { "Invalid submission validity flag" }
        val body = parts[0]
        val fields = body.entries()
        require(fields.isNotEmpty() && parts[1].major == 5) { "Invalid transaction body or witnesses" }
        val keys = HashSet<BigInteger>()
        fields.forEach { (key, _) -> require(key.major == 0 && keys.add(key.integer())) { "Invalid or duplicate transaction field" } }
        require(keys.containsAll(setOf(BigInteger.ZERO, BigInteger.ONE, BigInteger.TWO))) { "A child is not a complete submitted transaction" }
        val batch = if (eraNumber == QueryTip.ERA_DIJKSTRA) {
            DijkstraBlockDecoder.decodeSubmittedTransaction(bytes)
        } else {
            val member = DijkstraBlockDecoder.member(body, parts[1], parts.last(), true, eraNumber)
            DecodedTransactionBatch(raw, member, emptyList(), null)
        }
        val members = listOf(batch.parent) + batch.children
        val normal = members.flatMap { it.inputs }.map { it.transactionId.toHexString() to it.index }.toSet()
        val collateral = members.flatMap { it.collateralInputs }.map { it.transactionId.toHexString() to it.index }.toSet()
        val outputs = buildMap {
            for (member in members) {
                val id = member.bodyId.toHexString()
                member.outputs.forEachIndexed { index, output ->
                    require(put(id to index, output) == null) { "Duplicate transaction output reference" }
                }
            }
        }
        return Submitted(body, batch.parent.bodyId.toHexString(), batch, normal, collateral, outputs)
    }

}
