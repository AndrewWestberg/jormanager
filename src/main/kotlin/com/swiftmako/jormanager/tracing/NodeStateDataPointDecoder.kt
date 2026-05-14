package com.swiftmako.jormanager.tracing

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborByteString
import com.google.iot.cbor.CborTextString
import com.swiftmako.jormanager.model.NodeStats
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.BigInteger

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
    fun decode(reply: TraceForwardMessage.DataPointsReply): NodeStateMetrics? =
        runCatching {
            val values = reply.dataPoints.toDataPointValueMap() ?: return null
            NodeStateMetrics(
                peers = values.requireInt(KEY_OUTGOING_CONNS) ?: return null,
                incomingPeers = values.requireInt(KEY_INCOMING_CONNS) ?: return null,
                blockHeight = values.requireLong(KEY_BLOCK_NUM) ?: return null,
                remainingKESPeriods = values.requireInt(KEY_REMAINING_KES_PERIODS) ?: return null,
                epoch = values.requireLong(KEY_EPOCH) ?: return null,
                slot = values.requireLong(KEY_SLOT_NUM) ?: return null,
                slotInEpoch = values.requireLong(KEY_SLOT_IN_EPOCH) ?: return null,
                txsProcessed = values.requireLong(KEY_TXS_PROCESSED_NUM) ?: return null,
            )
        }.getOrNull()

    private fun CborArray.toDataPointValueMap(): Map<String, ByteArray?>? {
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

    private fun Map<String, ByteArray?>.requireInt(key: String): Int? =
        get(key)?.parseIntegerValue()?.intValueExact()

    private fun Map<String, ByteArray?>.requireLong(key: String): Long? =
        get(key)?.parseIntegerValue()?.longValueExact()

    private fun ByteArray.parseIntegerValue(): BigInteger? =
        runCatching {
            BigDecimal(decodeToString().trim()).toBigIntegerExact()
        }.getOrNull()

    companion object {
        const val KEY_OUTGOING_CONNS = "cardano.node.metrics.connectionManager.outgoingConns"
        const val KEY_INCOMING_CONNS = "cardano.node.metrics.connectionManager.incomingConns"
        const val KEY_BLOCK_NUM = "cardano.node.metrics.blockNum"
        const val KEY_REMAINING_KES_PERIODS = "cardano.node.metrics.remainingKESPeriods"
        const val KEY_EPOCH = "cardano.node.metrics.epoch"
        const val KEY_SLOT_NUM = "cardano.node.metrics.slotNum"
        const val KEY_SLOT_IN_EPOCH = "cardano.node.metrics.slotInEpoch"
        const val KEY_TXS_PROCESSED_NUM = "cardano.node.metrics.txsProcessedNum"

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
            )
    }
}
