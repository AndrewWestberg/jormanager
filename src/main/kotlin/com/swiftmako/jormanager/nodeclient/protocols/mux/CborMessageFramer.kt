package com.swiftmako.jormanager.nodeclient.protocols.mux

/** Tracks one CBOR item across arbitrary mux frame boundaries without re-encoding it. */
internal class CborMessageFramer {
    private val remaining = ArrayList<Long>().apply { add(1) }
    private var offset = 0
    private var stringBytes = 0L

    fun advance(bytes: ByteArray, available: Int): Int? {
        while (true) {
            if (stringBytes > 0) {
                val count = minOf(stringBytes, (available - offset).toLong()).toInt()
                offset += count
                stringBytes -= count
                if (stringBytes != 0L) return null
            }
            while (remaining.isNotEmpty() && remaining.last() == 0L) remaining.removeAt(remaining.lastIndex)
            if (remaining.isEmpty()) return offset
            if (offset >= available) return null
            val initial = bytes[offset].toInt() and 255
            val major = initial ushr 5
            val ai = initial and 31
            if (initial == 255) {
                require(remaining.last() == -1L) { "Unexpected CBOR break" }
                remaining.removeAt(remaining.lastIndex)
                offset++
                continue
            }
            require(ai < 28 || ai == 31 && major in 2..5) { "Invalid CBOR initial byte" }
            val extra = when (ai) { 24 -> 1; 25 -> 2; 26 -> 4; 27 -> 8; else -> 0 }
            if (available - offset < 1 + extra) return null
            var argument = if (ai < 24) ai.toLong() else 0L
            repeat(extra) { i ->
                val next = bytes[offset + 1 + i].toInt() and 255
                if (major in 2..5) {
                    require(argument <= (Long.MAX_VALUE - next) / 256) { "CBOR length exceeds supported bounds" }
                    argument = argument * 256 + next
                }
            }
            offset += 1 + extra
            val last = remaining.lastIndex
            if (remaining[last] >= 0) remaining[last]--
            when (major) {
                2, 3 -> if (ai == 31) remaining.add(-1) else stringBytes = argument
                4 -> remaining.add(if (ai == 31) -1 else argument)
                5 -> remaining.add(if (ai == 31) -1 else Math.multiplyExact(argument, 2L))
                6 -> remaining.add(1)
            }
        }
    }
}
