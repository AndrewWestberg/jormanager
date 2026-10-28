package com.swiftmako.jormanager.nodeclient.protocols.blockfetch

import com.swiftmako.jormanager.model.DecodedDijkstraHeader
import com.swiftmako.jormanager.model.DecodedLedgerBlock
import com.swiftmako.jormanager.model.DecodedTransactionBatch
import com.swiftmako.jormanager.utils.CborSpan
import com.swiftmako.jormanager.utils.CborSpans
import java.math.BigInteger

/** The era is the normalized hard-fork era index, not a node version. */
object LedgerBlockDecoder {
    fun decode(era: Int, bytes: ByteArray, byronEpochSlots: Long? = null): DecodedLedgerBlock = when (era) {
        7 -> DijkstraBlockDecoder.decodeBlock(bytes)
        in 1..6 -> historical(era, bytes)
        0 -> ByronBlockDecoder.decode(bytes, requireNotNull(byronEpochSlots) { "Byron genesis epochSlots is required" })
        else -> throw UnsupportedOperationException("Unsupported ledger era $era")
    }

    private fun historical(era: Int, bytes: ByteArray): DecodedLedgerBlock {
        val root = CborSpans.parse(bytes)
        val p = root.array()
        require(p.size == if (era >= 4) 5 else 4) { "Invalid historical block envelope" }
        val bodies = p[1].array()
        val witnesses = p[2].array()
        require(bodies.size == witnesses.size) { "Transaction/witness count mismatch" }
        val auxiliary = HashMap<Int, CborSpan>()
        for ((key, value) in p[3].entries()) {
            val index = index(key, bodies.size)
            require(auxiliary.put(index, value) == null) { "Duplicate auxiliary transaction index" }
        }
        val invalid = HashSet<Int>()
        if (era >= 4) {
            val set = if (p[4].major == 6) p[4].tagged(258) else p[4]
            for (value in set.array()) require(invalid.add(index(value, bodies.size))) { "Duplicate invalid transaction index" }
        }
        val transactions = bodies.indices.map { i ->
            val member = DijkstraBlockDecoder.member(bodies[i], witnesses[i], auxiliary[i], true, era = era)
            DecodedTransactionBatch(bodies[i], member, emptyList(), i !in invalid)
        }
        return DecodedLedgerBlock(p[0], root, transactions, null, null, historicalHeader(era, p[0]), encoded = bytes)
    }

    private fun index(span: CborSpan, count: Int): Int {
        require(span.major == 0 && span.integer() < BigInteger.valueOf(count.toLong())) { "Transaction index out of range" }
        return span.integer().toInt()
    }

    private fun historicalHeader(era: Int, raw: CborSpan): DecodedDijkstraHeader {
        val outer = raw.array(); require(outer.size == 2)
        val h = outer[0].array()
        val offset = if (era >= 5) 0 else 1
        require(h.size == if (offset == 0) 10 else 15)
        fun number(s: CborSpan): BigInteger { require(s.major == 0); return s.integer() }
        fun bytes(s: CborSpan, length: Int): ByteArray {
            require(s.major == 2); return s.payload().also { require(it.size == length) }
        }
        val vrf = h[if (offset == 0) 5 else 6].array(); require(vrf.size == 2)
        val version = if (offset == 0) h[9].array().also { require(it.size == 2) } else listOf(h[13], h[14])
        return DecodedDijkstraHeader(outer[0], number(h[0]), number(h[1]),
            if (h[2].isNull()) null else bytes(h[2], 32), number(h[6 + offset]), bytes(h[7 + offset], 32),
            number(version[0]), number(version[1]), false, null,
            bytes(h[3], 32), bytes(h[4], 32), bytes(vrf[0], 64), bytes(vrf[1], 80))
    }
}
