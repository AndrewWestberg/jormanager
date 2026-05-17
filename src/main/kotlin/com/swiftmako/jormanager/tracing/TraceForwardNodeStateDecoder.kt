package com.swiftmako.jormanager.tracing

import com.google.iot.cbor.CborByteString
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborObject
import com.google.iot.cbor.CborTextString
import org.json.JSONObject

data class ForwardedNodeState(
    val slot: Long? = null,
    val blockHeight: Long? = null,
    val peers: Int? = null,
    val incomingPeers: Int? = null,
)

class TraceForwardNodeStateDecoder {
    fun decode(reply: TraceForwardMessage.TraceObjectsReply): ForwardedNodeState? =
        (0 until reply.traceObjects.size())
            .asSequence()
            .mapNotNull { index ->
                reply.traceObjects.elementAt(index).toTraceObjectJsonOrNull()
            }.mapNotNull(::decode)
            .fold(null as ForwardedNodeState?) { acc, next -> acc?.merge(next) ?: next }

    fun decode(traceObjectJson: String): ForwardedNodeState? =
        runCatching {
            val traceObject = JSONObject(traceObjectJson)
            val machine = traceObject.machineObject() ?: return null
            when (traceObject.namespace()) {
                ADDED_TO_CURRENT_CHAIN_NAMESPACE -> {
                    if (machine.optString("kind") != ADDED_TO_CURRENT_CHAIN_KIND) {
                        return null
                    }

                    val newSuffix = machine.optJSONObject("newSuffixSelectView") ?: return null
                    val slot = newSuffix.optLongOrNull("slotNo") ?: return null
                    val blockHeight = newSuffix.optLongOrNull("blockNo") ?: return null
                    ForwardedNodeState(
                        slot = slot,
                        blockHeight = blockHeight,
                    )
                }

                CONNECTION_COUNTERS_NAMESPACE -> {
                    if (machine.optString("kind") != CONNECTION_COUNTERS_KIND) {
                        return null
                    }

                    val state = machine.optJSONObject("state") ?: return null
                    ForwardedNodeState(
                        peers = state.optIntOrNull("outbound"),
                        incomingPeers = state.optIntOrNull("inbound"),
                    )
                }

                else -> null
            }
        }.getOrNull()

    private fun CborObject.toTraceObjectJsonOrNull(): String? =
        when (this) {
            is CborArray -> elementAtOrNull(MACHINE_JSON_INDEX)?.toTraceObjectJsonOrNull()
            is CborTextString -> stringValue()
            is CborByteString -> byteArrayValue()[0].decodeToString()
            else -> toJsonString()
        }

    private fun CborArray.elementAtOrNull(index: Int): CborObject? =
        if (index in 0 until size()) {
            elementAt(index)
        } else {
            null
        }

    private fun JSONObject.namespace(): List<String> {
        val namespace = optJSONArray(NAMESPACE_FIELD) ?: return emptyList()
        return List(namespace.length(), namespace::getString)
    }

    private fun JSONObject.machineObject(): JSONObject? =
        when (val machine = opt(MACHINE_FIELD)) {
            is JSONObject -> machine
            is String -> JSONObject(machine)
            else -> null
        }

    private fun JSONObject.optLongOrNull(fieldName: String): Long? {
        if (!has(fieldName) || isNull(fieldName)) {
            return null
        }

        return when (val value = opt(fieldName)) {
            is Number -> value.toLong()
            else -> null
        }
    }

    private fun JSONObject.optIntOrNull(fieldName: String): Int? {
        if (!has(fieldName) || isNull(fieldName)) {
            return null
        }

        return when (val value = opt(fieldName)) {
            is Number -> value.toInt()
            else -> null
        }
    }

    private fun ForwardedNodeState.merge(other: ForwardedNodeState): ForwardedNodeState =
        ForwardedNodeState(
            slot = other.slot ?: slot,
            blockHeight = other.blockHeight ?: blockHeight,
            peers = other.peers ?: peers,
            incomingPeers = other.incomingPeers ?: incomingPeers,
        )

    companion object {
        private const val NAMESPACE_FIELD = "toNamespace"
        private const val MACHINE_FIELD = "toMachine"
        private val ADDED_TO_CURRENT_CHAIN_NAMESPACE = listOf("ChainDB.AddBlockEvent.AddedToCurrentChain")
        private const val ADDED_TO_CURRENT_CHAIN_KIND = "AddedToCurrentChain"
        private val CONNECTION_COUNTERS_NAMESPACE = listOf("Net.ConnectionManager.Remote.ConnectionManagerCounters")
        private const val CONNECTION_COUNTERS_KIND = "ConnectionManagerCounters"
        private const val MACHINE_JSON_INDEX = 2
    }
}
