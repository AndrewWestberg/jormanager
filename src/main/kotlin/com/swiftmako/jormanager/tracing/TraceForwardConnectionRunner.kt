package com.swiftmako.jormanager.tracing

import com.swiftmako.jormanager.nodeclient.protocols.mux.Mux
import com.swiftmako.jormanager.tracing.forwarding.ForwardingDataPointsProtocol
import com.swiftmako.jormanager.tracing.forwarding.ForwardingHandshakeProtocol
import com.swiftmako.jormanager.tracing.forwarding.ForwardingMetricsProtocol
import com.swiftmako.jormanager.tracing.forwarding.ForwardingMetricsRequest
import com.swiftmako.jormanager.tracing.forwarding.ForwardingTraceObjectsProtocol
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.connection
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

interface TraceForwardConnectionRunner {
    suspend fun runConnection(
        hostname: String,
        port: Int,
        onMessage: suspend (TraceForwardMessage) -> Unit,
    )

    fun close()
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

class SocketTraceForwardConnectionRunner(
    private val requestedDataPointNames: List<String> = NodeStateDataPointDecoder.REQUESTED_NAMES,
    private val metricsRequest: ForwardingMetricsRequest = ForwardingMetricsRequest.GetMetrics(TracingMetricDecoder.DASHBOARD_REQUEST_NAMES),
    private val networkMagic: Long,
    private val enableTraceObjects: Boolean = true,
    private val metricsPollInterval: Duration = DEFAULT_METRICS_POLL_INTERVAL,
    private val dataPointPollInterval: Duration = DEFAULT_DATA_POINTS_POLL_INTERVAL,
    private val requestBlocking: Boolean = true,
    private val requestCount: Int = DEFAULT_REQUEST_COUNT,
) : TraceForwardConnectionRunner {
    private val activeMux = AtomicReference<Mux?>(null)

    override suspend fun runConnection(
        hostname: String,
        port: Int,
        onMessage: suspend (TraceForwardMessage) -> Unit,
    ) {
        val selector = ActorSelectorManager(coroutineContext)
        try {
            aSocket(selector)
                .tcp()
                .connect(InetSocketAddress(hostname, port)) {
                    noDelay = true
                    keepAlive = true
                }.use { socket ->
                    val mux = Mux(socket.connection())
                    activeMux.set(mux)
                    val metricsProtocol =
                        ForwardingMetricsProtocol(
                            request = metricsRequest,
                            singleReplyMode = false,
                            pollInterval = metricsPollInterval,
                        )
                    val dataPointsProtocol =
                        ForwardingDataPointsProtocol(
                            requestedNames = requestedDataPointNames,
                            singleReplyMode = false,
                            pollInterval = dataPointPollInterval,
                        )
                    val metricsCollector = metricsProtocol.messages.collectInBackground(onMessage)
                    val dataPointCollector = dataPointsProtocol.messages.collectInBackground(onMessage)
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
                    val traceObjectCollector = traceObjectsProtocol?.messages?.collectInBackground(onMessage)
                    try {
                        mux.execute(ForwardingHandshakeProtocol(networkMagic))
                        val protocols = buildList {
                            add(metricsProtocol)
                            traceObjectsProtocol?.let(::add)
                            add(dataPointsProtocol)
                        }
                        mux.execute(*protocols.toTypedArray())
                    } finally {
                        metricsCollector.cancelAndJoin()
                        traceObjectCollector?.cancelAndJoin()
                        dataPointCollector.cancelAndJoin()
                        activeMux.compareAndSet(mux, null)
                    }
                }
        } finally {
            selector.close()
        }
    }

    override fun close() {
        activeMux.getAndSet(null)?.shutdownGracefully()
    }

    companion object {
        internal const val DEFAULT_REQUEST_COUNT = 25
        internal val DEFAULT_METRICS_POLL_INTERVAL: Duration = 5.seconds
        internal val DEFAULT_DATA_POINTS_POLL_INTERVAL: Duration = 5.seconds
    }
}

private fun Flow<TraceForwardMessage>.collectInBackground(
    onMessage: suspend (TraceForwardMessage) -> Unit,
): Job =
    CoroutineScope(Dispatchers.IO).launch {
        collect { onMessage(it) }
    }
