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
    fun pinnedNodeStateRequest(): ByteArray = msgDataPointsRequest()

    fun pinnedNodeStateReply(): ByteArray = msgDataPointsReply(*fullNodeStateDataPoints().toTypedArray())

    fun pinnedNodeStateWithStartupReply(): ByteArray =
        msgDataPointsReply(*fullNodeStateWithStartupDataPoints().toTypedArray())

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
        namespace: List<String> = listOf("Forge", "AdoptedBlock"),
    ): ForwardedTraceObjectFixture =
        ForwardedTraceObjectFixture(
            traceObjectJson =
                """
                {
                  "toNamespace": ${jsonArray(namespace)},
                  "toMachine": {"kind":"TraceAdoptedBlock","slot":$slot,"blockHash":"$blockHash","blockSize":1234},
                  "toSeverity": "Info",
                  "toDetails": "DNormal",
                  "toHostname": "core-node-1",
                  "toThreadId": "trace-forward-1",
                  "toTimestamp": "2026-05-12T00:00:00Z"
                }
                """.trimIndent(),
        )

    fun forgedBlockTraceObject(
        slot: Long = 7_403_221L,
        blockHash: String = "6dc4f778bf6ff15f8f3c7c3d98e6c6c8321df6e3e97e2cb7f1f1d6ca0b5c4abc",
        blockNo: Long = 7_403_221L,
        namespace: List<String> = listOf("Forge", "ForgedBlock"),
    ): ForwardedTraceObjectFixture =
        ForwardedTraceObjectFixture(
            traceObjectJson =
                """
                {
                  "toNamespace": ${jsonArray(namespace)},
                  "toMachine": {"kind":"TraceForgedBlock","slot":$slot,"block":"$blockHash","blockNo":$blockNo,"blockPrev":"prevhash"},
                  "toSeverity": "Info",
                  "toDetails": "DNormal",
                  "toHostname": "core-node-1",
                  "toThreadId": "trace-forward-1",
                  "toTimestamp": "2026-05-12T00:00:00Z"
                }
                """.trimIndent(),
        )

    fun forgedBlockHashOnlyTraceObject(
        slot: Long = 7_403_221L,
        blockHash: String = "6dc4f778bf6ff15f8f3c7c3d98e6c6c8321df6e3e97e2cb7f1f1d6ca0b5c4abc",
        blockNo: Long = 7_403_221L,
        namespace: List<String> = listOf("Forge", "ForgedBlock"),
    ): ForwardedTraceObjectFixture =
        ForwardedTraceObjectFixture(
            traceObjectJson =
                """
                {
                  "toNamespace": ${jsonArray(namespace)},
                  "toMachine": {"kind":"TraceForgedBlock","slot":$slot,"blockHash":"$blockHash","blockNo":$blockNo,"blockPrev":"prevhash"},
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

    fun nodeStateTraceObject(
        blockHeight: Long = 7_403_221L,
        slot: Long = 7_403_221L,
    ): ForwardedTraceObjectFixture =
        ForwardedTraceObjectFixture(
            traceObjectJson =
                """
                {
                  "toNamespace": ["ChainDB.AddBlockEvent.AddedToCurrentChain"],
                  "toMachine": {"kind":"AddedToCurrentChain","newSuffixSelectView":{"blockNo":$blockHeight,"slotNo":$slot},"newtip":"hash@$slot"},
                  "toSeverity": "Notice",
                  "toDetails": "DNormal",
                  "toHostname": "core-node-1",
                  "toThreadId": "trace-forward-1",
                  "toTimestamp": "2026-05-12T00:00:00Z"
                }
                """.trimIndent(),
        )

    fun connectionManagerCountersTraceObject(
        outbound: Int = 2,
        inbound: Int = 2,
        duplex: Int = 2,
        fullDuplex: Int = 2,
        unidirectional: Int = 0,
    ): ForwardedTraceObjectFixture =
        ForwardedTraceObjectFixture(
            traceObjectJson =
                """
                {
                  "toNamespace": ["Net.ConnectionManager.Remote.ConnectionManagerCounters"],
                  "toMachine": {"kind":"ConnectionManagerCounters","state":{"duplex":$duplex,"fullDuplex":$fullDuplex,"inbound":$inbound,"outbound":$outbound,"unidirectional":$unidirectional}},
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

    fun fullNodeStateWithStartupDataPoints(): List<DataPointFixture> =
        fullNodeStateDataPoints() +
            dataPoint(
                NodeStateDataPointDecoder.KEY_NODE_STARTUP_INFO,
                """
                {"era":"Conway","epochLength":432000,"slotLength":1,"slotsPerKESPeriod":129600}
                """.trimIndent(),
            )

    fun traceObjectsArray(vararg traceObjects: ForwardedTraceObjectFixture): CborArray =
        CborArray.create().apply {
            traceObjects.forEach { traceObject ->
                add(CborTextString.create(traceObject.traceObjectJson))
            }
        }

    private fun jsonArray(values: List<String>): String = values.joinToString(prefix = "[\"", separator = "\", \"", postfix = "\"]")

    fun missingNodeStateKeyReply(): ByteArray =
        msgDataPointsReply(
            dataPoint(NodeStateDataPointDecoder.KEY_OUTGOING_CONNS, "12"),
            dataPoint(NodeStateDataPointDecoder.KEY_INCOMING_CONNS, "7"),
            dataPoint(NodeStateDataPointDecoder.KEY_BLOCK_NUM, "7403221"),
            dataPoint(NodeStateDataPointDecoder.KEY_REMAINING_KES_PERIODS, "36"),
            dataPoint(NodeStateDataPointDecoder.KEY_EPOCH, "490"),
            dataPoint(NodeStateDataPointDecoder.KEY_SLOT_NUM, "7403221"),
            dataPoint(NodeStateDataPointDecoder.KEY_SLOT_IN_EPOCH, "321"),
        )

    fun nothingNodeStateValueReply(): ByteArray =
        msgDataPointsReply(
            dataPoint(NodeStateDataPointDecoder.KEY_OUTGOING_CONNS, "12"),
            dataPoint(NodeStateDataPointDecoder.KEY_INCOMING_CONNS, "7"),
            dataPoint(NodeStateDataPointDecoder.KEY_BLOCK_NUM, "7403221"),
            dataPoint(NodeStateDataPointDecoder.KEY_REMAINING_KES_PERIODS, "36"),
            dataPoint(NodeStateDataPointDecoder.KEY_EPOCH, "490"),
            dataPoint(NodeStateDataPointDecoder.KEY_SLOT_NUM, null),
            dataPoint(NodeStateDataPointDecoder.KEY_SLOT_IN_EPOCH, "321"),
            dataPoint(NodeStateDataPointDecoder.KEY_TXS_PROCESSED_NUM, "123456"),
        )

    fun malformedNodeStateScalarReply(): ByteArray =
        msgDataPointsReply(
            dataPoint(NodeStateDataPointDecoder.KEY_OUTGOING_CONNS, "12"),
            dataPoint(NodeStateDataPointDecoder.KEY_INCOMING_CONNS, "7"),
            dataPoint(NodeStateDataPointDecoder.KEY_BLOCK_NUM, "7403221"),
            dataPoint(NodeStateDataPointDecoder.KEY_REMAINING_KES_PERIODS, "36"),
            dataPoint(NodeStateDataPointDecoder.KEY_EPOCH, "\"oops\""),
            dataPoint(NodeStateDataPointDecoder.KEY_SLOT_NUM, "7403221"),
            dataPoint(NodeStateDataPointDecoder.KEY_SLOT_IN_EPOCH, "321"),
            dataPoint(NodeStateDataPointDecoder.KEY_TXS_PROCESSED_NUM, "123456"),
        )

    fun overflowingNodeStateReply(): ByteArray =
        msgDataPointsReply(
            dataPoint(NodeStateDataPointDecoder.KEY_OUTGOING_CONNS, "2147483648"),
            dataPoint(NodeStateDataPointDecoder.KEY_INCOMING_CONNS, "7"),
            dataPoint(NodeStateDataPointDecoder.KEY_BLOCK_NUM, "7403221"),
            dataPoint(NodeStateDataPointDecoder.KEY_REMAINING_KES_PERIODS, "36"),
            dataPoint(NodeStateDataPointDecoder.KEY_EPOCH, "490"),
            dataPoint(NodeStateDataPointDecoder.KEY_SLOT_NUM, "7403221"),
            dataPoint(NodeStateDataPointDecoder.KEY_SLOT_IN_EPOCH, "321"),
            dataPoint(NodeStateDataPointDecoder.KEY_TXS_PROCESSED_NUM, "123456"),
        )

    fun legacyPrefixedNodeStateReply(): ByteArray =
        msgDataPointsReply(
            dataPoint(NodeStateDataPointDecoder.LEGACY_KEY_OUTGOING_CONNS, "12"),
            dataPoint(NodeStateDataPointDecoder.LEGACY_KEY_INCOMING_CONNS, "7"),
            dataPoint(NodeStateDataPointDecoder.LEGACY_KEY_BLOCK_NUM, "7403221"),
            dataPoint(NodeStateDataPointDecoder.LEGACY_KEY_REMAINING_KES_PERIODS, "36"),
            dataPoint(NodeStateDataPointDecoder.LEGACY_KEY_EPOCH, "490"),
            dataPoint(NodeStateDataPointDecoder.LEGACY_KEY_SLOT_NUM, "7403221"),
            dataPoint(NodeStateDataPointDecoder.LEGACY_KEY_SLOT_IN_EPOCH, "321"),
            dataPoint(NodeStateDataPointDecoder.LEGACY_KEY_TXS_PROCESSED_NUM, "123456"),
        )

    fun nodeAddBlockOnlyReply(): ByteArray =
        msgDataPointsReply(
            dataPoint(
                NodeStateDataPointDecoder.KEY_NODE_ADD_BLOCK,
                """
                {"tag":"NodeAddBlock","contents":[320,74,74.64714228564361]}
                """.trimIndent(),
            )
        )

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

    private fun String.hexToByteArray(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private const val CHUNKED_NODE_STATE_REPLY_HEX =
        "82038882781f636f6e6e656374696f6e4d616e616765722e6f7574676f696e67436f6e6e738142313282781f636f6e6e656374696f6e4d616e616765722e696e636f6d696e67436f6e6e738141378268626c6f636b4e756d814737343033323231827372656d61696e696e674b4553506572696f647381423336826565706f636881433439308267736c6f744e756d815f433734304433323231ff826b736c6f74496e45706f63688143333231826f74787350726f6365737365644e756d8146313233343536"
}

data class DataPointFixture(
    val name: String,
    val rawJsonValue: String? = null,
) {
    fun maybeValue(): CborArray =
        CborArray.create().apply {
            rawJsonValue?.let { add(CborByteString.create(rawJsonValue.encodeToByteArray())) }
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
