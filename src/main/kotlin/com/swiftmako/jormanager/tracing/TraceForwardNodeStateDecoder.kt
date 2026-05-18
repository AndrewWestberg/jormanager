package com.swiftmako.jormanager.tracing

data class ForwardedNodeState(
    val slot: Long? = null,
    val blockHeight: Long? = null,
    val peers: Int? = null,
    val incomingPeers: Int? = null,
) {
    fun merge(other: ForwardedNodeState): ForwardedNodeState =
        ForwardedNodeState(
            slot = other.slot ?: slot,
            blockHeight = other.blockHeight ?: blockHeight,
            peers = other.peers ?: peers,
            incomingPeers = other.incomingPeers ?: incomingPeers,
        )
}

class TraceForwardNodeStateDecoder(
    private val extractor: TraceForwardProtocol2Extractor = TraceForwardProtocol2Extractor(),
) {
    fun decode(reply: TraceForwardMessage.TraceObjectsReply): ForwardedNodeState? = extractor.decodeNodeState(reply)

    fun decode(traceObjectJson: String): ForwardedNodeState? = extractor.decodeNodeState(traceObjectJson)
}
