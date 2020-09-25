package com.swiftmako.jormanager.nodeclient.protocols.mux

import com.google.iot.cbor.CborReader
import com.swiftmako.jormanager.nodeclient.protocols.handshake.HandshakeProtocol
import com.swiftmako.jormanager.nodeclient.utils.BufferPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.nio.aConnect
import kotlinx.coroutines.nio.aRead
import kotlinx.coroutines.nio.aWrite
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousSocketChannel
import kotlin.coroutines.CoroutineContext
import kotlin.experimental.xor

class MuxProtocol(private val hostName: String, private val port: Int, private val networkMagic: Long) : CoroutineScope {
    private val log = LoggerFactory.getLogger("MuxProtocol")

    override val coroutineContext: CoroutineContext = Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            log.error("Uncaught coroutine exception!", throwable)
        }
    }

    fun start(): Job = launch {
        log.info("Starting MuxProtocol...")
        while (true) {
            try {
                val asyncSocketChannel = AsynchronousSocketChannel.open()
                asyncSocketChannel.aConnect(InetSocketAddress(hostName, port))

                // Start the handshake protocol
                val handshakeProtocol = HandshakeProtocol(networkMagic)
                launchProtocolSender(handshakeProtocol, asyncSocketChannel)

                // Start the socket receiver loop
                launch {
                    try {
                        while (true) {
                            val receiveBuffer = BufferPool.borrow()
                            receiveBuffer.limit(8)
                            asyncSocketChannel.aRead(receiveBuffer)
                            receiveBuffer.flip()
                            val timestamp = receiveBuffer.int
                            val protocolId = receiveBuffer.short xor 0x8000.toShort() // clear the remote mode bit
                            val payloadLength = receiveBuffer.short.toInt()
                            log.debug("Received Msg: timestamp: 0x${timestamp.toString(16).padStart(4, '0')}, protocolId: 0x${protocolId.toString(16).padStart(4, '0')}, payloadLength: $payloadLength")
                            receiveBuffer.flip()
                            receiveBuffer.limit(receiveBuffer.position() + payloadLength)
                            val bytesReceived = asyncSocketChannel.aRead(receiveBuffer)
                            if (bytesReceived != payloadLength) {
                                BufferPool.recycle(receiveBuffer)
                                throw IOException("Expected $payloadLength bytes, but got $bytesReceived!")
                            }
                            receiveBuffer.flip()
                            when (protocolId) {
                                handshakeProtocol.protocolId -> {
                                    handshakeProtocol.rxChannel.send(receiveBuffer)
                                }
                                else -> {
                                    log.error("Unknown message received: protocolId: 0x${protocolId.toString(16).padStart(4, '0')}")
//                                    while (receiveBuffer.hasRemaining()) {
                                    val jsonString = CborReader.createFromByteArray(receiveBuffer.array(), receiveBuffer.position(), 1).readDataItem().toJsonString()
                                    log.error("Unknown data: $jsonString")
//                                    }
                                    BufferPool.recycle(receiveBuffer)
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        log.error("Error receiving data!", e)
                    }
                }
                handshakeProtocol.start()
            } catch (e: Throwable) {
                log.error("Fatal Protocol Exception!", e)
            }
            delay(5000) // wait 5 seconds before trying again
        }
    }

    private fun launchProtocolSender(protocol: HandshakeProtocol, asyncSocketChannel: AsynchronousSocketChannel) {
        launch {
            try {
                protocol.txChannel.consumeEach { byteBuffer ->
                    val sendBuffer = BufferPool.borrow()
                    try {
                        sendBuffer.putInt(timestampNow)
                        sendBuffer.putShort(protocol.protocolId)
                        sendBuffer.putShort(byteBuffer.remaining().toShort())
                        sendBuffer.put(byteBuffer)
                        BufferPool.recycle(byteBuffer)
                        sendBuffer.flip()
                        asyncSocketChannel.aWrite(sendBuffer)
                    } finally {
                        BufferPool.recycle(sendBuffer)
                    }
                }
            } catch (e: CancellationException) {
                // ignored
            } catch (e: Throwable) {
                log.error("Error sending data!", e)
            }
        }
    }

    val timestampNow: Int
        get() = (System.nanoTime() / 1000).toInt()
}
