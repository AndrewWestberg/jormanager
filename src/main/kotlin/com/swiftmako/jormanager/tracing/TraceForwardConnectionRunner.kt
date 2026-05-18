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
    fun create(): TraceForwardConnectionRunner
}

class SocketTraceForwardConnectionRunnerFactory : TraceForwardConnectionRunnerFactory {
    override fun create(): TraceForwardConnectionRunner = SocketTraceForwardConnectionRunner()
}

class SocketTraceForwardConnectionRunner(
    private val requestedDataPointNames: List<String> = NodeStateDataPointDecoder.REQUESTED_NAMES,
    private val metricsRequest: ForwardingMetricsRequest = ForwardingMetricsRequest.GetAllMetrics,
    private val networkMagic: Long = DEFAULT_NETWORK_MAGIC,
    private val requestBlocking: Boolean = true,
    private val requestCount: Int = DEFAULT_REQUEST_COUNT,
) : TraceForwardConnectionRunner {
    private val activeMux = AtomicReference<Mux?>(null)

    override suspend fun runConnection(
        hostname: String,
        port: Int,
        onMessage: suspend (TraceForwardMessage) -> Unit,
    ) {
        aSocket(ActorSelectorManager(Dispatchers.IO))
            .tcp()
            .connect(InetSocketAddress(hostname, port)) {
                noDelay = true
                keepAlive = true
            }.use { socket ->
                val mux = Mux(socket.connection())
                activeMux.set(mux)
                val metricsProtocol = ForwardingMetricsProtocol(metricsRequest)
                val protocol = ForwardingTraceObjectsProtocol(
                    requestBlocking = requestBlocking,
                    requestCount = requestCount,
                    singleReplyMode = false,
                )
                val dataPointsProtocol = ForwardingDataPointsProtocol(requestedDataPointNames, singleReplyMode = false)
                val metricsCollector = metricsProtocol.messages.collectInBackground(onMessage)
                val collector = protocol.messages.collectInBackground(onMessage)
                val dataPointCollector = dataPointsProtocol.messages.collectInBackground(onMessage)
                try {
                    mux.execute(ForwardingHandshakeProtocol(networkMagic))
                    mux.execute(metricsProtocol, protocol, dataPointsProtocol)
                } finally {
                    metricsCollector.cancelAndJoin()
                    collector.cancelAndJoin()
                    dataPointCollector.cancelAndJoin()
                    activeMux.compareAndSet(mux, null)
                }
            }
    }

    override fun close() {
        activeMux.getAndSet(null)?.shutdownGracefully()
    }

    companion object {
        internal const val DEFAULT_REQUEST_COUNT = 25
        private const val DEFAULT_NETWORK_MAGIC = 141L
    }
}

private fun Flow<TraceForwardMessage>.collectInBackground(
    onMessage: suspend (TraceForwardMessage) -> Unit,
): Job =
    CoroutineScope(Dispatchers.IO).launch {
        collect { onMessage(it) }
    }
