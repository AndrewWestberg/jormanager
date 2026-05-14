package com.swiftmako.jormanager.tracing.fixtures

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborByteString
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborSimple
import com.google.iot.cbor.CborTextString
import com.google.iot.cbor.CborWriter
import com.swiftmako.jormanager.tracing.NodeStateDataPointDecoder
import java.nio.ByteBuffer
import org.json.JSONArray
import org.json.JSONObject

object TraceForwardFixtures {
    fun pinnedNodeStateRequest(): ByteArray = PINNED_NODE_STATE_REQUEST_HEX.hexToByteArray()

    fun pinnedNodeStateReply(): ByteArray = PINNED_NODE_STATE_REPLY_HEX.hexToByteArray()

    fun chunkedNodeStateReply(): ByteArray = CHUNKED_NODE_STATE_REPLY_HEX.hexToByteArray()

    fun msgDataPointsRequest(names: List<String> = NodeStateDataPointDecoder.REQUESTED_NAMES): ByteArray =
        cborBytes(
            CborArray.create().apply {
                add(CborInteger.create(MSG_DATA_POINTS_REQUEST_ID))
                add(
                    CborArray.create().apply {
                        names.forEach { add(CborTextString.create(it)) }
                    }
                )
            }
        )

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

    fun msgTraceObjectsReply(vararg traceObjects: ForwardedTraceObjectFixture): ByteArray =
        cborBytes(
            CborArray.create().apply {
                add(CborInteger.create(MSG_TRACE_OBJECTS_REPLY_ID))
                add(
                    CborArray.create().apply {
                        traceObjects.forEach { traceObject ->
                            add(CborTextString.create(traceObject.traceObjectJson))
                        }
                    }
                )
            }
        )

    fun msgDataPointsReply(vararg dataPoints: DataPointFixture): ByteArray =
        cborBytes(
            CborArray.create().apply {
                add(CborInteger.create(MSG_DATA_POINTS_REPLY_ID))
                add(
                    CborArray.create().apply {
                        dataPoints.forEach { dataPoint ->
                            add(
                                CborArray.create().apply {
                                    add(CborTextString.create(dataPoint.name))
                                    add(dataPoint.maybeValue())
                                }
                            )
                        }
                    }
                )
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

    fun wrongKindTraceObject(): ForwardedTraceObjectFixture =
        ForwardedTraceObjectFixture(
            traceObjectJson =
                """
                {
                  "toNamespace": ["Forge", "AdoptedBlock"],
                  "toMachine": {"kind":"SomeOtherEvent","slot":42,"blockHash":"abc123"},
                  "toSeverity": "Info",
                  "toDetails": "DNormal",
                  "toHostname": "core-node-1",
                  "toThreadId": "trace-forward-1",
                  "toTimestamp": "2026-05-12T00:00:00Z"
                }
                """.trimIndent(),
        )

    fun nonNumericSlotTraceObject(): ForwardedTraceObjectFixture =
        ForwardedTraceObjectFixture(
            traceObjectJson =
                """
                {
                  "toNamespace": ["Forge", "AdoptedBlock"],
                  "toMachine": {"kind":"TraceAdoptedBlock","slot":"oops","blockHash":"abc123"},
                  "toSeverity": "Info",
                  "toDetails": "DNormal",
                  "toHostname": "core-node-1",
                  "toThreadId": "trace-forward-1",
                  "toTimestamp": "2026-05-12T00:00:00Z"
                }
                """.trimIndent(),
        )

    fun wrapperOnlyBlockHashTraceObject(): ForwardedTraceObjectFixture =
        ForwardedTraceObjectFixture(
            traceObjectJson =
                """
                {
                  "toNamespace": ["Forge", "AdoptedBlock"],
                  "blockHash": "wrapper-value",
                  "toMachine": {"kind":"TraceAdoptedBlock","slot":42},
                  "toSeverity": "Info",
                  "toDetails": "DNormal",
                  "toHostname": "core-node-1",
                  "toThreadId": "trace-forward-1",
                  "toTimestamp": "2026-05-12T00:00:00Z"
                }
                """.trimIndent(),
        )

    fun fullNodeStateDataPoints(): List<DataPointFixture> =
        listOf(
            dataPoint(NodeStateDataPointDecoder.KEY_OUTGOING_CONNS, "12"),
            dataPoint(NodeStateDataPointDecoder.KEY_INCOMING_CONNS, "7"),
            dataPoint(NodeStateDataPointDecoder.KEY_BLOCK_NUM, "7403221"),
            dataPoint(NodeStateDataPointDecoder.KEY_REMAINING_KES_PERIODS, "36"),
            dataPoint(NodeStateDataPointDecoder.KEY_EPOCH, "490"),
            dataPoint(NodeStateDataPointDecoder.KEY_SLOT_NUM, "7403221"),
            dataPoint(NodeStateDataPointDecoder.KEY_SLOT_IN_EPOCH, "321"),
            dataPoint(NodeStateDataPointDecoder.KEY_TXS_PROCESSED_NUM, "123456"),
        )

    fun missingNodeStateKeyReply(): ByteArray =
        MISSING_NODE_STATE_KEY_REPLY_HEX.hexToByteArray()

    fun nothingNodeStateValueReply(): ByteArray =
        NOTHING_NODE_STATE_VALUE_REPLY_HEX.hexToByteArray()

    fun malformedNodeStateScalarReply(): ByteArray =
        MALFORMED_NODE_STATE_SCALAR_REPLY_HEX.hexToByteArray()

    fun overflowingNodeStateReply(): ByteArray =
        OVERFLOWING_NODE_STATE_REPLY_HEX.hexToByteArray()

    fun dataPoint(
        name: String,
        rawJsonValue: String?,
    ): DataPointFixture = DataPointFixture(name = name, rawJsonValue = rawJsonValue)

    private fun cborBytes(item: com.google.iot.cbor.CborObject): ByteArray {
        val buffer = ByteBuffer.allocate(1024)
        CborWriter.createFromByteBuffer(buffer).writeDataItem(item)
        buffer.flip()
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    const val MSG_TRACE_OBJECTS_REQUEST_ID = 1L
    const val MSG_DATA_POINTS_REQUEST_ID = 1L
    const val MSG_DONE_ID = 2L
    const val MSG_TRACE_OBJECTS_REPLY_ID = 3L
    const val MSG_DATA_POINTS_REPLY_ID = 3L

    private const val BLOCKING_REQUEST_ID = 0L

    private const val PINNED_NODE_STATE_REQUEST_HEX =
        "820188783463617264616e6f2e6e6f64652e6d6574726963732e636f6e6e656374696f6e4d616e616765722e6f7574676f696e67436f6e6e73783463617264616e6f2e6e6f64652e6d6574726963732e636f6e6e656374696f6e4d616e616765722e696e636f6d696e67436f6e6e73781d63617264616e6f2e6e6f64652e6d6574726963732e626c6f636b4e756d782863617264616e6f2e6e6f64652e6d6574726963732e72656d61696e696e674b4553506572696f6473781a63617264616e6f2e6e6f64652e6d6574726963732e65706f6368781c63617264616e6f2e6e6f64652e6d6574726963732e736c6f744e756d782063617264616e6f2e6e6f64652e6d6574726963732e736c6f74496e45706f6368782463617264616e6f2e6e6f64652e6d6574726963732e74787350726f6365737365644e756d"
    private const val PINNED_NODE_STATE_REPLY_HEX =
        "82038882783463617264616e6f2e6e6f64652e6d6574726963732e636f6e6e656374696f6e4d616e616765722e6f7574676f696e67436f6e6e738142313282783463617264616e6f2e6e6f64652e6d6574726963732e636f6e6e656374696f6e4d616e616765722e696e636f6d696e67436f6e6e7381413782781d63617264616e6f2e6e6f64652e6d6574726963732e626c6f636b4e756d81473734303332323182782863617264616e6f2e6e6f64652e6d6574726963732e72656d61696e696e674b4553506572696f64738142333682781a63617264616e6f2e6e6f64652e6d6574726963732e65706f6368814334393082781c63617264616e6f2e6e6f64652e6d6574726963732e736c6f744e756d81473734303332323182782063617264616e6f2e6e6f64652e6d6574726963732e736c6f74496e45706f6368814333323182782463617264616e6f2e6e6f64652e6d6574726963732e74787350726f6365737365644e756d8146313233343536"
    private const val MISSING_NODE_STATE_KEY_REPLY_HEX =
        "82038782783463617264616e6f2e6e6f64652e6d6574726963732e636f6e6e656374696f6e4d616e616765722e6f7574676f696e67436f6e6e738142313282783463617264616e6f2e6e6f64652e6d6574726963732e636f6e6e656374696f6e4d616e616765722e696e636f6d696e67436f6e6e7381413782781d63617264616e6f2e6e6f64652e6d6574726963732e626c6f636b4e756d81473734303332323182782863617264616e6f2e6e6f64652e6d6574726963732e72656d61696e696e674b4553506572696f64738142333682781a63617264616e6f2e6e6f64652e6d6574726963732e65706f6368814334393082781c63617264616e6f2e6e6f64652e6d6574726963732e736c6f744e756d81473734303332323182782063617264616e6f2e6e6f64652e6d6574726963732e736c6f74496e45706f63688143333231"
    private const val NOTHING_NODE_STATE_VALUE_REPLY_HEX =
        "82038882783463617264616e6f2e6e6f64652e6d6574726963732e636f6e6e656374696f6e4d616e616765722e6f7574676f696e67436f6e6e738142313282783463617264616e6f2e6e6f64652e6d6574726963732e636f6e6e656374696f6e4d616e616765722e696e636f6d696e67436f6e6e7381413782781d63617264616e6f2e6e6f64652e6d6574726963732e626c6f636b4e756d81473734303332323182782863617264616e6f2e6e6f64652e6d6574726963732e72656d61696e696e674b4553506572696f64738142333682781a63617264616e6f2e6e6f64652e6d6574726963732e65706f6368814334393082781c63617264616e6f2e6e6f64652e6d6574726963732e736c6f744e756d8082782063617264616e6f2e6e6f64652e6d6574726963732e736c6f74496e45706f6368814333323182782463617264616e6f2e6e6f64652e6d6574726963732e74787350726f6365737365644e756d8146313233343536"
    private const val MALFORMED_NODE_STATE_SCALAR_REPLY_HEX =
        "82038882783463617264616e6f2e6e6f64652e6d6574726963732e636f6e6e656374696f6e4d616e616765722e6f7574676f696e67436f6e6e738142313282783463617264616e6f2e6e6f64652e6d6574726963732e636f6e6e656374696f6e4d616e616765722e696e636f6d696e67436f6e6e7381413782781d63617264616e6f2e6e6f64652e6d6574726963732e626c6f636b4e756d81473734303332323182782863617264616e6f2e6e6f64652e6d6574726963732e72656d61696e696e674b4553506572696f64738142333682781a63617264616e6f2e6e6f64652e6d6574726963732e65706f63688146226f6f70732282781c63617264616e6f2e6e6f64652e6d6574726963732e736c6f744e756d81473734303332323182782063617264616e6f2e6e6f64652e6d6574726963732e736c6f74496e45706f6368814333323182782463617264616e6f2e6e6f64652e6d6574726963732e74787350726f6365737365644e756d8146313233343536"
    private const val OVERFLOWING_NODE_STATE_REPLY_HEX =
        "82038882783463617264616e6f2e6e6f64652e6d6574726963732e636f6e6e656374696f6e4d616e616765722e6f7574676f696e67436f6e6e73814a3231343734383336343882783463617264616e6f2e6e6f64652e6d6574726963732e636f6e6e656374696f6e4d616e616765722e696e636f6d696e67436f6e6e7381413782781d63617264616e6f2e6e6f64652e6d6574726963732e626c6f636b4e756d81473734303332323182782863617264616e6f2e6e6f64652e6d6574726963732e72656d61696e696e674b4553506572696f64738142333682781a63617264616e6f2e6e6f64652e6d6574726963732e65706f6368814334393082781c63617264616e6f2e6e6f64652e6d6574726963732e736c6f744e756d81473734303332323182782063617264616e6f2e6e6f64652e6d6574726963732e736c6f74496e45706f6368814333323182782463617264616e6f2e6e6f64652e6d6574726963732e74787350726f6365737365644e756d8146313233343536"
    private const val CHUNKED_NODE_STATE_REPLY_HEX =
        "82038882783463617264616e6f2e6e6f64652e6d6574726963732e636f6e6e656374696f6e4d616e616765722e6f7574676f696e67436f6e6e738142313282783463617264616e6f2e6e6f64652e6d6574726963732e636f6e6e656374696f6e4d616e616765722e696e636f6d696e67436f6e6e7381413782781d63617264616e6f2e6e6f64652e6d6574726963732e626c6f636b4e756d81473734303332323182782863617264616e6f2e6e6f64652e6d6574726963732e72656d61696e696e674b4553506572696f64738142333682781a63617264616e6f2e6e6f64652e6d6574726963732e65706f6368814334393082781c63617264616e6f2e6e6f64652e6d6574726963732e736c6f744e756d815f433734304433323231ff82782063617264616e6f2e6e6f64652e6d6574726963732e736c6f74496e45706f6368814333323182782463617264616e6f2e6e6f64652e6d6574726963732e74787350726f6365737365644e756d8146313233343536"

    private fun String.hexToByteArray(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

data class DataPointFixture(
    val name: String,
    val rawJsonValue: String?,
) {
    fun maybeValue(): CborArray =
        CborArray.create().apply {
            rawJsonValue?.let { add(CborByteString.create(it.encodeToByteArray())) }
        }
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
