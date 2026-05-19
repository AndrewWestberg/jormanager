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
    fun decodesRealClockworkAddedToCurrentChainStateFromTraceObjectsReply() {
        val reply =
            TraceForwardFixtures
                .msgTraceObjectsReply(
                    TraceForwardFixtures.nodeStateTraceObject(
                        blockHeight = 3_751_042L,
                        slot = 140_061_626L,
                        host = "clockwork",
                        at = "2026-05-19T00:45:00.003969274Z",
                    )
                )
                .toTraceObjectsReply()

        val decoded = decoder.decode(reply)

        assertThat(decoded?.blockHeight).isEqualTo(3_751_042L)
        assertThat(decoded?.slot).isEqualTo(140_061_626L)
    }

    @Test
    fun mergesPeerConnectionInferenceAndChainStateAcrossTraceObjectsReply() {
        val reply =
            TraceForwardFixtures
                .msgTraceObjectsReply(
                    TraceForwardFixtures.clockworkPeerTraceObject(namespace = "ChainSync.Client.DownloadedHeader", kind = "DownloadedHeader", connectionId = "127.0.0.1:7001 127.0.0.1:7000"),
                    TraceForwardFixtures.clockworkPeerTraceObject(namespace = "BlockFetch.Client.SendFetchRequest", kind = "SendFetchRequest", connectionId = "127.0.0.1:7002 127.0.0.1:7000"),
                    TraceForwardFixtures.nodeStateTraceObject(),
                ).toTraceObjectsReply()

        val decoded = decoder.decode(reply)

        assertThat(decoded?.blockHeight).isEqualTo(7_403_221L)
        assertThat(decoded?.slot).isEqualTo(7_403_221L)
        assertThat(decoded?.peers).isEqualTo(2)
        assertThat(decoded?.incomingPeers).isNull()
    }

    @Test
    fun ignoresMalformedTraceObjectWhenMergingState() {
        val reply =
            TraceForwardFixtures
                .msgTraceObjectsReply(
                    TraceForwardFixtures.malformedMachineJsonTraceObject(),
                    TraceForwardFixtures.clockworkPeerTraceObject(connectionId = "127.0.0.1:7003 127.0.0.1:7000"),
                    TraceForwardFixtures.nodeStateTraceObject(),
                ).toTraceObjectsReply()

        val decoded = decoder.decode(reply)

        assertThat(decoded?.blockHeight).isEqualTo(7_403_221L)
        assertThat(decoded?.slot).isEqualTo(7_403_221L)
        assertThat(decoded?.peers).isEqualTo(1)
        assertThat(decoded?.incomingPeers).isNull()
    }

    @Test
    fun infersSinglePeerConnectionFromClockworkTraceObjectShape() {
        val reply =
            TraceForwardFixtures
                .msgTraceObjectsReply(TraceForwardFixtures.clockworkPeerTraceObject())
                .toTraceObjectsReply()

        val decoded = decoder.decode(reply)

        assertThat(decoded?.peers).isEqualTo(1)
    }

    @Test
    fun deduplicatesRepeatedClockworkPeerConnectionIdsAcrossTraceObjectsReply() {
        val reply =
            TraceForwardFixtures
                .msgTraceObjectsReply(
                    TraceForwardFixtures.clockworkPeerTraceObject(namespace = "ChainSync.Client.DownloadedHeader", kind = "DownloadedHeader"),
                    TraceForwardFixtures.clockworkPeerTraceObject(namespace = "BlockFetch.Client.SendFetchRequest", kind = "SendFetchRequest"),
                    TraceForwardFixtures.clockworkPeerTraceObject(namespace = "BlockFetch.Client.CompletedBlockFetch", kind = "CompletedBlockFetch"),
                ).toTraceObjectsReply()

        val decoded = decoder.decode(reply)

        assertThat(decoded?.peers).isEqualTo(1)
    }

    private fun ByteArray.toTraceObjectsReply(): TraceForwardMessage.TraceObjectsReply =
        ByteArrayInputStream(this).use { input ->
            val payload = com.google.iot.cbor.CborReader.createFromInputStream(input).readDataItem() as com.google.iot.cbor.CborArray
            TraceForwardMessage.TraceObjectsReply(payload.elementAt(1) as com.google.iot.cbor.CborArray)
        }
}
