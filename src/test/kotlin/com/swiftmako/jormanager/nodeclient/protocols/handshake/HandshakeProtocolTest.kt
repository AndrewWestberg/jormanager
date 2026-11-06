package com.swiftmako.jormanager.nodeclient.protocols.handshake

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HandshakeProtocolTest {
    private fun bytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun exchange(hex: String): HandshakeProtocol = runBlocking {
        HandshakeProtocol(42).also {
            it.sendData()
            it.receiveData(ByteBuffer.wrap(bytes(hex)))
        }
    }

    @Test
    fun proposesAscendingVersionsWithVersionSpecificData() {
        val buffer = ByteBuffer.allocate(256)
        MsgProposeVersions(42).writeToBuffer(buffer)
        buffer.flip()
        val encoded = ByteArray(buffer.remaining()).also { buffer.get(it) }
        assertThat(encoded).isEqualTo(bytes(
            "8200a4" +
                "0d84182af500f4" +
                "0e84182af500f4" +
                "0f84182af500f4" +
                "1085182af500f4f4"
        ))
    }

    @Test
    fun acceptsEachOfferedVersion() {
        for (version in 13..16) {
            val data = if (version == 16) "85182af500f4f4" else "84182af500f4"
            val reply = "8301" + version.toString(16).padStart(2, '0') + data
            assertThat(exchange(reply).msgAcceptVersion.versionNumber).isEqualTo(version.toLong())
        }
    }

    @Test
    fun rejectsInvalidNegotiationsAndNonAcceptReplies() {
        val invalid = listOf(
            "83010c84182af500f4", // unoffered version
            "83010f84182bf500f4", // wrong network
            "83010f84182af400f4", // duplex instead of initiator-only
            "8301c00f84182af500f4", // tagged version is not an integer term
            "83010f84c0182af500f4", // tagged network magic
            "83010f84182af501f4", // peer sharing enabled
            "83010f84182af500f5", // query enabled
            "83010f85182af500f4f4", // v15 cannot have Peras field
            "83011084182af500f4", // v16 requires Peras field
            "83011085182af500f4f5", // Peras enabled
            "83011085182a0100f4f4", // diffusion mode is not a boolean
            "83010f841b000000010000002af500f4", // out-of-range network magic
            "84010f84182af500f400", // extra acceptance field
            "82028200810f", // version mismatch refusal
            "8203a0", // query reply to a non-query handshake
            "83010f84182af500f400" // trailing CBOR item
        )
        for (reply in invalid) {
            assertThrows(IOException::class.java, { exchange(reply) }, reply)
        }
    }
}
