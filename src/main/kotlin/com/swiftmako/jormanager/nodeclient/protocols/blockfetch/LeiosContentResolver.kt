package com.swiftmako.jormanager.nodeclient.protocols.blockfetch

import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.DecodedTransactionBatch
import com.swiftmako.jormanager.utils.Blake2b
import com.swiftmako.jormanager.utils.CborSpans
import java.io.ByteArrayOutputStream
import java.math.BigInteger

/** Pure interpretation of a LeiosFetch/LeiosDB EB body and its complete ordered closure.
 * The caller must establish which announced EB was certified; a certificate does not itself contain that hash.
 */
object LeiosContentResolver {
    data class Entry(val fullTransactionHash: ByteArray, val size: Long, val batch: DecodedTransactionBatch)
    data class Closure(val ebHash: ByteArray, val entries: List<Entry>)

    fun references(certifiedEbHash: ByteArray, announcedSize: Long, body: ByteArray): List<Pair<ByteArray, Long>> {
        require(certifiedEbHash.size == 32 && announcedSize >= 0)
        val entries = CborSpans.parse(body).entries()
        require(entries.isNotEmpty()) { "Empty endorser block" }
        val canonical = ByteArrayOutputStream()
        encodeUnsigned(canonical, 5, entries.size.toLong())
        val seen = HashSet<String>()
        val refs = entries.map { (key, value) ->
            require(key.major == 2)
            val hash = key.payload(); require(hash.size == 32)
            val id = hash.toHexString(); require(seen.add(id)) { "Duplicate endorser transaction" }
            require(value.major == 0 && value.integer() <= BigInteger.valueOf(0xffffffffL))
            val size = value.integer().toLong()
            encodeUnsigned(canonical, 2, 32); canonical.write(hash); encodeUnsigned(canonical, 0, size)
            hash to size
        }
        val encoded = canonical.toByteArray()
        require(encoded.size.toLong() == announcedSize) { "Endorser body size mismatch" }
        require(Blake2b.hash256(encoded).contentEquals(certifiedEbHash)) { "Endorser body hash mismatch" }
        return refs
    }

    fun resolve(certifiedEbHash: ByteArray, announcedSize: Long, body: ByteArray, transactionsByHash: Map<String, ByteArray>): Closure {
        val refs = references(certifiedEbHash, announcedSize, body)
        val resolved = refs.map { (hash, size) ->
            val id = hash.toHexString()
            val bytes = requireNotNull(transactionsByHash[id]) { "Missing endorser transaction $id" }
            require(bytes.size.toLong() == size && Blake2b.hash256(bytes).contentEquals(hash)) { "Endorser transaction commitment mismatch" }
            Entry(hash, size, DijkstraBlockDecoder.decodeIncludedTransaction(bytes))
        }
        return Closure(certifiedEbHash.copyOf(), resolved)
    }

    private fun encodeUnsigned(out: ByteArrayOutputStream, major: Int, n: Long) {
        require(n >= 0)
        val prefix = major shl 5
        when {
            n < 24 -> out.write(prefix or n.toInt())
            n <= 0xff -> { out.write(prefix or 24); out.write(n.toInt()) }
            n <= 0xffff -> { out.write(prefix or 25); out.write((n shr 8).toInt()); out.write(n.toInt()) }
            else -> { out.write(prefix or 26); for (shift in 24 downTo 0 step 8) out.write((n shr shift).toInt()) }
        }
    }
}
