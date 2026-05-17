package com.swiftmako.jormanager.tracing

import com.swiftmako.jormanager.nodeclient.protocols.mux.Mux
import com.swiftmako.jormanager.tracing.forwarding.ForwardingDataPointsProtocol
import com.swiftmako.jormanager.tracing.forwarding.ForwardingHandshakeProtocol
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

interface DataPointSessionClient {
    suspend fun runSession(
        hostname: String,
        port: Int,
        onMessage: suspend (TraceForwardMessage) -> Unit,
    )

    fun close()
}

fun interface DataPointSessionClientFactory {
    fun create(): DataPointSessionClient
}

class SocketDataPointSessionClientFactory : DataPointSessionClientFactory {
    override fun create(): DataPointSessionClient = SocketDataPointSessionClient()
}

class SocketDataPointSessionClient(
    private val requestedNames: List<String> = NodeStateDataPointDecoder.REQUESTED_NAMES,
    private val networkMagic: Long = DEFAULT_NETWORK_MAGIC,
) : DataPointSessionClient {
    private val activeMux = AtomicReference<Mux?>(null)

    override suspend fun runSession(
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
                val protocol = ForwardingDataPointsProtocol(requestedNames, singleReplyMode = true)
                val collector = protocol.messages.collectInBackground(onMessage)
                try {
                    mux.execute(ForwardingHandshakeProtocol(networkMagic))
                    mux.execute(protocol)
                } finally {
                    collector.cancelAndJoin()
                    activeMux.compareAndSet(mux, null)
                }
            }
    }

    override fun close() {
        activeMux.getAndSet(null)?.shutdownGracefully()
    }

    companion object {
        private const val DEFAULT_NETWORK_MAGIC = 141L
    }
}

private fun Flow<TraceForwardMessage>.collectInBackground(
    onMessage: suspend (TraceForwardMessage) -> Unit,
): Job =
    CoroutineScope(Dispatchers.IO).launch {
        collect { onMessage(it) }
    }
