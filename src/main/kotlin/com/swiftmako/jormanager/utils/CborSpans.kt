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
    fun isNull(): Boolean = major == 7 && argument == BigInteger.valueOf(22)
    fun boolean(): Boolean = when {
        major == 7 && argument == BigInteger.valueOf(20) -> false
        major == 7 && argument == BigInteger.valueOf(21) -> true
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

/** Bounded structural parser; it never normalizes or re-encodes identity-bearing bytes. */
object CborSpans {
    fun parse(bytes: ByteArray, maxDepth: Int = 128, maxItems: Int = 1_000_000): CborSpan {
        require(maxDepth > 0 && maxItems > 0)
        val parser = Parser(bytes, maxDepth, maxItems)
        val result = parser.item(0)
        require(parser.position == bytes.size) { "Trailing CBOR data at offset ${parser.position}" }
        return result
    }

    private class Parser(val bytes: ByteArray, val maxDepth: Int, val maxItems: Int) {
        var position = 0
        var count = 0
        private fun octet(): Int {
            require(position < bytes.size) { "Truncated CBOR at offset $position" }
            return bytes[position++].toInt() and 255
        }
        private fun length(value: BigInteger): Int {
            require(value.signum() >= 0 && value.bitLength() <= 31) { "CBOR length exceeds bounds at offset $position" }
            return value.toInt()
        }
        fun item(depth: Int): CborSpan {
            require(depth < maxDepth && ++count <= maxItems) { "CBOR nesting or item limit exceeded at offset $position" }
            val start = position
            val initial = octet()
            val major = initial ushr 5
            val ai = initial and 31
            require(ai !in 28..30 && ai != 31 || major in 2..5 && ai == 31) { "Invalid CBOR initial byte at offset $start" }
            val arg = when (ai) {
                in 0..23 -> BigInteger.valueOf(ai.toLong())
                24, 25, 26, 27 -> {
                    val size = 1 shl (ai - 24)
                    require(size <= bytes.size - position) { "Truncated CBOR argument at offset $start" }
                    val value = BigInteger(1, bytes.copyOfRange(position, position + size))
                    position += size
                    value
                }
                31 -> null
                else -> throw IllegalArgumentException("Invalid CBOR argument at offset $start")
            }
            val payloadStart = position
            val children = mutableListOf<CborSpan>()
            when (major) {
                2, 3 -> if (arg != null) {
                    val size = length(arg)
                    require(size <= bytes.size - position) { "Truncated CBOR string at offset $start" }
                    position += size
                } else {
                    while (true) {
                        require(position < bytes.size) { "Unterminated CBOR string at offset $start" }
                        if ((bytes[position].toInt() and 255) == 255) { position++; break }
                        val chunk = item(depth + 1)
                        require(chunk.major == major && chunk.argument != null) { "Invalid CBOR string chunk at offset ${chunk.start}" }
                        children.add(chunk)
                    }
                }
                4, 5 -> {
                    if (arg == null) {
                        while (true) {
                            require(position < bytes.size) { "Unterminated CBOR container at offset $start" }
                            if ((bytes[position].toInt() and 255) == 255) { position++; break }
                            children.add(item(depth + 1))
                        }
                        require(major != 5 || children.size % 2 == 0) { "Odd CBOR map at offset $start" }
                    } else {
                        val n = length(arg)
                        require(n <= (maxItems - count) / if (major == 5) 2 else 1) { "CBOR container exceeds item limit at offset $start" }
                        repeat(n * if (major == 5) 2 else 1) { children.add(item(depth + 1)) }
                    }
                }
                6 -> children.add(item(depth + 1))
            }
            return CborSpan(bytes, start, position, major, arg, children, payloadStart)
        }
    }
}
