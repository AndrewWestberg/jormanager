package com.swiftmako.jormanager.nodeclient

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.nio.aConnect
import okhttp3.internal.closeQuietly
import okhttp3.internal.ignoreIoExceptions
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import kotlin.coroutines.CoroutineContext

class NodeClient(private val hostName: String, private val port: Int) : Closeable, CoroutineScope {
    private val log = LoggerFactory.getLogger("NodeClient")

    override val coroutineContext: CoroutineContext = Dispatchers.IO

    private val txBuffer by lazy { ByteBuffer.allocateDirect(8192) }
    private val rxBuffer by lazy { ByteBuffer.allocateDirect(8192) }

    private lateinit var _socket :AsynchronousSocketChannel

    private val socket:AsynchronousSocketChannel
    get() {
        if(!::_socket.isInitialized) {
            _socket = AsynchronousSocketChannel.open()
        }
        return _socket
    }

    private fun start() {
        launch {
            log.info("Starting NodeClient -> $hostName:$port")
            socket.aConnect(InetSocketAddress(hostName, port))
        }
    }

    override fun close() {
        coroutineContext.cancelChildren()
        ignoreIoExceptions {
            socket.close()
        }
    }
}