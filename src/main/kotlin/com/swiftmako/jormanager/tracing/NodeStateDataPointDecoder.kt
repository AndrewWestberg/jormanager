package com.swiftmako.jormanager.tracing

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborByteString
import com.google.iot.cbor.CborTextString
import com.swiftmako.jormanager.model.NodeStats
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.BigInteger

data class NodeStartupInfo(
    val era: String?,
    val epochLength: Long?,
    val slotLength: Long?,
    val slotsPerKESPeriod: Long?,
)

data class NodeStateMetrics(
    val peers: Int,
    val incomingPeers: Int,
    val blockHeight: Long,
    val remainingKESPeriods: Int,
    val epoch: Long,
    val slot: Long,
    val slotInEpoch: Long,
    val txsProcessed: Long,
) {
    fun toNodeStats(
        timestamp: Long,
        nodeName: String,
        color: String,
        isDefault: Boolean,
        epochLength: Long,
    ): NodeStats =
        NodeStats(
            isDefault = isDefault,
            timestamp = timestamp,
            nodeName = nodeName,
            color = color,
            peers = peers,
            incomingPeers = incomingPeers,
            blockHeight = blockHeight,
            remainingKESPeriods = remainingKESPeriods,
            epoch = epoch,
            slot = slot,
            slotInEpoch = slotInEpoch,
            txsProcessed = txsProcessed,
            epochLength = epochLength,
        )
}

class NodeStateDataPointDecoder {
    fun decode(reply: TraceForwardMessage.DataPointsReply): NodeStateMetrics? = reply.dataPoints.toDataPointValueMap()?.let(::decodeValues)

    internal fun decodeValues(values: Map<String, ByteArray?>): NodeStateMetrics? =
        runCatching {
            val nodeAddBlock = values.parseNodeAddBlock()
            val epoch = values.requireLong(KEY_EPOCH, LEGACY_KEY_EPOCH) ?: nodeAddBlock?.epoch ?: return null
            val slotInEpoch = values.requireLong(KEY_SLOT_IN_EPOCH, LEGACY_KEY_SLOT_IN_EPOCH) ?: nodeAddBlock?.slotInEpoch ?: return null
            NodeStateMetrics(
                peers = values.requireInt(KEY_OUTGOING_CONNS, LEGACY_KEY_OUTGOING_CONNS) ?: 0,
                incomingPeers = values.requireInt(KEY_INCOMING_CONNS, LEGACY_KEY_INCOMING_CONNS) ?: 0,
                blockHeight = values.requireLong(KEY_BLOCK_NUM, LEGACY_KEY_BLOCK_NUM) ?: return null,
                remainingKESPeriods = values.requireInt(KEY_REMAINING_KES_PERIODS, LEGACY_KEY_REMAINING_KES_PERIODS) ?: 0,
                epoch = epoch,
                slot = values.requireLong(KEY_SLOT_NUM, LEGACY_KEY_SLOT_NUM) ?: 0L,
                slotInEpoch = slotInEpoch,
                txsProcessed = values.requireLong(KEY_TXS_PROCESSED_NUM, LEGACY_KEY_TXS_PROCESSED_NUM) ?: 0,
            )
        }.getOrNull()

    companion object {
        const val KEY_OUTGOING_CONNS = "connectionManager.outgoingConns"
        const val KEY_INCOMING_CONNS = "connectionManager.incomingConns"
        const val KEY_BLOCK_NUM = "blockNum"
        const val KEY_REMAINING_KES_PERIODS = "remainingKESPeriods"
        const val KEY_EPOCH = "epoch"
        const val KEY_SLOT_NUM = "slotNum"
        const val KEY_SLOT_IN_EPOCH = "slotInEpoch"
        const val KEY_TXS_PROCESSED_NUM = "txsProcessedNum"
        const val KEY_NODE_STARTUP_INFO = "NodeStartupInfo"
        const val KEY_NODE_ADD_BLOCK = "NodeAddBlock"

        const val LEGACY_KEY_OUTGOING_CONNS = "cardano.node.metrics.connectionManager.outgoingConns"
        const val LEGACY_KEY_INCOMING_CONNS = "cardano.node.metrics.connectionManager.incomingConns"
        const val LEGACY_KEY_BLOCK_NUM = "cardano.node.metrics.blockNum"
        const val LEGACY_KEY_REMAINING_KES_PERIODS = "cardano.node.metrics.remainingKESPeriods"
        const val LEGACY_KEY_EPOCH = "cardano.node.metrics.epoch"
        const val LEGACY_KEY_SLOT_NUM = "cardano.node.metrics.slotNum"
        const val LEGACY_KEY_SLOT_IN_EPOCH = "cardano.node.metrics.slotInEpoch"
        const val LEGACY_KEY_TXS_PROCESSED_NUM = "cardano.node.metrics.txsProcessedNum"

        val REQUESTED_NAMES =
            listOf(
                KEY_OUTGOING_CONNS,
                KEY_INCOMING_CONNS,
                KEY_BLOCK_NUM,
                KEY_REMAINING_KES_PERIODS,
                KEY_EPOCH,
                KEY_SLOT_NUM,
                KEY_SLOT_IN_EPOCH,
                KEY_TXS_PROCESSED_NUM,
                KEY_NODE_STARTUP_INFO,
                KEY_NODE_ADD_BLOCK,
            )
    }
}

private const val JSON_TAG_FIELD = "tag"
private const val JSON_CONTENTS_FIELD = "contents"
private const val NODE_ADD_BLOCK_TAG = "NodeAddBlock"
private const val NODE_ADD_BLOCK_CONTENTS_SIZE = 3

internal fun CborArray.toDataPointValueMap(): Map<String, ByteArray?>? {
    val decoded = linkedMapOf<String, ByteArray?>()
    for (index in 0 until size()) {
        val pair = elementAt(index) as? CborArray ?: return null
        if (pair.size() != 2) {
            return null
        }

        val name = (pair.elementAt(0) as? CborTextString)?.stringValue() ?: return null
        val maybeValue = pair.elementAt(1) as? CborArray ?: return null
        if (decoded.put(name, maybeValue.readMaybeValue()) != null) {
            return null
        }
    }
    return decoded
}

internal fun Map<String, ByteArray?>.parseNodeStartupInfo(): NodeStartupInfo? = findValue(NodeStateDataPointDecoder.KEY_NODE_STARTUP_INFO)?.let(::parseNodeStartupInfo)

private fun CborArray.readMaybeValue(): ByteArray? =
    when (size()) {
        0 -> null
        1 -> (elementAt(0) as? CborByteString)?.joinedByteArrayValue()
        else -> null
    }

private fun CborByteString.joinedByteArrayValue(): ByteArray {
    val chunks = byteArrayValue()
    if (chunks.size == 1) {
        return chunks[0].copyOf()
    }

    return ByteArrayOutputStream().use { output ->
        chunks.forEach(output::write)
        output.toByteArray()
    }
}

private fun Map<String, ByteArray?>.requireInt(vararg keys: String): Int? = findValue(*keys)?.parseIntegerValue()?.intValueExact()

private fun Map<String, ByteArray?>.requireLong(vararg keys: String): Long? = findValue(*keys)?.parseIntegerValue()?.longValueExact()

private fun Map<String, ByteArray?>.findValue(vararg keys: String): ByteArray? = keys.firstNotNullOfOrNull(::get)

private fun Map<String, ByteArray?>.parseNodeAddBlock(): NodeAddBlockValue? = findValue(NodeStateDataPointDecoder.KEY_NODE_ADD_BLOCK)?.let(::parseNodeAddBlock)

private fun parseNodeAddBlock(rawJson: ByteArray): NodeAddBlockValue? =
    runCatching {
        tracingJsonFactory.createParser(rawJson).use { parser ->
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return null
            }

            var tag: String? = null
            var epoch: Long? = null
            var slotInEpoch: Long? = null

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                val fieldName = parser.currentName() ?: continue
                parser.nextToken()
                when (fieldName) {
                    JSON_TAG_FIELD -> tag = parser.valueAsString
                    JSON_CONTENTS_FIELD -> {
                        if (parser.currentToken != JsonToken.START_ARRAY) {
                            parser.skipChildren()
                            continue
                        }

                        var index = 0
                        while (parser.nextToken() != JsonToken.END_ARRAY) {
                            when (index) {
                                0 -> epoch = parser.longValueOrNull()
                                1 -> slotInEpoch = parser.longValueOrNull()
                                else -> parser.skipChildren()
                            }
                            index++
                        }

                        if (index < NODE_ADD_BLOCK_CONTENTS_SIZE) {
                            return null
                        }
                    }

                    else -> parser.skipChildren()
                }
            }

            if (tag != NODE_ADD_BLOCK_TAG) {
                return null
            }

            NodeAddBlockValue(
                epoch = epoch ?: return null,
                slotInEpoch = slotInEpoch ?: return null,
            )
        }
    }.getOrNull()

private fun parseNodeStartupInfo(rawJson: ByteArray): NodeStartupInfo? =
    runCatching {
        tracingJsonFactory.createParser(rawJson).use { parser ->
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return null
            }

            var era: String? = null
            var epochLength: Long? = null
            var slotLength: Long? = null
            var slotsPerKesPeriod: Long? = null

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                val fieldName = parser.currentName() ?: continue
                parser.nextToken()
                when (fieldName) {
                    "era", "suiEra" -> if (era.isNullOrBlank()) era = parser.valueAsString?.takeIf(String::isNotBlank)
                    "epochLength", "suiEpochLength" -> if (epochLength == null) epochLength = parser.longValueOrNull()
                    "slotLength", "suiSlotLength" -> if (slotLength == null) slotLength = parser.longValueOrNull()
                    "slotsPerKESPeriod", "suiSlotsPerKESPeriod" -> if (slotsPerKesPeriod == null) slotsPerKesPeriod = parser.longValueOrNull()
                    else -> parser.skipChildren()
                }
            }

            NodeStartupInfo(
                era = era,
                epochLength = epochLength,
                slotLength = slotLength,
                slotsPerKESPeriod = slotsPerKesPeriod,
            )
        }
    }.getOrNull()?.takeIf {
        it.era != null ||
            it.epochLength != null ||
            it.slotLength != null ||
            it.slotsPerKESPeriod != null
    }

private fun ByteArray.parseIntegerValue(): BigInteger? =
    runCatching {
        BigDecimal(decodeToString().trim()).toBigIntegerExact()
    }.getOrNull()

private data class NodeAddBlockValue(
    val epoch: Long,
    val slotInEpoch: Long,
)

private val tracingJsonFactory = JsonFactory()

private fun com.fasterxml.jackson.core.JsonParser.longValueOrNull(): Long? =
    when (currentToken()) {
        JsonToken.VALUE_NUMBER_INT -> longValue
        JsonToken.VALUE_STRING -> valueAsString?.toLongOrNull()
        else -> null
    }
