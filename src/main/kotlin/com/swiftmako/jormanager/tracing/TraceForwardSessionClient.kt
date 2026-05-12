package com.swiftmako.jormanager.tracing

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborReader
import com.google.iot.cbor.CborSimple
import com.google.iot.cbor.CborWriter
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

interface TraceForwardSessionClient {
    suspend fun runSession(
        hostname: String,
        port: Int,
        onMessage: suspend (TraceForwardMessage) -> Unit,
    )

    fun close()
}

fun interface TraceForwardSessionClientFactory {
    fun create(): TraceForwardSessionClient
}

class SocketTraceForwardSessionClientFactory : TraceForwardSessionClientFactory {
    override fun create(): TraceForwardSessionClient = SocketTraceForwardSessionClient()
}

class SocketTraceForwardSessionClient(
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val requestBlocking: Boolean = true,
    private val requestCount: Int = DEFAULT_REQUEST_COUNT,
) : TraceForwardSessionClient {
    private val activeSocket = AtomicReference<Socket?>(null)

    override suspend fun runSession(
        hostname: String,
        port: Int,
        onMessage: suspend (TraceForwardMessage) -> Unit,
    ) {
        Socket().apply {
            tcpNoDelay = true
            keepAlive = true
            soTimeout = readTimeoutMillis
            connect(InetSocketAddress(hostname, port), connectTimeoutMillis)
        }.use { socket ->
            activeSocket.set(socket)
            try {
                socket.getOutputStream().apply {
                    write(buildTraceObjectsRequest(requestBlocking, requestCount))
                    flush()
                }

                val input = socket.getInputStream()
                while (true) {
                    try {
                        when (val message = readMessage(input)) {
                            is TraceForwardMessage.TraceObjectsReply -> onMessage(message)
                            TraceForwardMessage.Done -> {
                                onMessage(TraceForwardMessage.Done)
                                return
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // Blocking trace-forward sessions can stay quiet for long periods.
                        continue
                    }
                }
            } finally {
                activeSocket.compareAndSet(socket, null)
            }
        }
    }

    override fun close() {
        activeSocket.getAndSet(null)?.runCatching(Socket::close)
    }

    private fun readMessage(input: java.io.InputStream): TraceForwardMessage {
        val payload = CborReader.createFromInputStream(input).readDataItem() as? CborArray
            ?: throw IOException("Expected trace-forward CBOR array message")
        val messageId = (payload.elementAt(0) as? CborInteger)?.longValue()
            ?: throw IOException("Expected trace-forward message identifier")

        return when (messageId) {
            MSG_TRACE_OBJECTS_REPLY_ID -> {
                val traceObjects = payload.elementAt(1) as? CborArray
                    ?: throw IOException("Expected trace objects array payload")
                TraceForwardMessage.TraceObjectsReply(traceObjects)
            }

            MSG_DONE_ID -> TraceForwardMessage.Done
            else -> throw IOException("Unexpected trace-forward message id: $messageId")
        }
    }

    private fun buildTraceObjectsRequest(
        blocking: Boolean,
        count: Int,
    ): ByteArray {
        val payload =
            CborArray.create().apply {
                add(CborInteger.create(MSG_TRACE_OBJECTS_REQUEST_ID))
                add(if (blocking) CborSimple.TRUE else CborSimple.FALSE)
                add(
                    CborArray.create().apply {
                        add(CborInteger.create(BLOCKING_REQUEST_ID))
                        add(CborInteger.create(count.toLong()))
                    }
                )
            }
        val buffer = ByteBuffer.allocate(64)
        CborWriter.createFromByteBuffer(buffer).writeDataItem(payload)
        buffer.flip()
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000
        private const val DEFAULT_READ_TIMEOUT_MILLIS = 5_000
        internal const val DEFAULT_REQUEST_COUNT = 25
        private const val MSG_TRACE_OBJECTS_REQUEST_ID = 1L
        private const val MSG_DONE_ID = 2L
        private const val MSG_TRACE_OBJECTS_REPLY_ID = 3L
        private const val BLOCKING_REQUEST_ID = 0L
    }
}
