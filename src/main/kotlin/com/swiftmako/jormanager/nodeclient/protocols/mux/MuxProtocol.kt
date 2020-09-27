package com.swiftmako.jormanager.nodeclient.protocols.mux

import com.google.iot.cbor.CborReader
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import com.swiftmako.jormanager.nodeclient.protocols.chainsync.ChainSyncProtocol
import com.swiftmako.jormanager.nodeclient.protocols.handshake.HandshakeProtocol
import com.swiftmako.jormanager.nodeclient.protocols.transaction.TxSubmissionProtocol
import com.swiftmako.jormanager.nodeclient.utils.BufferPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.nio.aConnect
import kotlinx.coroutines.nio.aRead
import kotlinx.coroutines.nio.aWrite
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.internal.ignoreIoExceptions
import okhttp3.internal.toHexString
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

    /**
     * Ensures we don't try to send two things at the same time.
     */
    private val sendMutex = Mutex()


    fun start(): Job = launch {
        log.info("Starting MuxProtocol...")
        while (true) {
            val asyncSocketChannel = AsynchronousSocketChannel.open()
            try {
                asyncSocketChannel.aConnect(InetSocketAddress(hostName, port))

                // Start the handshake protocol
                val handshakeProtocol = HandshakeProtocol(networkMagic)
                launchProtocolSender(handshakeProtocol, asyncSocketChannel)

                val txSubmissionProtocol = TxSubmissionProtocol()
                launchProtocolSender(txSubmissionProtocol, asyncSocketChannel)

                val chainSyncProtocol = ChainSyncProtocol()
                launchProtocolSender(chainSyncProtocol, asyncSocketChannel)

                // Start the socket receiver loop
                val receiverLoop = launch {
                    try {
                        while (true) {
                            val receiveBuffer = BufferPool.borrow()
                            receiveBuffer.limit(8)
                            //log.debug("ready to read: position(): ${receiveBuffer.position()}, limit(): ${receiveBuffer.limit()}, remaining(): ${receiveBuffer.remaining()}, capacity(): ${receiveBuffer.capacity()}")
                            var bytesReceived = 0
                            while (bytesReceived < 8) {
                                val byteCnt = asyncSocketChannel.aRead(receiveBuffer)
                                //log.debug("read socket bytes: $byteCnt")
                                if (byteCnt <= 0) {
                                    BufferPool.recycle(receiveBuffer)
                                    throw IOException("Unexpected end of stream!")
                                }
                                bytesReceived += byteCnt
                            }
                            receiveBuffer.flip()
                            val timestamp = receiveBuffer.int
                            val protocolId = receiveBuffer.short
                            //log.debug("rawProtocolId: $protocolId")
                            val payloadLength = receiveBuffer.short.toInt()
                            //log.debug("Received Msg: timestamp: 0x${timestamp.toHexString().padStart(8, '0')}, protocolId: 0x${protocolId.toInt().toHexString().padStart(4, '0').substring(4)}, payloadLength: $payloadLength")
                            receiveBuffer.flip()
                            receiveBuffer.limit(receiveBuffer.position() + payloadLength)
                            bytesReceived = 0
                            while (bytesReceived < payloadLength) {
                                val byteCnt = asyncSocketChannel.aRead(receiveBuffer)
                                //log.debug("read socket bytes: $byteCnt")
                                if (byteCnt < 0) {
                                    BufferPool.recycle(receiveBuffer)
                                    throw IOException("Unexpected end of stream!")
                                }
                                bytesReceived += byteCnt
                            }
                            receiveBuffer.flip()
                            when (protocolId xor 0x8000.toShort()) {
                                handshakeProtocol.protocolId -> {
                                    handshakeProtocol.rxChannel.send(receiveBuffer)
                                }
                                txSubmissionProtocol.protocolId -> {
                                    txSubmissionProtocol.rxChannel.send(receiveBuffer)
                                }
                                chainSyncProtocol.protocolId -> {
                                    chainSyncProtocol.rxChannel.send(receiveBuffer)
                                }

                                else -> {
                                    log.error("Unknown message received: protocolId: 0x${protocolId.toInt().toHexString().padStart(4, '0').substring(4)}")
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
                txSubmissionProtocol.start()
                chainSyncProtocol.start()

                receiverLoop.join()
            } catch (e: Throwable) {
                log.error("Fatal Protocol Exception!", e)
            }

            ignoreIoExceptions {
                asyncSocketChannel.close()
            }

            // during development, just break out
            break
            //delay(5000) // wait 5 seconds before trying again
        }
    }

    private fun launchProtocolSender(protocol: MiniProtocol, asyncSocketChannel: AsynchronousSocketChannel) {
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
                        sendMutex.withLock {
                            asyncSocketChannel.aWrite(sendBuffer)
                        }
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
