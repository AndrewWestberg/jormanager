package com.swiftmako.jormanager.nodeclient.protocols.mux

import com.firehose.controllers.nodeclient.protocol.Agency
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import io.ktor.network.sockets.Connection
import io.ktor.network.sockets.isClosed
import io.ktor.util.cio.KtorDefaultPool
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.pool.ByteBufferPool
import io.ktor.utils.io.readFully
import io.ktor.utils.io.writeFully
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.experimental.xor
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

val muxByteBufferPool: ByteBufferPool = ByteBufferPool(20, 64 * 1024)

class Mux(
    private val socketConnection: Connection,
) {
    private val log by lazy { LoggerFactory.getLogger("Mux") }
    private val rxMuxBufferMap: MutableMap<Short, MuxRxBuffer> = mutableMapOf()

    private var runningProtocols: Array<out MiniProtocol> = emptyArray()

    suspend fun execute(vararg protocols: MiniProtocol) =
        coroutineScope {
            runningProtocols = protocols
            val protocolNames = protocols.map { it::class.java.simpleName }
            log.info("execute($protocolNames)")

            awaitAll(
                // TxJob
                async {
                    executeTx(protocols)
                },
                // RxJob
                async {
                    executeRx(protocols)
                },
            )

            log.info("execute($protocolNames): done")
        }

    private val txMutex = Mutex()

    private suspend fun executeTx(protocols: Array<out MiniProtocol>) =
        coroutineScope {
            val txChannel = socketConnection.output
            val deferreds = mutableListOf<Deferred<Unit>>()
            protocols.forEach { protocol ->
                deferreds.add(
                    async {
                        protocol.agencyFlow
                            .takeWhile { agency -> agency != Agency.None }
                            .filter { agency -> agency == Agency.Client }
                            .collect {
                                val payload = protocol.sendData()
                                val sendBuffer = muxByteBufferPool.borrow()
                                try {
                                    sendBuffer.putInt(timestampNow)
                                    sendBuffer.putShort(protocol.protocolId)
                                    sendBuffer.putShort(payload.remaining().toShort())
                                    sendBuffer.put(payload)
                                    sendBuffer.flip()

                                    if (log.isTraceEnabled) {
                                        val hex =
                                            sendBuffer
                                                .array()
                                                .copyOfRange(
                                                    sendBuffer.position(),
                                                    sendBuffer.position() + sendBuffer.remaining()
                                                ).toHexString()
                                        log.error("> : $hex")
                                    }

                                    txMutex.withLock {
                                        txChannel.writeFully(sendBuffer)
                                        txChannel.flush()
                                    }
                                } finally {
                                    muxByteBufferPool.recycle(payload)
                                    muxByteBufferPool.recycle(sendBuffer)
                                }
                            }
                        log.info("executeTx: protocol %02X done.".format(protocol.protocolId))
                    }
                )
            }
            deferreds.awaitAll()
        }

    private suspend fun executeRx(protocols: Array<out MiniProtocol>) =
        coroutineScope {
            val rxChannel = socketConnection.input
            var protocolsDone = false
            while (!protocolsDone) {
                if (socketConnection.socket.isClosed) {
                    throw IOException("Socket closed.")
                }
                if (rxChannel.isClosedForRead) {
                    throw IOException("rxChannel closed.")
                }
                rxChannel.awaitContent()
                val serverAgencyProtocols =
                    protocols.filter { protocol -> protocol.agency == Agency.Server }.associateBy { it.protocolId }
                if (serverAgencyProtocols.isNotEmpty()) {
                    val header = readHeader(rxChannel)
                    val rxProtocol = serverAgencyProtocols[header.protocolId]!!
                    val rxMuxBuffer = rxMuxBufferMap.getOrPut(header.protocolId) {
                        MuxRxBuffer(ByteBuffer.allocate(rxProtocol.rxBufferSize))
                    }
                    val buffer = rxMuxBuffer.rxBuffer
                    require(header.payloadLength <= buffer.remaining()) { "Mux message exceeds protocol buffer limit" }
                    val oldLimit = buffer.limit()
                    buffer.limit(buffer.position() + header.payloadLength)
                    rxChannel.readFully(buffer)
                    buffer.limit(oldLimit)
                    while (true) {
                        val end = rxMuxBuffer.framer.advance(buffer.array(), buffer.position()) ?: break
                        val count = buffer.position()
                        rxProtocol.receiveData(ByteBuffer.wrap(buffer.array(), 0, end).slice())
                        val left = count - end
                        if (left > 0) System.arraycopy(buffer.array(), end, buffer.array(), 0, left)
                        buffer.clear()
                        buffer.position(left)
                        rxMuxBuffer.framer = CborMessageFramer()
                    }
                }


                // Are all protocols in Agency.None? If so, then we're done.
                protocolsDone = !socketConnection.socket.isClosed &&
                    protocols.filterNot { protocol -> protocol.agency == Agency.None }.isEmpty()
            }

            log.info("executeRx: all protocols done.")
        }

    private suspend fun readHeader(rxChannel: ByteReadChannel): MiniProtocolHeader {
        val rxHeaderBuffer = KtorDefaultPool.borrow()
        try {
            rxHeaderBuffer.clear()
            rxHeaderBuffer.limit(8)
            rxChannel.readFully(rxHeaderBuffer)
            rxHeaderBuffer.flip()
            val timestamp = rxHeaderBuffer.int
            val rawProtocolId = rxHeaderBuffer.short
            // log.debug("rawProtocolId: $rawProtocolId")
            val protocolId = rawProtocolId xor 0x8000.toShort()
            val payloadLength = rxHeaderBuffer.short.toInt() and 0xffff
            // log.debug("Received Msg: timestamp: 0x${timestamp.toHexString().padStart(8, '0')}, protocolId: 0x${protocolId.toInt().toHexString().padStart(4, '0').substring(4)}, payloadLength: $payloadLength")

            return MiniProtocolHeader(timestamp, protocolId, payloadLength)
        } finally {
            KtorDefaultPool.recycle(rxHeaderBuffer)
        }
    }

    fun shutdownGracefully() {
        runningProtocols.forEach { it.shutdown() }
        runningProtocols = emptyArray()
    }

    private class MiniProtocolHeader(
        val timestamp: Int,
        val protocolId: Short,
        val payloadLength: Int,
    )

    private val timestampNow: Int
        get() = (System.nanoTime() / 1000).toInt()


    private class MuxRxBuffer(
        val rxBuffer: ByteBuffer,
        var framer: CborMessageFramer = CborMessageFramer(),
    )
}
