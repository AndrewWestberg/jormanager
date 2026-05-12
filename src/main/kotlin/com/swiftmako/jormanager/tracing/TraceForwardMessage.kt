package com.swiftmako.jormanager.tracing

import com.google.iot.cbor.CborArray

sealed interface TraceForwardMessage {
    data class TraceObjectsReply(
        val traceObjects: CborArray,
    ) : TraceForwardMessage

    data object Done : TraceForwardMessage
}

fun interface TraceForwardMessageSink {
    suspend fun onMessage(
        nodeId: Long,
        message: TraceForwardMessage,
    )
}
