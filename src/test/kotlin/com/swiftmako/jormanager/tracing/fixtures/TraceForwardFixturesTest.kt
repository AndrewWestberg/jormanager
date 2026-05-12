package com.swiftmako.jormanager.tracing.fixtures

import com.google.common.truth.Truth.assertThat
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborReader
import java.io.ByteArrayInputStream
import java.net.Socket
import org.json.JSONObject
import org.junit.jupiter.api.Test

class TraceForwardFixturesTest {
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

    private fun ByteArray.readSingleCborArray(): CborArray =
        ByteArrayInputStream(this).use { input ->
            CborReader.createFromInputStream(input).readDataItem() as CborArray
        }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }

    private fun String.hexToByteArray(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
