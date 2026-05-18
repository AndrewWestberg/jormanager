package com.swiftmako.jormanager.tracing.fixtures

import com.google.common.truth.Truth.assertThat
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborByteString
import com.google.iot.cbor.CborReader
import com.swiftmako.jormanager.tracing.NodeStateDataPointDecoder
import java.io.ByteArrayInputStream
import org.json.JSONObject
import org.junit.jupiter.api.Test

class TraceForwardFixturesTest {
    @Test
    fun msgDataPointsRequestBuildsExpectedCborShape() {
        val payload = TraceForwardFixtures.msgDataPointsRequest()
        val pinnedPayload = TraceForwardFixtures.pinnedNodeStateRequest()
        val request = payload.readSingleCborArray()
        val names = request.elementAt(1) as CborArray

        assertThat(payload.toHex()).isEqualTo(pinnedPayload.toHex())
        assertThat(request.size()).isEqualTo(2)
        assertThat(request.elementAt(0).toJsonString()).isEqualTo("1")
        assertThat(names.size()).isEqualTo(NodeStateDataPointDecoder.REQUESTED_NAMES.size)
        assertThat(List(names.size()) { index -> names.elementAt(index).toJsonString().trim('"') })
            .containsExactlyElementsIn(NodeStateDataPointDecoder.REQUESTED_NAMES)
            .inOrder()
        assertThat(List(names.size()) { index -> names.elementAt(index).toJsonString().trim('"') })
            .contains(NodeStateDataPointDecoder.KEY_NODE_STARTUP_INFO)
    }

    @Test
    fun msgTraceObjectsRequestBuildsExpectedCborShape() {
        val payload = TraceForwardFixtures.msgTraceObjectsRequest(blocking = true, count = 25)
        val request = payload.readSingleCborArray()

        assertThat(payload.toHex()).isEqualTo("8301f582001819")
        assertThat(request.size()).isEqualTo(3)
        assertThat(request.elementAt(0).toJsonString()).isEqualTo("1")
        assertThat(request.elementAt(1).toJsonString()).isEqualTo("true")
        assertThat((request.elementAt(2) as CborArray).elementAt(0).toJsonString()).isEqualTo("0")
        assertThat((request.elementAt(2) as CborArray).elementAt(1).toJsonString()).isEqualTo("25")
    }

    @Test
    fun msgTraceObjectsReplyEmptyBuildsExpectedCborShape() {
        val payload = TraceForwardFixtures.msgTraceObjectsReplyEmpty()
        val reply = payload.readSingleCborArray()

        assertThat(payload.toHex()).isEqualTo("820380")
        assertThat(reply.size()).isEqualTo(2)
        assertThat(reply.elementAt(0).toJsonString()).isEqualTo("3")
        assertThat((reply.elementAt(1) as CborArray).size()).isEqualTo(0)
    }

    @Test
    fun msgTraceObjectsReplyBuildsNonEmptyCborShape() {
        val payload = TraceForwardFixtures.msgTraceObjectsReply(TraceForwardFixtures.adoptedBlockTraceObject())
        val reply = payload.readSingleCborArray()
        val traceObjects = reply.elementAt(1) as CborArray

        assertThat(reply.size()).isEqualTo(2)
        assertThat(reply.elementAt(0).toJsonString()).isEqualTo("3")
        assertThat(traceObjects.size()).isEqualTo(1)
        assertThat(traceObjects.elementAt(0).toJsonString()).contains("\\\"toNamespace\\\"")
        assertThat(traceObjects.elementAt(0).toJsonString()).contains("Forge")
    }

    @Test
    fun msgDataPointsReplyBuildsPinnedNodeStateShape() {
        val payload = TraceForwardFixtures.msgDataPointsReply(*TraceForwardFixtures.fullNodeStateDataPoints().toTypedArray())
        val pinnedPayload = TraceForwardFixtures.pinnedNodeStateReply()
        val reply = payload.readSingleCborArray()
        val dataPoints = reply.elementAt(1) as CborArray
        val first = dataPoints.elementAt(0) as CborArray
        val maybeValue = first.elementAt(1) as CborArray

        assertThat(payload.toHex()).isEqualTo(pinnedPayload.toHex())
        assertThat(reply.size()).isEqualTo(2)
        assertThat(reply.elementAt(0).toJsonString()).isEqualTo("3")
        assertThat(dataPoints.size()).isEqualTo(TraceForwardFixtures.fullNodeStateDataPoints().size)
        assertThat(first.elementAt(0).toJsonString()).isEqualTo("\"connectionManager.outgoingConns\"")
        assertThat(maybeValue.size()).isEqualTo(1)
        assertThat((maybeValue.elementAt(0) as CborByteString).byteArrayValue()[0].decodeToString()).isEqualTo("12")
    }

    @Test
    fun msgDoneBuildsExpectedCborShape() {
        val payload = TraceForwardFixtures.msgDone()
        val done = payload.readSingleCborArray()

        assertThat(payload.toHex()).isEqualTo("8102")
        assertThat(done.size()).isEqualTo(1)
        assertThat(done.elementAt(0).toJsonString()).isEqualTo("2")
    }

    @Test
    fun adoptedBlockFixtureMatchesPinnedNamespaceAndKind() {
        val traceObject = TraceForwardFixtures.adoptedBlockTraceObject()
        val traceObjectJson = JSONObject(traceObject.traceObjectJson)

        assertThat(traceObject.namespace()).containsExactly("Forge", "AdoptedBlock").inOrder()
        assertThat(traceObjectJson.has("toMachine")).isTrue()
        assertThat(traceObject.toMachineJson()).contains("\"kind\":\"TraceAdoptedBlock\"")
        assertThat(traceObject.toMachineJson()).contains("\"slot\":7403221")
        assertThat(traceObject.toMachineJson()).contains("\"blockHash\":")
    }

    @Test
    fun negativeTraceObjectFixturesStayDeterministic() {
        val malformed = TraceForwardFixtures.malformedMachineJsonTraceObject()
        val unknownNamespace = TraceForwardFixtures.unknownNamespaceTraceObject()
        val missingBlockHash = TraceForwardFixtures.missingBlockHashTraceObject()
        val wrongKind = TraceForwardFixtures.wrongKindTraceObject()
        val nonNumericSlot = TraceForwardFixtures.nonNumericSlotTraceObject()
        val wrapperOnlyBlockHash = TraceForwardFixtures.wrapperOnlyBlockHashTraceObject()

        assertThat(malformed.namespace()).containsExactly("Forge", "AdoptedBlock").inOrder()
        assertThat(malformed.toMachineJson()).endsWith(",")
        assertThat(unknownNamespace.namespace()).containsExactly("ChainDB", "AddBlockEvent").inOrder()
        assertThat(unknownNamespace.toMachineJson()).contains("\"SomeOtherEvent\"")
        assertThat(missingBlockHash.toMachineJson()).doesNotContain("blockHash")
        assertThat(wrongKind.namespace()).containsExactly("Forge", "AdoptedBlock").inOrder()
        assertThat(wrongKind.toMachineJson()).contains("\"SomeOtherEvent\"")
        assertThat(nonNumericSlot.toMachineJson()).contains("\"slot\":\"oops\"")
        assertThat(wrapperOnlyBlockHash.traceObjectJson).contains("\"blockHash\": \"wrapper-value\"")
    }

    @Test
    fun negativeNodeStateFixturesStayDeterministic() {
        val missingReply = TraceForwardFixtures.missingNodeStateKeyReply().readSingleCborArray()
        val nothingReply = TraceForwardFixtures.nothingNodeStateValueReply().readSingleCborArray()
        val malformedReply = TraceForwardFixtures.malformedNodeStateScalarReply().readSingleCborArray()
        val overflowingReply = TraceForwardFixtures.overflowingNodeStateReply().readSingleCborArray()

        assertThat((missingReply.elementAt(1) as CborArray).size()).isEqualTo(7)
        assertThat((((nothingReply.elementAt(1) as CborArray).elementAt(5) as CborArray).elementAt(1) as CborArray).size()).isEqualTo(0)
        assertThat(
            (((((malformedReply.elementAt(1) as CborArray).elementAt(4) as CborArray).elementAt(1) as CborArray).elementAt(0) as CborByteString)
                .byteArrayValue()[0]
                .decodeToString())
        ).isEqualTo("\"oops\"")
        assertThat(
            (((((overflowingReply.elementAt(1) as CborArray).elementAt(0) as CborArray).elementAt(1) as CborArray).elementAt(0) as CborByteString)
                .byteArrayValue()[0]
                .decodeToString())
        ).isEqualTo("2147483648")
    }

    @Test
    fun pinnedNodeStateWireBytesStayAnchoredToLiteralHex() {
        assertThat(TraceForwardFixtures.pinnedNodeStateRequest().toHex()).isEqualTo(
            TraceForwardFixtures.msgDataPointsRequest(NodeStateDataPointDecoder.REQUESTED_NAMES).toHex()
        )
        assertThat(TraceForwardFixtures.pinnedNodeStateReply().toHex()).isEqualTo(
            TraceForwardFixtures.msgDataPointsReply(*TraceForwardFixtures.fullNodeStateDataPoints().toTypedArray()).toHex()
        )
    }

    private fun ByteArray.readSingleCborArray(): CborArray =
        ByteArrayInputStream(this).use { input ->
            CborReader.createFromInputStream(input).readDataItem() as CborArray
        }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }
}
