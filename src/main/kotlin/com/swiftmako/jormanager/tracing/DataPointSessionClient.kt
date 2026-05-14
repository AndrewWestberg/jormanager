package com.swiftmako.jormanager.tracing

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborReader
import com.google.iot.cbor.CborTextString
import com.google.iot.cbor.CborWriter
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

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
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
) : DataPointSessionClient {
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
                val output = socket.getOutputStream()
                output.apply {
                    write(buildDataPointsRequest(requestedNames))
                    flush()
                }

                val input = socket.getInputStream()
                while (true) {
                    try {
                        when (val message = readMessage(input)) {
                            is TraceForwardMessage.DataPointsReply -> {
                                onMessage(message)
                                output.write(buildDoneMessage())
                                output.flush()
                                return
                            }

                            TraceForwardMessage.Done ->
                                throw IOException("Unexpected done reply on data-point session")

                            is TraceForwardMessage.TraceObjectsReply ->
                                throw IOException("Unexpected trace-objects reply on data-point session")
                        }
                    } catch (_: SocketTimeoutException) {
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
            ?: throw IOException("Expected data-point CBOR array message")
        val messageId = (payload.elementAt(0) as? CborInteger)?.longValue()
            ?: throw IOException("Expected data-point message identifier")

        return when (messageId) {
            MSG_DATA_POINTS_REPLY_ID -> {
                val dataPoints = payload.elementAt(1) as? CborArray
                    ?: throw IOException("Expected data points array payload")
                TraceForwardMessage.DataPointsReply(dataPoints)
            }

            MSG_DONE_ID -> TraceForwardMessage.Done
            else -> throw IOException("Unexpected data-point message id: $messageId")
        }
    }

    private fun buildDataPointsRequest(requestedNames: List<String>): ByteArray {
        val payload =
            CborArray.create().apply {
                add(CborInteger.create(MSG_DATA_POINTS_REQUEST_ID))
                add(
                    CborArray.create().apply {
                        requestedNames.forEach { add(CborTextString.create(it)) }
                    }
                )
            }
        val buffer = ByteBuffer.allocate(512)
        CborWriter.createFromByteBuffer(buffer).writeDataItem(payload)
        buffer.flip()
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun buildDoneMessage(): ByteArray {
        val payload =
            CborArray.create().apply {
                add(CborInteger.create(MSG_DONE_ID))
            }
        val buffer = ByteBuffer.allocate(16)
        CborWriter.createFromByteBuffer(buffer).writeDataItem(payload)
        buffer.flip()
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000
        private const val DEFAULT_READ_TIMEOUT_MILLIS = 5_000
        private const val MSG_DATA_POINTS_REQUEST_ID = 1L
        private const val MSG_DONE_ID = 2L
        private const val MSG_DATA_POINTS_REPLY_ID = 3L
    }
}
