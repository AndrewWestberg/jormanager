package com.swiftmako.jormanager.nodeclient.protocols.blockfetch

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.utils.Blake2b
import com.swiftmako.jormanager.utils.CborSpans
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32

data class DecodedGenesisOutput(val txId: ByteArray, val address: ByteArray, val coin: BigInteger)

/** Pure derivation; caller decides whether Shelley test-network injection actually applies. */
object GenesisLedgerDecoder {
    private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val maxCoin = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)

    /** Byron genesis identity hashes canonical JSON rather than the original file bytes. */
    fun verifyByronGenesisHash(json: String, expectedHash: ByteArray): Boolean {
        require(expectedHash.size == 32)
        return byronGenesisHash(json) == expectedHash.toHexString()
    }

    fun byronGenesisHash(json: String): String {
        var inside = false
        var escaped = false
        for (c in json) {
            if (escaped) {
                require(c == '"' || c == '\\') { "Unsupported Byron canonical JSON escape" }
                escaped = false
            } else if (inside && c == '\\') escaped = true
            else if (c == '"') inside = !inside
        }
        val root = mapper.readTree(json)
        return byronGenesisHash(root).toHexString()
    }

    private fun byronGenesisHash(root: JsonNode): ByteArray =
        Blake2b.hash256(byronCanonical(root).toByteArray(Charsets.UTF_8))

    private fun byronCanonical(node: JsonNode): String = when {
        node.isObject -> node.fieldNames().asSequence().toList().sorted().joinToString(",", "{", "}") { key -> byronString(key) + ":" + byronCanonical(node[key]) }
        node.isArray -> node.elements().asSequence().joinToString(",", "[", "]") { byronCanonical(it) }
        node.isTextual -> byronString(node.asText())
        node.isIntegralNumber -> node.bigIntegerValue().toString()
        node.isBoolean -> node.asText()
        node.isNull -> "null"
        else -> throw IllegalArgumentException("Unsupported Byron canonical JSON value")
    }
    private fun byronString(value: String): String {
        require(value.none { it.code < 0x20 || it.isSurrogate() }) { "Unsupported Byron string" }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    fun shelley(json: String): List<DecodedGenesisOutput> {
        val root = mapper.readTree(json)
        val funds = root.path("initialFunds"); require(funds.isObject)
        return collect(funds) { key ->
            require(key.isNotEmpty() && key.length % 2 == 0 && key.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
            key.hexToByteArray()
        }
    }

    /** networkMagic is null when the Byron protocol uses RequiresNoMagic. */
    fun byron(json: String, networkMagic: Long?): List<DecodedGenesisOutput> {
        if (networkMagic != null) require(networkMagic in 0..0xffffffffL)
        val root = mapper.readTree(json)
        val nonAvvm = root.path("nonAvvmBalances"); val avvm = root.path("avvmDistr")
        require(nonAvvm.isObject && avvm.isObject)
        val ordinary = collect(nonAvvm) { key ->
            val address = base58(key)
            validateByronAddress(address)
            address
        }
        val redeem = collect(avvm) { key ->
            val publicKey = Base64.getUrlDecoder().decode(key); require(publicKey.size == 32)
            redeemAddress(publicKey, networkMagic)
        }
        val all = ordinary + redeem
        require(all.map { it.txId.toHexString() }.toSet().size == all.size) { "Duplicate genesis address" }
        return all
    }

    internal fun validateByronAddress(address: ByteArray) {
        val wrapper = CborSpans.parse(address).array(); require(wrapper.size == 2)
        val body = wrapper[0].tagged(24); require(body.major == 2 && wrapper[1].major == 0)
        val raw = body.payload()
        val checksum = CRC32().apply { update(raw) }.value
        require(wrapper[1].integer() == BigInteger.valueOf(checksum)) { "Byron address CRC mismatch" }
        val inner = CborSpans.parse(raw).array(); require(inner.size == 3)
        require(inner[0].major == 2 && inner[0].payload().size == 28)
        inner[1].entries()
        require(inner[2].major == 0 && inner[2].integer() <= BigInteger.TWO)
    }

    private fun collect(node: JsonNode, decode: (String) -> ByteArray): List<DecodedGenesisOutput> =
        node.fields().asSequence().map { (key, value) ->
            require(value.isTextual || value.isIntegralNumber)
            val amount = value.asText().toBigInteger(); require(amount.signum() >= 0 && amount <= maxCoin)
            val address = decode(key)
            DecodedGenesisOutput(Blake2b.hash256(address), address, amount)
        }.toList()

    private fun redeemAddress(key: ByteArray, magic: Long?): ByteArray {
        val attributes = if (magic == null) byteArrayOf(0xa0.toByte()) else {
            val encodedMagic = cborNumber(0, magic)
            byteArrayOf(0xa1.toByte(), 2) + cborBytes(encodedMagic)
        }
        val preimage = byteArrayOf(0x83.toByte(), 2, 0x82.toByte(), 2) + cborBytes(key) + attributes
        val root = Blake2b.hash224(MessageDigest.getInstance("SHA3-256").digest(preimage))
        val inner = byteArrayOf(0x83.toByte()) + cborBytes(root) + attributes + byteArrayOf(2)
        val crc = CRC32().apply { update(inner) }.value
        return byteArrayOf(0x82.toByte(), 0xd8.toByte(), 24) + cborBytes(inner) + cborNumber(0, crc)
    }

    private fun cborBytes(bytes: ByteArray): ByteArray = cborNumber(2, bytes.size.toLong()) + bytes
    private fun cborNumber(major: Int, n: Long): ByteArray {
        require(n >= 0 && n <= 0xffffffffL)
        val out = ByteArrayOutputStream(); val prefix = major shl 5
        when {
            n < 24 -> out.write(prefix or n.toInt())
            n <= 255 -> { out.write(prefix or 24); out.write(n.toInt()) }
            n <= 65535 -> { out.write(prefix or 25); out.write((n shr 8).toInt()); out.write(n.toInt()) }
            else -> { out.write(prefix or 26); for (shift in 24 downTo 0 step 8) out.write((n shr shift).toInt()) }
        }
        return out.toByteArray()
    }

    private fun base58(text: String): ByteArray {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        require(text.isNotEmpty())
        var n = BigInteger.ZERO
        for (char in text) {
            val digit = alphabet.indexOf(char); require(digit >= 0)
            n = n.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit.toLong()))
        }
        val raw = if (n.signum() == 0) byteArrayOf() else n.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        return ByteArray(text.takeWhile { it == '1' }.length) + raw
    }
}
