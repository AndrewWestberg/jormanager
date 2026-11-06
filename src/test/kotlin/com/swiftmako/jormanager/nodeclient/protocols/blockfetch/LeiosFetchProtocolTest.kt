package com.swiftmako.jormanager.nodeclient.protocols.blockfetch

import com.google.common.truth.Truth.assertThat
import com.google.iot.cbor.CborByteString
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborMap
import com.google.iot.cbor.CborObject
import com.swiftmako.jormanager.nodeclient.protocols.mux.muxByteBufferPool
import com.swiftmako.jormanager.utils.Blake2b
import com.swiftmako.jormanager.utils.CborSpan
import com.swiftmako.jormanager.utils.CborSpans
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class LeiosFetchProtocolTest {
    private fun body(count: Int, txSize: Long): ByteArray {
        val refs = linkedMapOf<CborObject, CborObject>()
        repeat(count) { offset ->
            val hash = ByteBuffer.allocate(32).apply { position(28); putInt(offset) }.array()
            refs[CborByteString.create(hash)] = CborInteger.create(txSize)
        }
        return CborMap.create(refs).toCborByteArray()
    }

    private suspend fun request(protocol: LeiosFetchProtocol): List<CborSpan> {
        val buffer = protocol.sendData()
        return try {
            CborSpans.parse(ByteArray(buffer.remaining()).also { buffer.get(it) }).array()
        } finally {
            muxByteBufferPool.recycle(buffer)
        }
    }

    private fun deliverBody(protocol: LeiosFetchProtocol, body: ByteArray) {
        protocol.receiveData(ByteBuffer.wrap(byteArrayOf(0x82.toByte(), 2) + body))
    }

    // Upstream popLeftmostOffset consumes leading bits, then adds the 64-entry group base.
    private fun requestedOffsets(message: List<CborSpan>): List<Int> {
        assertThat(message[0].integer().toInt()).isEqualTo(3)
        return message[2].entries().flatMap { (group, bitmap) ->
            val offsets = mutableListOf<Int>()
            var word = bitmap.integer()
            while (word.signum() != 0) {
                val leadingZeros = 64 - word.bitLength()
                offsets.add(group.integer().toInt() * 64 + leadingZeros)
                word = word.clearBit(63 - leadingZeros)
            }
            offsets
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 63, 64, 65, 354])
    fun partialAndFullGroupsRequestExactlyTheAdvertisedTransactions(count: Int) = runBlocking {
        withTimeout(5000) {
            val protocol = LeiosFetchProtocol({ it == 1L })
            val body = body(count, 1)
            val acquisition = async { protocol.acquire(185565, Blake2b.hash256(body), body.size.toLong(), 1) }
            try {
                request(protocol)
                deliverBody(protocol, body)
                assertThat(requestedOffsets(request(protocol))).containsExactlyElementsIn(0 until count).inOrder()
            } finally {
                acquisition.cancelAndJoin()
                protocol.shutdown()
            }
        }
    }

    @Test
    fun sizeLimitedContinuationPreservesOffsetsAcrossGroupBoundary() = runBlocking {
        withTimeout(5000) {
            val protocol = LeiosFetchProtocol({ it == 1L })
            val body = body(65, 8000)
            val acquisition = async { protocol.acquire(185565, Blake2b.hash256(body), body.size.toLong(), 1) }
            try {
                request(protocol)
                deliverBody(protocol, body)
                assertThat(requestedOffsets(request(protocol))).containsExactlyElementsIn(0 until 62).inOrder()
                // Only request selection is under test; cancel before final closure validation.
                val response = ByteArrayOutputStream().apply {
                    write(byteArrayOf(0x82.toByte(), 4, 0x98.toByte(), 62))
                    repeat(62) { write(byteArrayOf(0xd8.toByte(), 24, 0x41, 0)) }
                }
                protocol.receiveData(ByteBuffer.wrap(response.toByteArray()))
                assertThat(requestedOffsets(request(protocol))).containsExactly(62, 63, 64).inOrder()
            } finally {
                acquisition.cancelAndJoin()
                protocol.shutdown()
            }
        }
    }
}
