package com.swiftmako.jormanager.tracing

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborByteString
import com.google.iot.cbor.CborObject
import com.google.iot.cbor.CborTextString
import org.springframework.stereotype.Component

@Component
class TraceForwardProtocol2Extractor {
    fun decodeNodeState(batches: Iterable<TracingRawTraceObjectBatch>): ForwardedNodeState? = mergeNodeStates(batches.flatMap { batch -> batch.toMessage().traceObjectsJson() })

    fun decodeNodeState(reply: TraceForwardMessage.TraceObjectsReply): ForwardedNodeState? = mergeNodeStates(reply.traceObjectsJson())

    fun decodeNodeState(traceObjectJson: String): ForwardedNodeState? = parseTraceObject(traceObjectJson)?.toNodeState()

    fun decodeBlockEvents(batch: TracingRawTraceObjectBatch): List<ForwardedBlockEvent> = decodeBlockEvents(batch.toMessage())

    fun decodeBlockEvents(reply: TraceForwardMessage.TraceObjectsReply): List<ForwardedBlockEvent> = reply.traceObjectsJson().mapNotNull(::decodeBlockEvent)

    fun decodeBlockEvent(traceObjectJson: String): ForwardedBlockEvent? = parseTraceObject(traceObjectJson)?.toBlockEvent()

    private fun TraceForwardMessage.TraceObjectsReply.traceObjectsJson(): List<String> =
        buildList(traceObjects.size()) {
            for (index in 0 until traceObjects.size()) {
                traceObjects.elementAt(index).toTraceObjectJsonOrNull()?.let(::add)
            }
        }

    private fun mergeNodeStates(traceObjectsJson: Iterable<String>): ForwardedNodeState? {
        val decodedStates = traceObjectsJson.mapNotNull(::decodeNodeState)
        val mergedState = decodedStates.fold(null as ForwardedNodeState?) { acc, next -> acc?.merge(next) ?: next }
        return mergedState
    }

    private fun CborObject.toTraceObjectJsonOrNull(): String? =
        when (this) {
            is CborArray -> elementAtOrNull(MACHINE_JSON_INDEX)?.toTraceObjectJsonOrNull()
            is CborTextString -> stringValue()
            is CborByteString -> byteArrayValue()[0].decodeToString()
            else -> toJsonString()
        }

    private fun CborArray.elementAtOrNull(index: Int): CborObject? = if (index in 0 until size()) elementAt(index) else null

    private fun parseTraceObject(traceObjectJson: String): ParsedTraceObject? =
        runCatching {
            jsonFactory.createParser(traceObjectJson).use { parser ->
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    return null
                }

                var namespace: String? = null
                var at: String? = null
                var host: String? = null
                var kind: String? = null
                var slot: Long? = null
                var blockHash: String? = null
                var block: String? = null
                var addedToChainSlot: Long? = null
                var addedToChainBlockHeight: Long? = null
                var outboundPeers: Int? = null
                var inboundPeers: Int? = null

                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    val fieldName = parser.currentName() ?: continue
                    parser.nextToken()
                    when (fieldName) {
                        NS_FIELD -> namespace = parser.valueAsString?.takeIf(String::isNotBlank)
                        AT_FIELD -> at = parser.valueAsString?.takeIf(String::isNotBlank)
                        HOST_FIELD -> host = parser.valueAsString?.takeIf(String::isNotBlank)
                        DATA_FIELD -> {
                            if (parser.currentToken != JsonToken.START_OBJECT) {
                                parser.skipChildren()
                                continue
                            }

                            while (parser.nextToken() != JsonToken.END_OBJECT) {
                                val dataFieldName = parser.currentName() ?: continue
                                parser.nextToken()
                                when (dataFieldName) {
                                    KIND_FIELD -> kind = parser.valueAsString?.takeIf(String::isNotBlank)
                                    SLOT_FIELD -> slot = parser.longValueOrNull()
                                    BLOCK_HASH_FIELD -> blockHash = parser.valueAsString?.takeIf(String::isNotBlank)
                                    BLOCK_FIELD -> block = parser.valueAsString?.takeIf(String::isNotBlank)
                                    NEW_SUFFIX_SELECT_VIEW_FIELD -> {
                                        if (parser.currentToken != JsonToken.START_OBJECT) {
                                            parser.skipChildren()
                                            continue
                                        }
                                        while (parser.nextToken() != JsonToken.END_OBJECT) {
                                            val suffixFieldName = parser.currentName() ?: continue
                                            parser.nextToken()
                                            when (suffixFieldName) {
                                                SLOT_NO_FIELD -> addedToChainSlot = parser.longValueOrNull()
                                                BLOCK_NO_FIELD -> addedToChainBlockHeight = parser.longValueOrNull()
                                                else -> parser.skipChildren()
                                            }
                                        }
                                    }

                                    STATE_FIELD -> {
                                        if (parser.currentToken != JsonToken.START_OBJECT) {
                                            parser.skipChildren()
                                            continue
                                        }
                                        while (parser.nextToken() != JsonToken.END_OBJECT) {
                                            val stateFieldName = parser.currentName() ?: continue
                                            parser.nextToken()
                                            when (stateFieldName) {
                                                OUTBOUND_FIELD -> outboundPeers = parser.intValueOrNull()
                                                INBOUND_FIELD -> inboundPeers = parser.intValueOrNull()
                                                else -> parser.skipChildren()
                                            }
                                        }
                                    }

                                    else -> parser.skipChildren()
                                }
                            }
                        }

                        else -> parser.skipChildren()
                    }
                }

                ParsedTraceObject(
                    namespace = namespace,
                    at = at,
                    host = host,
                    kind = kind,
                    slot = slot,
                    blockHash = blockHash,
                    block = block,
                    addedToChainSlot = addedToChainSlot,
                    addedToChainBlockHeight = addedToChainBlockHeight,
                    outboundPeers = outboundPeers,
                    inboundPeers = inboundPeers,
                )
            }
        }.getOrNull()

    private fun ParsedTraceObject.toNodeState(): ForwardedNodeState? =
        when {
            namespace == ADDED_TO_CURRENT_CHAIN_NAMESPACE && kind == ADDED_TO_CURRENT_CHAIN_KIND ->
                ForwardedNodeState(
                    slot = addedToChainSlot ?: return null,
                    blockHeight = addedToChainBlockHeight ?: return null,
                )

            namespace == CONNECTION_COUNTERS_NAMESPACE && kind == CONNECTION_COUNTERS_KIND ->
                ForwardedNodeState(
                    peers = outboundPeers,
                    incomingPeers = inboundPeers,
                )

            else -> null
        }

    private fun ParsedTraceObject.toBlockEvent(): ForwardedBlockEvent? {
        val decoded =
            when {
                namespace in ADOPTED_BLOCK_NAMESPACES && kind == ADOPTED_BLOCK_KIND ->
                    DecodedBlockEvent(status = "completed", blockHash = blockHash ?: return null)

                namespace in FORGED_BLOCK_NAMESPACES && kind == FORGED_BLOCK_KIND ->
                    DecodedBlockEvent(status = "completed", blockHash = block ?: return null)

                else -> return null
            }

        return ForwardedBlockEvent(
            slot = slot ?: return null,
            blockHash = decoded.blockHash,
            timestamp = at ?: return null,
            hostname = host ?: return null,
            status = decoded.status,
        )
    }

    private data class ParsedTraceObject(
        val namespace: String?,
        val at: String?,
        val host: String?,
        val kind: String?,
        val slot: Long?,
        val blockHash: String?,
        val block: String?,
        val addedToChainSlot: Long?,
        val addedToChainBlockHeight: Long?,
        val outboundPeers: Int?,
        val inboundPeers: Int?,
    )

    private data class DecodedBlockEvent(
        val status: String,
        val blockHash: String,
    )

    private companion object {
        val jsonFactory: JsonFactory = JsonFactory()
        const val NS_FIELD = "ns"
        const val DATA_FIELD = "data"
        const val KIND_FIELD = "kind"
        const val SLOT_FIELD = "slot"
        const val BLOCK_HASH_FIELD = "blockHash"
        const val BLOCK_FIELD = "block"
        const val AT_FIELD = "at"
        const val HOST_FIELD = "host"
        const val NEW_SUFFIX_SELECT_VIEW_FIELD = "newSuffixSelectView"
        const val SLOT_NO_FIELD = "slotNo"
        const val BLOCK_NO_FIELD = "blockNo"
        const val STATE_FIELD = "state"
        const val OUTBOUND_FIELD = "outbound"
        const val INBOUND_FIELD = "inbound"
        const val MACHINE_JSON_INDEX = 2
        val ADOPTED_BLOCK_NAMESPACES =
            setOf(
                "Forge.AdoptedBlock",
                "Forge.Loop.AdoptedBlock",
            )
        const val ADOPTED_BLOCK_KIND = "TraceAdoptedBlock"
        val FORGED_BLOCK_NAMESPACES =
            setOf(
                "Forge.ForgedBlock",
                "Forge.Loop.ForgedBlock",
            )
        const val FORGED_BLOCK_KIND = "TraceForgedBlock"
        const val ADDED_TO_CURRENT_CHAIN_NAMESPACE = "ChainDB.AddBlockEvent.AddedToCurrentChain"
        const val ADDED_TO_CURRENT_CHAIN_KIND = "AddedToCurrentChain"
        const val CONNECTION_COUNTERS_NAMESPACE = "Net.ConnectionManager.Remote.ConnectionManagerCounters"
        const val CONNECTION_COUNTERS_KIND = "ConnectionManagerCounters"
    }
}

private fun com.fasterxml.jackson.core.JsonParser.longValueOrNull(): Long? =
    when (currentToken()) {
        JsonToken.VALUE_NUMBER_INT -> longValue
        JsonToken.VALUE_STRING -> valueAsString?.toLongOrNull()
        else -> null
    }

private fun com.fasterxml.jackson.core.JsonParser.intValueOrNull(): Int? =
    when (currentToken()) {
        JsonToken.VALUE_NUMBER_INT -> valueAsInt
        JsonToken.VALUE_STRING -> valueAsString?.toIntOrNull()
        else -> null
    }
