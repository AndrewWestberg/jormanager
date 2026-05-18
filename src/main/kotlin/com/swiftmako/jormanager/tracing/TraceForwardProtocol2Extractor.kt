package com.swiftmako.jormanager.tracing

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborByteString
import com.google.iot.cbor.CborObject
import com.google.iot.cbor.CborTextString
import org.json.JSONArray
import org.json.JSONObject
import org.springframework.stereotype.Component

@Component
class TraceForwardProtocol2Extractor {
    fun decodeNodeState(batches: Iterable<TracingRawTraceObjectBatch>): ForwardedNodeState? =
        batches
            .mapNotNull { batch -> decodeNodeState(batch.toMessage()) }
            .fold(null as ForwardedNodeState?) { acc, next -> acc?.merge(next) ?: next }

    fun decodeNodeState(reply: TraceForwardMessage.TraceObjectsReply): ForwardedNodeState? =
        reply.traceObjectsJson()
            .mapNotNull(::decodeNodeState)
            .fold(null as ForwardedNodeState?) { acc, next -> acc?.merge(next) ?: next }

    fun decodeNodeState(traceObjectJson: String): ForwardedNodeState? =
        runCatching {
            val traceObject = JSONObject(traceObjectJson)
            val machine = traceObject.machineObject() ?: return null
            when (traceObject.namespace()) {
                ADDED_TO_CURRENT_CHAIN_NAMESPACE -> {
                    if (machine.optString(KIND_FIELD) != ADDED_TO_CURRENT_CHAIN_KIND) {
                        return null
                    }

                    val newSuffix = machine.optJSONObject(NEW_SUFFIX_SELECT_VIEW_FIELD) ?: return null
                    val slot = newSuffix.optLongOrNull(SLOT_NO_FIELD) ?: return null
                    val blockHeight = newSuffix.optLongOrNull(BLOCK_NO_FIELD) ?: return null
                    ForwardedNodeState(slot = slot, blockHeight = blockHeight)
                }

                CONNECTION_COUNTERS_NAMESPACE -> {
                    if (machine.optString(KIND_FIELD) != CONNECTION_COUNTERS_KIND) {
                        return null
                    }

                    val state = machine.optJSONObject(STATE_FIELD) ?: return null
                    ForwardedNodeState(
                        peers = state.optIntOrNull(OUTBOUND_FIELD),
                        incomingPeers = state.optIntOrNull(INBOUND_FIELD),
                    )
                }

                else -> null
            }
        }.getOrNull()

    fun decodeBlockEvents(batch: TracingRawTraceObjectBatch): List<ForwardedBlockEvent> = decodeBlockEvents(batch.toMessage())

    fun decodeBlockEvents(reply: TraceForwardMessage.TraceObjectsReply): List<ForwardedBlockEvent> =
        reply.traceObjectsJson().mapNotNull(::decodeBlockEvent)

    fun decodeBlockEvent(traceObjectJson: String): ForwardedBlockEvent? =
        runCatching {
            val traceObject = JSONObject(traceObjectJson)
            val machine = traceObject.machineObject() ?: return null
            val decoded =
                when {
                    traceObject.namespace() in ADOPTED_BLOCK_NAMESPACES && machine.optString(KIND_FIELD) == ADOPTED_BLOCK_KIND ->
                        DecodedBlockEvent(status = "completed", blockHash = machine.optNonBlankString(BLOCK_HASH_FIELD) ?: return null)

                    traceObject.namespace() in FORGED_BLOCK_NAMESPACES && machine.optString(KIND_FIELD) == FORGED_BLOCK_KIND ->
                        DecodedBlockEvent(status = "created", blockHash = machine.optNonBlankString(BLOCK_FIELD) ?: return null)

                    else -> return null
                }

            val slot = machine.optLongOrNull(SLOT_FIELD) ?: return null
            val timestamp = traceObject.optNonBlankString(TIMESTAMP_FIELD) ?: return null
            val hostname = traceObject.optNonBlankString(HOSTNAME_FIELD) ?: return null

            ForwardedBlockEvent(
                slot = slot,
                blockHash = decoded.blockHash,
                timestamp = timestamp,
                hostname = hostname,
                status = decoded.status,
            )
        }.getOrNull()

    private fun TraceForwardMessage.TraceObjectsReply.traceObjectsJson(): List<String> =
        buildList(traceObjects.size()) {
            for (index in 0 until traceObjects.size()) {
                traceObjects.elementAt(index).toTraceObjectJsonOrNull()?.let(::add)
            }
        }

    private fun CborObject.toTraceObjectJsonOrNull(): String? =
        when (this) {
            is CborArray -> elementAtOrNull(MACHINE_JSON_INDEX)?.toTraceObjectJsonOrNull()
            is CborTextString -> stringValue()
            is CborByteString -> byteArrayValue()[0].decodeToString()
            else -> toJsonString()
        }

    private fun CborArray.elementAtOrNull(index: Int): CborObject? =
        if (index in 0 until size()) elementAt(index) else null

    private fun JSONObject.namespace(): List<String> {
        val namespace = optJSONArray(NAMESPACE_FIELD) ?: return emptyList()
        return List(namespace.length(), namespace::getString)
    }

    private fun JSONObject.machineObject(): JSONObject? =
        when (val machine = opt(MACHINE_FIELD)) {
            is JSONObject -> machine
            is String -> JSONObject(machine)
            is JSONArray -> null
            else -> null
        }

    private fun JSONObject.optLongOrNull(fieldName: String): Long? {
        if (!has(fieldName) || isNull(fieldName)) return null
        return (opt(fieldName) as? Number)?.toLong()
    }

    private fun JSONObject.optIntOrNull(fieldName: String): Int? {
        if (!has(fieldName) || isNull(fieldName)) return null
        return (opt(fieldName) as? Number)?.toInt()
    }

    private fun JSONObject.optNonBlankString(fieldName: String): String? =
        optString(fieldName, null)?.takeUnless(String::isNullOrBlank)

    private data class DecodedBlockEvent(
        val status: String,
        val blockHash: String,
    )

    private companion object {
        const val NAMESPACE_FIELD = "toNamespace"
        const val MACHINE_FIELD = "toMachine"
        const val KIND_FIELD = "kind"
        const val SLOT_FIELD = "slot"
        const val BLOCK_HASH_FIELD = "blockHash"
        const val BLOCK_FIELD = "block"
        const val TIMESTAMP_FIELD = "toTimestamp"
        const val HOSTNAME_FIELD = "toHostname"
        const val NEW_SUFFIX_SELECT_VIEW_FIELD = "newSuffixSelectView"
        const val SLOT_NO_FIELD = "slotNo"
        const val BLOCK_NO_FIELD = "blockNo"
        const val STATE_FIELD = "state"
        const val OUTBOUND_FIELD = "outbound"
        const val INBOUND_FIELD = "inbound"
        const val MACHINE_JSON_INDEX = 2
        val ADOPTED_BLOCK_NAMESPACES =
            setOf(
                listOf("Forge", "AdoptedBlock"),
                listOf("Forge", "Loop", "AdoptedBlock"),
            )
        const val ADOPTED_BLOCK_KIND = "TraceAdoptedBlock"
        val FORGED_BLOCK_NAMESPACES =
            setOf(
                listOf("Forge", "ForgedBlock"),
                listOf("Forge", "Loop", "ForgedBlock"),
            )
        const val FORGED_BLOCK_KIND = "TraceForgedBlock"
        val ADDED_TO_CURRENT_CHAIN_NAMESPACE = listOf("ChainDB.AddBlockEvent.AddedToCurrentChain")
        const val ADDED_TO_CURRENT_CHAIN_KIND = "AddedToCurrentChain"
        val CONNECTION_COUNTERS_NAMESPACE = listOf("Net.ConnectionManager.Remote.ConnectionManagerCounters")
        const val CONNECTION_COUNTERS_KIND = "ConnectionManagerCounters"
    }
}
