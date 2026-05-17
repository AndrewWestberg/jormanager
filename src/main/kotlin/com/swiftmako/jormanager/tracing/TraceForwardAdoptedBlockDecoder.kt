package com.swiftmako.jormanager.tracing

import com.google.iot.cbor.CborByteString
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborObject
import com.google.iot.cbor.CborTextString
import org.json.JSONArray
import org.json.JSONObject

data class ForwardedBlockEvent(
    val slot: Long,
    val blockHash: String,
    val timestamp: String,
    val hostname: String,
    val status: String,
)

class TraceForwardAdoptedBlockDecoder {
    fun decode(reply: TraceForwardMessage.TraceObjectsReply): List<ForwardedBlockEvent> =
        reply.traceObjectsJson().mapNotNull(::decode)

    fun decode(traceObjectJson: String): ForwardedBlockEvent? =
        runCatching {
            val traceObject = JSONObject(traceObjectJson)
            val machine = traceObject.machineObject() ?: return null
            val status =
                when {
                    traceObject.namespace() == ADOPTED_BLOCK_NAMESPACE && machine.optString(KIND_FIELD) == ADOPTED_BLOCK_KIND -> "completed"
                    traceObject.namespace() == FORGED_BLOCK_NAMESPACE && machine.optString(KIND_FIELD) == FORGED_BLOCK_KIND -> "created"
                    else -> return null
                }

            val blockHash =
                machine.optNonBlankString(BLOCK_HASH_FIELD)
                    ?: machine.optNonBlankString(BLOCK_FIELD)
                    ?: return null

            val slot = machine.optLongOrNull(SLOT_FIELD) ?: return null
            val timestamp = traceObject.optNonBlankString(TIMESTAMP_FIELD) ?: return null
            val hostname = traceObject.optNonBlankString(HOSTNAME_FIELD) ?: return null

            ForwardedBlockEvent(
                slot = slot,
                blockHash = blockHash,
                timestamp = timestamp,
                hostname = hostname,
                status = status,
            )
        }.getOrNull()

    private fun TraceForwardMessage.TraceObjectsReply.traceObjectsJson(): List<String> =
        buildList(replySize()) {
            for (index in 0 until replySize()) {
                traceObjectJsonAt(index)?.let(::add)
            }
        }

    private fun TraceForwardMessage.TraceObjectsReply.replySize(): Int = traceObjects.size()

    private fun TraceForwardMessage.TraceObjectsReply.traceObjectJsonAt(index: Int): String? =
        traceObjects.elementAt(index).toTraceObjectJsonOrNull()

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
            is JSONArray -> null
            else -> null
        }

    private fun JSONObject.optNonBlankString(
        fieldName: String,
        fallback: JSONObject? = null,
    ): String? {
        val value = optString(fieldName, null)
        if (!value.isNullOrBlank()) {
            return value
        }
        return fallback?.optString(fieldName, null)?.takeUnless(String::isNullOrBlank)
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

    private companion object {
        val ADOPTED_BLOCK_NAMESPACE = listOf("Forge", "AdoptedBlock")
        const val ADOPTED_BLOCK_KIND = "TraceAdoptedBlock"
        val FORGED_BLOCK_NAMESPACE = listOf("Forge", "ForgedBlock")
        const val FORGED_BLOCK_KIND = "TraceForgedBlock"
        const val NAMESPACE_FIELD = "toNamespace"
        const val MACHINE_FIELD = "toMachine"
        const val KIND_FIELD = "kind"
        const val SLOT_FIELD = "slot"
        const val BLOCK_HASH_FIELD = "blockHash"
        const val BLOCK_FIELD = "block"
        const val TIMESTAMP_FIELD = "toTimestamp"
        const val HOSTNAME_FIELD = "toHostname"
        const val MACHINE_JSON_INDEX = 2
    }
}
