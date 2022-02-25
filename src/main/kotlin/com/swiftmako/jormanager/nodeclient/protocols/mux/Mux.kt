package com.swiftmako.jormanager.nodeclient.protocols.mux

import com.firehose.controllers.nodeclient.protocol.Agency
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import io.ktor.network.sockets.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.pool.ByteBufferPool
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.ByteBuffer
import java.time.Instant
import kotlin.experimental.xor

@OptIn(ExperimentalIoApi::class)
val muxByteBufferPool: ByteBufferPool = ByteBufferPool(20, 64 * 1024)

@OptIn(ExperimentalIoApi::class)
class Mux(
    private val socketConnection: Connection,
) {
    private val log by lazy { LoggerFactory.getLogger(Mux::class.java) }
    private val rxMuxBufferMap: MutableMap<Short, MuxRxBuffer> = mutableMapOf()

    suspend fun execute(vararg protocols: MiniProtocol) = coroutineScope {
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
    private suspend fun executeTx(protocols: Array<out MiniProtocol>) = coroutineScope {
        val txChannel = socketConnection.output
        val deferreds = mutableListOf<Deferred<Unit>>()
        protocols.forEach { protocol ->
            deferreds.add(
                async {
                    protocol.agencyFlow
                        .takeWhile { agency -> agency != Agency.None }
                        .filter { agency -> agency == Agency.Client }
                        .collect {
                            val sendBuffer = muxByteBufferPool.borrow()
                            val payload = protocol.sendData()
                            try {
                                sendBuffer.putInt(timestampNow)
                                sendBuffer.putShort(protocol.protocolId)
                                sendBuffer.putShort(payload.remaining().toShort())
                                sendBuffer.put(payload)
                                sendBuffer.flip()

                                if (log.isTraceEnabled) {
                                    val hex = sendBuffer.array().copyOfRange(
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

    private suspend fun executeRx(protocols: Array<out MiniProtocol>) = coroutineScope {
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
                val rxMuxBuffer = rxMuxBufferMap[header.protocolId]
                    ?: MuxRxBuffer(
                        ByteBuffer.allocate(rxProtocol.RX_BUFFER_SIZE).apply {
                            clear()
                            limit(0)
                        }
                    ).also {
                        rxMuxBufferMap[header.protocolId] = it
                    }
                rxMuxBuffer.timeoutJob?.cancel()
                rxMuxBuffer.rxBuffer.limit(rxMuxBuffer.rxBuffer.limit() + header.payloadLength)
                rxChannel.readFully(rxMuxBuffer.rxBuffer)
                rxMuxBuffer.lastRxTimestamp = Instant.now()

                log.debug("rxProtocol: ${header.protocolId}, payloadLength: ${header.payloadLength}")

                if (header.payloadLength < MAX_SESSION_DATA_UNIT_LENGTH) {
                    // We have a full item, process it
                    with(rxMuxBuffer) {
                        rxBuffer.flip()
                        if (log.isTraceEnabled) {
                            val hex = rxBuffer.array().copyOfRange(
                                rxBuffer.position(),
                                rxBuffer.position() + rxBuffer.remaining()
                            ).toHexString()
                            log.warn("< : $hex")
                        }

                        rxProtocol.receiveData(rxBuffer)
                        rxBuffer.clear()
                        rxBuffer.limit(0)
                    }
                } else {
                    // We have a full frame partial item. Start timeout job to send it in case
                    // we never see any more than exactly 0x3000 bytes of data for 2000ms.
                    with(rxMuxBuffer) {
                        timeoutJob = launch {
                            delay(2000)
                            log.warn("Processing item after 2000ms timeout for protocol ${rxProtocol.protocolId}")
                            rxProtocol.receiveData(rxBuffer.flip())
                            rxBuffer.clear()
                            rxBuffer.limit(0)
                        }
                    }
                }
            }

            // See if we received any messages that were exactly 0x3000 bytes long and we haven't processed yet
            rxMuxBufferMap.entries.filter {
                it.value.rxBuffer.position() > 0 && it.value.lastRxTimestamp.plusMillis(500).isBefore(Instant.now())
            }.forEach { entry ->
                // We've received content, but haven't seen any more for over half a second. Assume it's a full item
                val rxProtocol = serverAgencyProtocols[entry.key]!!
                val rxMuxBuffer = entry.value
                with(rxMuxBuffer) {
                    rxProtocol.receiveData(rxBuffer.flip())
                    rxBuffer.clear()
                    rxBuffer.limit(0)
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
            //log.debug("rawProtocolId: $rawProtocolId")
            val protocolId = rawProtocolId xor 0x8000.toShort()
            val payloadLength = rxHeaderBuffer.short.toInt()
            //log.debug("Received Msg: timestamp: 0x${timestamp.toHexString().padStart(8, '0')}, protocolId: 0x${protocolId.toInt().toHexString().padStart(4, '0').substring(4)}, payloadLength: $payloadLength")

            return MiniProtocolHeader(timestamp, protocolId, payloadLength)
        } finally {
            KtorDefaultPool.recycle(rxHeaderBuffer)
        }
    }

    private class MiniProtocolHeader(
        val timestamp: Int,
        val protocolId: Short,
        val payloadLength: Int,
    )

    private val timestampNow: Int
        get() = (System.nanoTime() / 1000).toInt()

    companion object {
        private const val MAX_SESSION_DATA_UNIT_LENGTH = 0x3000 //12288 bytes
    }

    private class MuxRxBuffer(
        val rxBuffer: ByteBuffer,
        var timeoutJob: Job? = null,
        var lastRxTimestamp: Instant = Instant.now(),
    )
}