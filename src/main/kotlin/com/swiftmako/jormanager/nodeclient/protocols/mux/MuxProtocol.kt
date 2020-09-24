package com.swiftmako.jormanager.nodeclient.protocols.mux

import com.swiftmako.jormanager.nodeclient.protocols.handshake.HandshakeProtocol
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
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousSocketChannel
import kotlin.coroutines.CoroutineContext

class MuxProtocol(private val hostName: String, private val port: Int, private val networkMagic: Long) : CoroutineScope {
    private val log = LoggerFactory.getLogger("MuxProtocol")

    override val coroutineContext: CoroutineContext = Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            log.error("Uncaught coroutine exception!", throwable)
        }
    }

    fun start(): Job = launch {
        try {
            val asyncSocketChannel = AsynchronousSocketChannel.open()
            asyncSocketChannel.aConnect(InetSocketAddress(hostName, port))

            // Start the handshake protocol
            val handshakeProtocol = HandshakeProtocol(networkMagic)
            launch {
                handshakeProtocol.txChannel.consumeEach { byteBuffer ->
                    val sendBuffer = BufferPool.borrow()
                    try {
                        sendBuffer.putInt(timestampNow)
                        sendBuffer.putShort(handshakeProtocol.protocolId)
                        sendBuffer.putShort(byteBuffer.remaining().toShort())
                        sendBuffer.put(byteBuffer)
                        BufferPool.recycle(byteBuffer)
                        sendBuffer.flip()
                        asyncSocketChannel.aWrite(sendBuffer)
                    } finally {
                        BufferPool.recycle(sendBuffer)
                    }
                }
            }
            launch {
                val receiveBuffer = BufferPool.borrow()
                try {
                    //todo read in exactly one message here, then loop
                    receiveBuffer.limit(8)
                    asyncSocketChannel.aRead(receiveBuffer)
                    receiveBuffer.flip()
                    // check if we have at least a full message here
                } finally {
                    BufferPool.recycle(receiveBuffer)
                }
            }
            handshakeProtocol.start()
        } catch (e: Throwable) {
            log.error("Fatal Protocol Exception!", e)
        }
    }

    val timestampNow: Int
        get() = (System.nanoTime() / 1000).toInt()
}
