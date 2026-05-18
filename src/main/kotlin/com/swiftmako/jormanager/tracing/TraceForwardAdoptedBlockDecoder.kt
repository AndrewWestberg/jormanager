package com.swiftmako.jormanager.tracing

data class ForwardedBlockEvent(
    val slot: Long,
    val blockHash: String,
    val timestamp: String,
    val hostname: String,
    val status: String,
)

class TraceForwardAdoptedBlockDecoder(
    private val extractor: TraceForwardProtocol2Extractor = TraceForwardProtocol2Extractor(),
) {
    fun decode(reply: TraceForwardMessage.TraceObjectsReply): List<ForwardedBlockEvent> = extractor.decodeBlockEvents(reply)

    fun decode(traceObjectJson: String): ForwardedBlockEvent? = extractor.decodeBlockEvent(traceObjectJson)
}
