package com.swiftmako.jormanager.tracing.fixtures

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborSimple
import com.google.iot.cbor.CborWriter
import java.nio.ByteBuffer
import org.json.JSONArray
import org.json.JSONObject

object TraceForwardFixtures {
    fun msgTraceObjectsRequest(
        blocking: Boolean,
        count: Int,
    ): ByteArray =
        cborBytes(
            CborArray.create().apply {
                add(CborInteger.create(MSG_TRACE_OBJECTS_REQUEST_ID))
                add(if (blocking) CborSimple.TRUE else CborSimple.FALSE)
                add(
                    CborArray.create().apply {
                        add(CborInteger.create(BLOCKING_REQUEST_ID))
                        add(CborInteger.create(count.toLong()))
                    }
                )
            }
        )

    fun msgTraceObjectsReplyEmpty(): ByteArray =
        cborBytes(
            CborArray.create().apply {
                add(CborInteger.create(MSG_TRACE_OBJECTS_REPLY_ID))
                add(CborArray.create())
            }
        )

    fun msgDone(): ByteArray =
        cborBytes(
            CborArray.create().apply {
                add(CborInteger.create(MSG_DONE_ID))
            }
        )

    fun adoptedBlockTraceObject(
        slot: Long = 7_403_221L,
        blockHash: String = "6dc4f778bf6ff15f8f3c7c3d98e6c6c8321df6e3e97e2cb7f1f1d6ca0b5c4abc",
    ): ForwardedTraceObjectFixture =
        ForwardedTraceObjectFixture(
            traceObjectJson =
                """
                {
                  "toNamespace": ["Forge", "AdoptedBlock"],
                  "toMachine": {"kind":"TraceAdoptedBlock","slot":$slot,"blockHash":"$blockHash","blockSize":1234},
                  "toSeverity": "Info",
                  "toDetails": "DNormal",
                  "toHostname": "core-node-1",
                  "toThreadId": "trace-forward-1",
                  "toTimestamp": "2026-05-12T00:00:00Z"
                }
                """.trimIndent(),
        )

    fun malformedMachineJsonTraceObject(): ForwardedTraceObjectFixture =
        ForwardedTraceObjectFixture(
            traceObjectJson =
                """
                {
                  "toNamespace": ["Forge", "AdoptedBlock"],
                  "toMachine": "{\"kind\":\"TraceAdoptedBlock\",\"slot\":42,",
                  "toSeverity": "Info",
                  "toDetails": "DNormal",
                  "toHostname": "core-node-1",
                  "toThreadId": "trace-forward-1",
                  "toTimestamp": "2026-05-12T00:00:00Z"
                }
                """.trimIndent(),
        )

    fun unknownNamespaceTraceObject(): ForwardedTraceObjectFixture =
        ForwardedTraceObjectFixture(
            traceObjectJson =
                """
                {
                  "toNamespace": ["ChainDB", "AddBlockEvent"],
                  "toMachine": {"kind":"SomeOtherEvent","slot":42,"blockHash":"abc123"},
                  "toSeverity": "Info",
                  "toDetails": "DNormal",
                  "toHostname": "core-node-1",
                  "toThreadId": "trace-forward-1",
                  "toTimestamp": "2026-05-12T00:00:00Z"
                }
                """.trimIndent(),
        )

    fun missingBlockHashTraceObject(): ForwardedTraceObjectFixture =
        ForwardedTraceObjectFixture(
            traceObjectJson =
                """
                {
                  "toNamespace": ["Forge", "AdoptedBlock"],
                  "toMachine": {"kind":"TraceAdoptedBlock","slot":42},
                  "toSeverity": "Info",
                  "toDetails": "DNormal",
                  "toHostname": "core-node-1",
                  "toThreadId": "trace-forward-1",
                  "toTimestamp": "2026-05-12T00:00:00Z"
                }
                """.trimIndent(),
        )

    private fun cborBytes(item: com.google.iot.cbor.CborObject): ByteArray {
        val buffer = ByteBuffer.allocate(1024)
        CborWriter.createFromByteBuffer(buffer).writeDataItem(item)
        buffer.flip()
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    const val MSG_TRACE_OBJECTS_REQUEST_ID = 1L
    const val MSG_DONE_ID = 2L
    const val MSG_TRACE_OBJECTS_REPLY_ID = 3L

    private const val BLOCKING_REQUEST_ID = 0L
}

data class ForwardedTraceObjectFixture(
    val traceObjectJson: String,
) {
    fun toMachineJson(): String =
        readJsonObject().get("toMachine").let { machine ->
            when (machine) {
                is JSONObject -> machine.toString()
                is JSONArray -> machine.toString()
                is String -> machine
                else -> error("Unsupported toMachine type: ${machine::class.java.name}")
            }
        }

    fun namespace(): List<String> =
        readJsonObject()
            .getJSONArray("toNamespace")
            .let { namespace -> (0 until namespace.length()).map(namespace::getString) }

    private fun readJsonObject(): JSONObject = JSONObject(traceObjectJson)
}
