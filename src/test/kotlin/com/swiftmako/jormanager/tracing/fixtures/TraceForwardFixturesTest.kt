package com.swiftmako.jormanager.tracing.fixtures

import com.google.common.truth.Truth.assertThat
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborByteString
import com.google.iot.cbor.CborReader
import com.swiftmako.jormanager.tracing.NodeStateDataPointDecoder
import java.io.ByteArrayInputStream
import java.net.Socket
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
        assertThat(dataPoints.size()).isEqualTo(NodeStateDataPointDecoder.REQUESTED_NAMES.size)
        assertThat(first.elementAt(0).toJsonString()).isEqualTo("\"cardano.node.metrics.connectionManager.outgoingConns\"")
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
            "820188783463617264616e6f2e6e6f64652e6d6574726963732e636f6e6e656374696f6e4d616e616765722e6f7574676f696e67436f6e6e73783463617264616e6f2e6e6f64652e6d6574726963732e636f6e6e656374696f6e4d616e616765722e696e636f6d696e67436f6e6e73781d63617264616e6f2e6e6f64652e6d6574726963732e626c6f636b4e756d782863617264616e6f2e6e6f64652e6d6574726963732e72656d61696e696e674b4553506572696f6473781a63617264616e6f2e6e6f64652e6d6574726963732e65706f6368781c63617264616e6f2e6e6f64652e6d6574726963732e736c6f744e756d782063617264616e6f2e6e6f64652e6d6574726963732e736c6f74496e45706f6368782463617264616e6f2e6e6f64652e6d6574726963732e74787350726f6365737365644e756d"
        )
        assertThat(TraceForwardFixtures.pinnedNodeStateReply().toHex()).isEqualTo(
            "82038882783463617264616e6f2e6e6f64652e6d6574726963732e636f6e6e656374696f6e4d616e616765722e6f7574676f696e67436f6e6e738142313282783463617264616e6f2e6e6f64652e6d6574726963732e636f6e6e656374696f6e4d616e616765722e696e636f6d696e67436f6e6e7381413782781d63617264616e6f2e6e6f64652e6d6574726963732e626c6f636b4e756d81473734303332323182782863617264616e6f2e6e6f64652e6d6574726963732e72656d61696e696e674b4553506572696f64738142333682781a63617264616e6f2e6e6f64652e6d6574726963732e65706f6368814334393082781c63617264616e6f2e6e6f64652e6d6574726963732e736c6f744e756d81473734303332323182782063617264616e6f2e6e6f64652e6d6574726963732e736c6f74496e45706f6368814333323182782463617264616e6f2e6e6f64652e6d6574726963732e74787350726f6365737365644e756d8146313233343536"
        )
    }

    @Test
    fun scriptedTraceForwardServerAcceptsExpectedRequestAndReturnsEmptyReply() {
        ScriptedTraceForwardServer.start(
            TraceForwardSessionScript(
                expectedClientMessages = listOf("8301f582000a".hexToByteArray()),
                serverResponses = listOf(TraceForwardFixtures.msgTraceObjectsReplyEmpty()),
            )
        ).use { server ->
            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 2_000
                socket.getOutputStream().write(TraceForwardFixtures.msgTraceObjectsRequest(blocking = true, count = 10))
                socket.getOutputStream().flush()

                val actualReply = socket.getInputStream().readNBytes(TraceForwardFixtures.msgTraceObjectsReplyEmpty().size)

                assertThat(actualReply.toHex()).isEqualTo(TraceForwardFixtures.msgTraceObjectsReplyEmpty().toHex())
            }

            server.awaitCompletion()
        }
    }

    @Test
    fun scriptedTraceForwardServerSupportsRepeatedSessionsForReconnectTests() {
        ScriptedTraceForwardServer.start(
            TraceForwardSessionScript(
                expectedClientMessages = listOf("8301f5820001".hexToByteArray()),
                serverResponses = listOf(TraceForwardFixtures.msgTraceObjectsReplyEmpty()),
            ),
            TraceForwardSessionScript(
                expectedClientMessages = listOf("8301f4820002".hexToByteArray()),
                serverResponses = listOf("820380".hexToByteArray(), "8102".hexToByteArray()),
            ),
        ).use { server ->
            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 2_000
                socket.getOutputStream().write(TraceForwardFixtures.msgTraceObjectsRequest(blocking = true, count = 1))
                socket.getOutputStream().flush()
                socket.getInputStream().readNBytes(TraceForwardFixtures.msgTraceObjectsReplyEmpty().size)
            }

            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 2_000
                socket.getOutputStream().write(TraceForwardFixtures.msgTraceObjectsRequest(blocking = false, count = 2))
                socket.getOutputStream().flush()

                val reply = socket.getInputStream().readNBytes(TraceForwardFixtures.msgTraceObjectsReplyEmpty().size)
                val done = socket.getInputStream().readNBytes(TraceForwardFixtures.msgDone().size)

                assertThat(reply.toHex()).isEqualTo("820380")
                assertThat(done.toHex()).isEqualTo("8102")
            }

            server.awaitCompletion()
        }
    }

    @Test
    fun scriptedTraceForwardServerAcceptsPinnedDataPointRequestAndReturnsReply() {
        val expectedRequest = TraceForwardFixtures.pinnedNodeStateRequest()
        val reply = TraceForwardFixtures.pinnedNodeStateReply()

        ScriptedTraceForwardServer.start(
            TraceForwardSessionScript(
                expectedClientMessages = listOf(expectedRequest),
                serverResponses = listOf(reply),
            )
        ).use { server ->
            Socket("127.0.0.1", server.port).use { socket ->
                socket.soTimeout = 2_000
                socket.getOutputStream().write(expectedRequest)
                socket.getOutputStream().flush()

                val actualReply = socket.getInputStream().readNBytes(reply.size)

                assertThat(actualReply.toHex()).isEqualTo(reply.toHex())
            }

            server.awaitCompletion()
        }
    }

    private fun ByteArray.readSingleCborArray(): CborArray =
        ByteArrayInputStream(this).use { input ->
            CborReader.createFromInputStream(input).readDataItem() as CborArray
        }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }

    private fun String.hexToByteArray(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
