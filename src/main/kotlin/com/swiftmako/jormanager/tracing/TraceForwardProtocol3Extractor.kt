package com.swiftmako.jormanager.tracing

data class Protocol3DataPoints(
    val nodeStateMetrics: NodeStateMetrics?,
    val startupInfo: NodeStartupInfo?,
)

class TraceForwardProtocol3Extractor(
    private val nodeStateDataPointDecoder: NodeStateDataPointDecoder = NodeStateDataPointDecoder(),
) {
    fun decode(reply: TraceForwardMessage.DataPointsReply): Protocol3DataPoints? {
        val values = reply.dataPoints.toDataPointValueMap() ?: return null
        val nodeStateMetrics = nodeStateDataPointDecoder.decodeValues(values)
        val startupInfo = values.parseNodeStartupInfo()
        return Protocol3DataPoints(
            nodeStateMetrics = nodeStateMetrics,
            startupInfo = startupInfo,
        ).takeIf { it.nodeStateMetrics != null || it.startupInfo != null }
    }

    fun decode(snapshot: TracingRawDataPointSnapshot): Protocol3DataPoints? = decode(snapshot.toMessage())

    fun decodeNodeStateMetrics(snapshot: TracingRawDataPointSnapshot): NodeStateMetrics? = decode(snapshot)?.nodeStateMetrics

    fun decodeStartupInfo(snapshot: TracingRawDataPointSnapshot): NodeStartupInfo? = decode(snapshot)?.startupInfo
}
