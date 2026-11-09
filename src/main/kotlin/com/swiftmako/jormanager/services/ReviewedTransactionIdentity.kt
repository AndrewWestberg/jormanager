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

/** Compares original bytes; inclusion validity is not part of the submitted envelope. */
object ReviewedTransactionIdentity {
    data class StoredMember(val body: ByteArray, val witnesses: ByteArray, val auxiliary: ByteArray?)
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
    private val childrenKey = BigInteger.valueOf(23)

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

    fun matches(era: Int, signedBatchHex: String, included: DecodedTransactionBatch): Boolean =
        compare(era, signedBatchHex, included.children.size + 1) { index, body, witnesses, auxiliary ->
            val actual = if (index == 0) included.parent else included.children[index - 1]
            body.sameEncoding(actual.body) && witnesses.sameEncoding(actual.witnesses) &&
                sameAuxiliary(auxiliary, actual.auxiliaryData, era != QueryTip.ERA_DIJKSTRA)
        }

    fun matchesStored(era: Int, signedBatchHex: String, included: List<StoredMember>): Boolean =
        compare(era, signedBatchHex, included.size) { index, body, witnesses, auxiliary ->
            val actual = included[index]
            body.sameEncoding(actual.body) && witnesses.sameEncoding(actual.witnesses) &&
                if (era != QueryTip.ERA_DIJKSTRA && auxiliary.isNull() &&
                    (actual.auxiliary == null || actual.auxiliary.size == 1 && actual.auxiliary[0] == 0xf6.toByte())) true
                else actual.auxiliary?.let(auxiliary::sameEncoding) ?: false
        }

    private inline fun compare(
        era: Int,
        signedBatchHex: String,
        count: Int,
        matchesMember: (Int, CborSpan, CborSpan, CborSpan) -> Boolean,
    ): Boolean {
        return try {
            val parts = CborSpans.parse(hex.parseHex(signedBatchHex)).array()
            require(parts.size == 3 || parts.size == 4)
            if (parts.size == 4) require(parts[2].boolean())
            val body = parts[0]
            var childSet: CborSpan? = null
            if (era == QueryTip.ERA_DIJKSTRA) {
                require(body.major == 5)
                var index = 0
                while (index < body.children.size) {
                    if (body.children[index].integer() == childrenKey) {
                        require(childSet == null)
                        childSet = body.children[index + 1]
                    }
                    index += 2
                }
            }
            val children = childSet?.let { if (it.major == 6) it.tagged(258).array() else it.array() } ?: emptyList()
            require(count == children.size + 1 && (childSet == null || children.isNotEmpty()))
            if (!matchesMember(0, body, parts[1], parts.last())) return false
            for (index in children.indices) {
                val child = children[index].array()
                require(child.size == 3)
                if (!matchesMember(index + 1, child[0], child[1], child[2])) return false
            }
            true
        } catch (_: Exception) {
            // Corrupt or different pending intent must not prevent unrelated chain adoption.
            false
        }
    }

    private fun sameAuxiliary(expected: CborSpan, actual: CborSpan?, historical: Boolean): Boolean {
        // Historical block maps omit absent auxiliary data; submissions encode CBOR null.
        if (historical && expected.isNull() && (actual == null || actual.isNull())) return true
        return actual != null && expected.sameEncoding(actual)
    }
}
