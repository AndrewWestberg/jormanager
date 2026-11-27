package com.swiftmako.jormanager.tracing

import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import com.swiftmako.jormanager.nodeclient.protocols.mux.Mux
import com.swiftmako.jormanager.tracing.forwarding.ForwardingDataPointsProtocol
import com.swiftmako.jormanager.tracing.forwarding.ForwardingHandshakeProtocol
import com.swiftmako.jormanager.tracing.forwarding.ForwardingMetricsProtocol
import com.swiftmako.jormanager.tracing.forwarding.ForwardingMetricsRequest
import com.swiftmako.jormanager.tracing.forwarding.ForwardingTraceObjectsProtocol
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.connection
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun interface TraceForwardConnectionRunner {
    fun runConnection(
        hostname: String,
        port: Int,
    ): Flow<TraceForwardMessage>
}

fun interface TraceForwardConnectionRunnerFactory {
    fun create(target: TracingTransportTarget): TraceForwardConnectionRunner
}

class SocketTraceForwardConnectionRunnerFactory : TraceForwardConnectionRunnerFactory {
    override fun create(target: TracingTransportTarget): TraceForwardConnectionRunner =
        SocketTraceForwardConnectionRunner(
            networkMagic = target.networkMagic,
            enableTraceObjects = target.enableTraceObjects,
        )
}

data class TracingTransportTarget(
    val networkMagic: Long,
    val enableTraceObjects: Boolean,
)

internal data class ProtocolStream(
    val protocol: MiniProtocol,
    val messages: Flow<TraceForwardMessage>,
    val closeMessages: () -> Unit = {},
)

class SocketTraceForwardConnectionRunner internal constructor(
    private val networkMagic: Long,
    private val selectorFactory: () -> SelectorManager,
    private val createProtocolStreams: () -> List<ProtocolStream>,
) : TraceForwardConnectionRunner {
    constructor(
        requestedDataPointNames: List<String> = NodeStateDataPointDecoder.REQUESTED_NAMES,
        metricsRequest: ForwardingMetricsRequest = ForwardingMetricsRequest.GetMetrics(TracingMetricDecoder.DASHBOARD_REQUEST_NAMES),
        networkMagic: Long,
        enableTraceObjects: Boolean = true,
        metricsPollInterval: Duration = DEFAULT_METRICS_POLL_INTERVAL,
        dataPointPollInterval: Duration = DEFAULT_DATA_POINTS_POLL_INTERVAL,
        requestBlocking: Boolean = true,
        requestCount: Int = DEFAULT_REQUEST_COUNT,
    ) : this(
        networkMagic = networkMagic,
        selectorFactory = { ActorSelectorManager(Dispatchers.IO) },
        createProtocolStreams = {
            val metricsProtocol =
                ForwardingMetricsProtocol(
                    request = metricsRequest,
                    singleReplyMode = false,
                    pollInterval = metricsPollInterval,
                )
            val traceObjectsProtocol =
                if (enableTraceObjects) {
                    ForwardingTraceObjectsProtocol(
                        requestBlocking = requestBlocking,
                        requestCount = requestCount,
                        singleReplyMode = false,
                    )
                } else {
                    null
                }
            val dataPointsProtocol =
                ForwardingDataPointsProtocol(
                    requestedNames = requestedDataPointNames,
                    singleReplyMode = false,
                    pollInterval = dataPointPollInterval,
                )
            buildList {
                add(
                    ProtocolStream(
                        protocol = metricsProtocol,
                        messages = metricsProtocol.messages,
                        closeMessages = metricsProtocol::closeMessages,
                    ),
                )
                traceObjectsProtocol?.let { protocol ->
                    add(
                        ProtocolStream(
                            protocol = protocol,
                            messages = protocol.messages,
                            closeMessages = protocol::closeMessages,
                        ),
                    )
                }
                add(
                    ProtocolStream(
                        protocol = dataPointsProtocol,
                        messages = dataPointsProtocol.messages,
                        closeMessages = dataPointsProtocol::closeMessages,
                    ),
                )
            }
        },
    )

    override fun runConnection(
        hostname: String,
        port: Int,
    ): Flow<TraceForwardMessage> =
        channelFlow {
            selectorFactory().use { selector ->
                aSocket(selector)
                    .tcp()
                    .connect(InetSocketAddress(hostname, port)) {
                        noDelay = true
                        keepAlive = true
                    }.use { socket ->
                        val mux = Mux(socket.connection())
                        mux.execute(ForwardingHandshakeProtocol(networkMagic))

                        val protocolStreams = createProtocolStreams()
                        val protocols = protocolStreams.map { it.protocol }.toTypedArray()
                        val mergedMessages =
                            merge(*protocolStreams.map { it.messages }.toTypedArray())
                                .buffer(Channel.RENDEZVOUS)

                        val collectorJob =
                            launch(start = CoroutineStart.UNDISPATCHED) {
                                mergedMessages.collect { message ->
                                    send(message)
                                }
                            }
                        try {
                            mux.execute(*protocols)
                        } finally {
                            mux.shutdownGracefully()
                            protocolStreams.forEach { it.closeMessages() }
                            if (currentCoroutineContext().isActive) {
                                collectorJob.join()
                            } else {
                                collectorJob.cancel()
                            }
                        }
                    }
            }
        }.buffer(Channel.RENDEZVOUS)

    companion object {
        internal const val DEFAULT_REQUEST_COUNT = 25
        internal val DEFAULT_METRICS_POLL_INTERVAL: Duration = 5.seconds
        internal val DEFAULT_DATA_POINTS_POLL_INTERVAL: Duration = 5.seconds
    }
}
