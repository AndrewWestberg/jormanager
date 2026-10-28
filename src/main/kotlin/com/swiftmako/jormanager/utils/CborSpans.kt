package com.swiftmako.jormanager.utils

import java.math.BigInteger

/** A view of one original CBOR item. Children share the original input buffer. */
class CborSpan internal constructor(
    private val source: ByteArray,
    val start: Int,
    val end: Int,
    val major: Int,
    val argument: BigInteger?,
    val children: List<CborSpan>,
    val payloadStart: Int
) {
    fun bytes(): ByteArray = source.copyOfRange(start, end)
    fun sameEncoding(other: CborSpan): Boolean =
        java.util.Arrays.equals(source, start, end, other.source, other.start, other.end)

    fun sameEncoding(encoded: ByteArray): Boolean =
        java.util.Arrays.equals(source, start, end, encoded, 0, encoded.size)

    /** Read-only view of the exact encoded item, backed by the original input. */
    fun buffer(): java.nio.ByteBuffer =
        java.nio.ByteBuffer.wrap(source, start, end - start).slice().asReadOnlyBuffer()

    fun hash256(): ByteArray {
        val digest = org.bouncycastle.crypto.digests.Blake2bDigest(256)
        digest.update(source, start, end - start)
        return ByteArray(32).also { digest.doFinal(it, 0) }
    }
    fun payload(): ByteArray {
        require(major == 2 || major == 3) { "CBOR item at $start is not a string" }
        if (argument != null) return source.copyOfRange(payloadStart, end)
        val chunks = children.map { it.payload() }
        val result = ByteArray(chunks.sumOf { it.size })
        var at = 0
        for (chunk in chunks) { chunk.copyInto(result, at); at += chunk.size }
        return result
    }
    fun integer(): BigInteger = when (major) {
        0 -> argument!!
        1 -> argument!!.negate().subtract(BigInteger.ONE)
        else -> throw IllegalArgumentException("Expected integer at CBOR offset $start")
    }
    fun isNull(): Boolean = major == 7 && end - start == 1 && argument == BigInteger.valueOf(22)
    fun boolean(): Boolean = when {
        major == 7 && end - start == 1 && argument == BigInteger.valueOf(20) -> false
        major == 7 && end - start == 1 && argument == BigInteger.valueOf(21) -> true
        else -> throw IllegalArgumentException("Expected boolean at CBOR offset $start")
    }
    fun array(): List<CborSpan> {
        require(major == 4) { "Expected array at CBOR offset $start" }
        return children
    }
    fun entries(): List<Pair<CborSpan, CborSpan>> {
        require(major == 5) { "Expected map at CBOR offset $start" }
        return children.chunked(2).map { it[0] to it[1] }
    }
    fun tagged(tag: Long): CborSpan {
        require(major == 6 && argument == BigInteger.valueOf(tag)) { "Expected tag $tag at CBOR offset $start" }
        return children.single()
    }
}

/** More bytes are required to distinguish a complete item from malformed input. */
class CborIncompleteException(message: String) : IllegalArgumentException(message)

/** Bounded structural parser; it never normalizes or re-encodes identity-bearing bytes. */
object CborSpans {
    /** Parses one item at the beginning of a buffer, leaving following items untouched.
     * [CborIncompleteException] means append more bytes; other failures are malformed input.
     * The returned span's end is the number of consumed bytes.
     */
    fun parseFirst(bytes: ByteArray, maxDepth: Int = Int.MAX_VALUE, maxItems: Int = Int.MAX_VALUE): CborSpan {
        require(maxDepth > 0 && maxItems > 0)
        return Parser(bytes, maxDepth, minOf(maxItems, maxOf(1, bytes.size))).item()
    }

    fun parse(bytes: ByteArray, maxDepth: Int = Int.MAX_VALUE, maxItems: Int = Int.MAX_VALUE): CborSpan {
        require(maxDepth > 0 && maxItems > 0)
        val parser = Parser(bytes, maxDepth, minOf(maxItems, maxOf(1, bytes.size)))
        val result = parser.item()
        require(parser.position == bytes.size) { "Trailing CBOR data at offset ${parser.position}" }
        return result
    }

    private class Parser(val bytes: ByteArray, val maxDepth: Int, val maxItems: Int) {
        var position = 0
        var count = 0
        private fun octet(): Int {
            if (position >= bytes.size) throw CborIncompleteException("Truncated CBOR at offset $position")
            return bytes[position++].toInt() and 255
        }
        private fun length(value: BigInteger): Int {
            require(value.signum() >= 0 && value.bitLength() <= 31) { "CBOR length exceeds bounds at offset $position" }
            return value.toInt()
        }
        private class Frame(
            val start: Int, val major: Int, val argument: BigInteger?, val payloadStart: Int,
            var remaining: Long, val children: MutableList<CborSpan> = mutableListOf()
        )

        fun item(): CborSpan {
            val stack = ArrayDeque<Frame>()
            var completed: CborSpan? = null
            while (true) {
                if (completed != null) {
                    if (stack.isEmpty()) return completed
                    val parent = stack.last()
                    if (parent.major == 2 || parent.major == 3) {
                        require(completed.major == parent.major && completed.argument != null) {
                            "Invalid CBOR string chunk at offset ${completed.start}"
                        }
                    }
                    parent.children.add(completed)
                    if (parent.remaining >= 0) parent.remaining--
                    completed = null
                    continue
                }
                if (stack.isNotEmpty()) {
                    val parent = stack.last()
                    if (parent.remaining == 0L ||
                        (parent.remaining < 0 && position < bytes.size && (bytes[position].toInt() and 255) == 255)) {
                        if (parent.remaining < 0) position++
                        require(parent.major != 5 || parent.children.size % 2 == 0) { "Odd CBOR map at offset ${parent.start}" }
                        stack.removeLast()
                        completed = CborSpan(bytes, parent.start, position, parent.major, parent.argument, parent.children, parent.payloadStart)
                        continue
                    }
                }
                if (position >= bytes.size) throw CborIncompleteException("Truncated CBOR at offset $position")
                require(stack.size < maxDepth && ++count <= maxItems) { "CBOR nesting or item limit exceeded at offset $position" }
                val start = position
                val initial = octet()
                val major = initial ushr 5
                val ai = initial and 31
                require(ai !in 28..30 && ai != 31 || major in 2..5 && ai == 31) { "Invalid CBOR initial byte at offset $start" }
                val arg = when (ai) {
                    in 0..23 -> BigInteger.valueOf(ai.toLong())
                    24, 25, 26, 27 -> {
                        val size = 1 shl (ai - 24)
                        if (size > bytes.size - position) throw CborIncompleteException("Truncated CBOR argument at offset $start")
                        val value = BigInteger(1, bytes.copyOfRange(position, position + size))
                        position += size
                        value
                    }
                    31 -> null
                    else -> throw IllegalArgumentException("Invalid CBOR argument at offset $start")
                }
                val payloadStart = position
                if ((major == 2 || major == 3) && arg != null) {
                    val size = length(arg)
                    if (size > bytes.size - position) throw CborIncompleteException("Truncated CBOR string at offset $start")
                    position += size
                    completed = CborSpan(bytes, start, position, major, arg, emptyList(), payloadStart)
                } else if (major in 2..6) {
                    val remaining = when {
                        major == 6 -> 1L
                        arg == null -> -1L
                        else -> length(arg).toLong() * if (major == 5) 2 else 1
                    }
                    stack.addLast(Frame(start, major, arg, payloadStart, remaining))
                } else {
                    completed = CborSpan(bytes, start, position, major, arg, emptyList(), payloadStart)
                }
            }
        }
    }
}
