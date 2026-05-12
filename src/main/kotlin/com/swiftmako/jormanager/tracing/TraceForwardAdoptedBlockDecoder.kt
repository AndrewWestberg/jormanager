package com.swiftmako.jormanager.tracing

import com.google.iot.cbor.CborByteString
import com.google.iot.cbor.CborObject
import com.google.iot.cbor.CborTextString
import org.json.JSONArray
import org.json.JSONObject

data class ForwardedAdoptedBlockEvent(
    val slot: Long,
    val blockHash: String,
    val timestamp: String,
    val hostname: String,
)

class TraceForwardAdoptedBlockDecoder {
    fun decode(reply: TraceForwardMessage.TraceObjectsReply): List<ForwardedAdoptedBlockEvent> =
        reply.traceObjectsJson().mapNotNull(::decode)

    fun decode(traceObjectJson: String): ForwardedAdoptedBlockEvent? =
        runCatching {
            val traceObject = JSONObject(traceObjectJson)
            if (traceObject.namespace() != ADOPTED_BLOCK_NAMESPACE) {
                return null
            }

            val machine = traceObject.machineObject() ?: return null
            if (machine.optString(KIND_FIELD) != ADOPTED_BLOCK_KIND) {
                return null
            }

            val slot = machine.optLongOrNull(SLOT_FIELD) ?: return null
            val blockHash = machine.optNonBlankString(BLOCK_HASH_FIELD) ?: return null
            val timestamp = traceObject.optNonBlankString(TIMESTAMP_FIELD) ?: return null
            val hostname = traceObject.optNonBlankString(HOSTNAME_FIELD) ?: return null

            ForwardedAdoptedBlockEvent(
                slot = slot,
                blockHash = blockHash,
                timestamp = timestamp,
                hostname = hostname,
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
            is CborTextString -> stringValue()
            is CborByteString -> byteArrayValue()[0].decodeToString()
            else -> toJsonString()
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
        const val NAMESPACE_FIELD = "toNamespace"
        const val MACHINE_FIELD = "toMachine"
        const val KIND_FIELD = "kind"
        const val SLOT_FIELD = "slot"
        const val BLOCK_HASH_FIELD = "blockHash"
        const val TIMESTAMP_FIELD = "toTimestamp"
        const val HOSTNAME_FIELD = "toHostname"
    }
}
