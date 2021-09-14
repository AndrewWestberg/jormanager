package com.swiftmako.jormanager.nodeclient.protocols.mux

import com.google.iot.cbor.CborReader
import com.swiftmako.jormanager.entities.ChainBlock
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import com.swiftmako.jormanager.nodeclient.protocols.chainsync.ChainSyncProtocol
import com.swiftmako.jormanager.nodeclient.protocols.handshake.HandshakeProtocol
import com.swiftmako.jormanager.nodeclient.utils.BufferPool
import com.swiftmako.jormanager.repositories.ChainRepository
import com.swiftmako.jormanager.services.PooltoolService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.nio.aConnect
import kotlinx.coroutines.nio.aRead
import kotlinx.coroutines.nio.aWrite
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.internal.ignoreIoExceptions
import okhttp3.internal.toHexString
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.experimental.xor

class MuxProtocol(
    private val host: Host,
    private val poolId: String,
    private val hostName: String,
    private val port: Int,
    private val networkMagic: Long,
    private val shelleyGenesisHash: ByteArray,
    private val chainRepository: ChainRepository,
    private val isPooltool: Boolean,
    private val pooltoolService: PooltoolService,
    private val pooltoolApiKey: String,
) : CoroutineScope {
    private val log = LoggerFactory.getLogger("MuxProtocol")

    val job = SupervisorJob()
    override val coroutineContext: CoroutineContext =
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, throwable ->
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
                launchProtocolSender(handshakeProtocol, asyncSocketChannel, job)

                val chainBlocks = getChainBlocksForSyncStart()
                val chainSyncProtocol =
                    ChainSyncProtocol(
                        host,
                        shelleyGenesisHash,
                        chainBlocks,
                        chainRepository,
                        isPooltool,
                        pooltoolService,
                        pooltoolApiKey,
                        poolId
                    )
                launchProtocolSender(chainSyncProtocol, asyncSocketChannel, job)

                // Start the socket receiver loop
                val receiverLoop = async(job) {
                    while (true) {
                        val receiveBuffer = BufferPool.borrow()
                        receiveBuffer.limit(8)
                        //log.debug("ready to read: position(): ${receiveBuffer.position()}, limit(): ${receiveBuffer.limit()}, remaining(): ${receiveBuffer.remaining()}, capacity(): ${receiveBuffer.capacity()}")
                        var bytesReceived = 0
                        while (bytesReceived < 8) {
                            val byteCnt = asyncSocketChannel.aRead(receiveBuffer, 20L, TimeUnit.MINUTES)
                            //log.debug("read socket bytes: $byteCnt")
                            if (byteCnt <= 0) {
                                BufferPool.recycle(receiveBuffer)
                                throw IOException("Unexpected end of stream!")
                            }
                            bytesReceived += byteCnt
                        }
                        receiveBuffer.flip()
                        /*val timestamp =*/ receiveBuffer.int
                        val protocolId = receiveBuffer.short
                        //log.debug("rawProtocolId: $protocolId")
                        val payloadLength = receiveBuffer.short.toInt()
                        //log.debug("Received Msg: timestamp: 0x${timestamp.toHexString().padStart(8, '0')}, protocolId: 0x${protocolId.toInt().toHexString().padStart(4, '0').substring(4)}, payloadLength: $payloadLength")
                        receiveBuffer.limit(receiveBuffer.position() + payloadLength)
                        val payloadStartPosition = receiveBuffer.position()
                        bytesReceived = 0
                        while (bytesReceived < payloadLength) {
                            val byteCnt = asyncSocketChannel.aRead(receiveBuffer, 2L, TimeUnit.SECONDS)
                            //log.debug("read socket bytes: $byteCnt")
                            if (byteCnt <= 0) {
                                BufferPool.recycle(receiveBuffer)
                                throw IOException("Unexpected end of stream!")
                            }
                            bytesReceived += byteCnt
                        }
                        receiveBuffer.flip()
                        receiveBuffer.position(payloadStartPosition)
                        when (protocolId xor 0x8000.toShort()) {
                            handshakeProtocol.protocolId -> {
                                handshakeProtocol.rxChannel.send(receiveBuffer)
                            }
                            chainSyncProtocol.protocolId -> {
                                chainSyncProtocol.rxChannel.send(receiveBuffer)
                            }

                            else -> {
                                try {
                                    log.error(
                                        "Unknown message received: protocolId: 0x${
                                            protocolId.toInt().toHexString().padStart(4, '0').substring(4)
                                        }"
                                    )

                                    ByteArrayInputStream(
                                        receiveBuffer.array(),
                                        receiveBuffer.position(),
                                        receiveBuffer.remaining()
                                    ).use { byteStream ->
                                        CborReader.createFromInputStream(byteStream).apply {
                                            while (byteStream.available() > 0) {
                                                val jsonString = readDataItem().toJsonString()
                                                log.error("Unknown data: $jsonString")
                                            }
                                        }
                                    }
                                } finally {
                                    BufferPool.recycle(receiveBuffer)
                                }
                            }
                        }
                    }
                }

                // These two need to complete before we start chain sync
                handshakeProtocol.startAsync(this + job).await()
                log.debug("Connected to Node.")

                awaitAll(
                    chainSyncProtocol.startAsync(this + job),
                    receiverLoop
                )
            } catch (e: Throwable) {
                log.error("Fatal Protocol Exception!", e)
            } finally {
                job.cancelChildren()
            }

            ignoreIoExceptions {
                asyncSocketChannel.close()
            }

            delay(30_000) // wait 30 seconds before trying again
        }
    }

    private fun getChainBlocksForSyncStart(): List<ChainBlock> {
        // Clean up old versions less than 20 so they re-sync
        chainRepository.deleteEmptyEta()

        val page = chainRepository.findAll(PageRequest.of(0, 64, Sort.Direction.DESC, "slotNumber"))

        return page.content.filterIndexed { index, _ ->
            // all powers of 2 including 0th element 0, 2, 4, 8, 16, 32, 64
            index == 0 || (index > 1 && (index and (index - 1) == 0))
        }.toMutableList().also {
            it.add(
                // Last byron block of mainnet
                ChainBlock(
                    slotNumber = 4492799,
                    hash = "f8084c61b6a238acec985b59310b6ecec49c0ab8352249afd7268da5cff2a457",
                    blockNumber = 0L,
                    prevHash = "",
                    etaV = "",
                    poolId = "",
                    leaderVrf = "",
                )
            )
            it.add(
                // Last byron block of testnet
                ChainBlock(
                    slotNumber = 1598399,
                    hash = "7e16781b40ebf8b6da18f7b5e8ade855d6738095ef2f1c58c77e88b6e45997a4",
                    blockNumber = 0L,
                    prevHash = "",
                    etaV = "",
                    poolId = "",
                    leaderVrf = "",
                )
            )
            it.add(
                // Last byron block of guild
                ChainBlock(
                    slotNumber = 359,
                    hash = "9c0fe75b6a0499e9576a09589a5777e7021824e8a6d037065829423f861a9bb6",
                    blockNumber = 0L,
                    prevHash = "",
                    etaV = "",
                    poolId = "",
                    leaderVrf = "",
                )
            )
        }
    }


    private fun launchProtocolSender(protocol: MiniProtocol, asyncSocketChannel: AsynchronousSocketChannel, job: Job) {
        launch(job) {
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
                            asyncSocketChannel.aWrite(sendBuffer, 2L, TimeUnit.SECONDS)
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
