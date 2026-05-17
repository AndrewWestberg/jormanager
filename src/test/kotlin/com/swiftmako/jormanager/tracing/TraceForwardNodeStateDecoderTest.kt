package com.swiftmako.jormanager.tracing

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.tracing.fixtures.TraceForwardFixtures
import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Test

class TraceForwardNodeStateDecoderTest {
    private val decoder = TraceForwardNodeStateDecoder()

    @Test
    fun decodesAddedToCurrentChainStateFromTraceObjectsReply() {
        val reply =
            TraceForwardFixtures
                .msgTraceObjectsReply(TraceForwardFixtures.nodeStateTraceObject())
                .toTraceObjectsReply()

        val decoded = decoder.decode(reply)

        assertThat(decoded?.blockHeight).isEqualTo(7_403_221L)
        assertThat(decoded?.slot).isEqualTo(7_403_221L)
    }

    @Test
    fun mergesConnectionCountersAndChainStateAcrossTraceObjectsReply() {
        val reply =
            TraceForwardFixtures
                .msgTraceObjectsReply(
                    TraceForwardFixtures.connectionManagerCountersTraceObject(outbound = 2, inbound = 3),
                    TraceForwardFixtures.nodeStateTraceObject(),
                ).toTraceObjectsReply()

        val decoded = decoder.decode(reply)

        assertThat(decoded?.blockHeight).isEqualTo(7_403_221L)
        assertThat(decoded?.slot).isEqualTo(7_403_221L)
        assertThat(decoded?.peers).isEqualTo(2)
        assertThat(decoded?.incomingPeers).isEqualTo(3)
    }

    private fun ByteArray.toTraceObjectsReply(): TraceForwardMessage.TraceObjectsReply =
        ByteArrayInputStream(this).use { input ->
            val payload = com.google.iot.cbor.CborReader.createFromInputStream(input).readDataItem() as com.google.iot.cbor.CborArray
            TraceForwardMessage.TraceObjectsReply(payload.elementAt(1) as com.google.iot.cbor.CborArray)
        }
}
