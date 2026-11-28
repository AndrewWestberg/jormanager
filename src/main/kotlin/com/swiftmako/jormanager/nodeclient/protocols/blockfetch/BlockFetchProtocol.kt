package com.swiftmako.jormanager.nodeclient.protocols.blockfetch

import com.firehose.controllers.nodeclient.protocol.Agency
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborReader
import com.swiftmako.jormanager.ktx.elementToByteArray
import com.swiftmako.jormanager.ktx.elementToLong
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import com.swiftmako.jormanager.nodeclient.protocols.NetworkChainSession
import com.swiftmako.jormanager.nodeclient.protocols.mux.muxByteBufferPool
import com.swiftmako.jormanager.utils.CborSpans
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.floor
import org.slf4j.LoggerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Fetches exactly the headers delivered on this connection, never from a global tip. */
class BlockFetchProtocol(
    private val session: NetworkChainSession,
    private val leios: LeiosFetchProtocol,
    scope: CoroutineScope,
    private val maxBatchSize: Int = MAX_BATCH_SIZE,
) : MiniProtocol(protocolId = 0x0003.toShort()) {
    companion object {
        const val MAX_BATCH_SIZE = NetworkChainSession.MAX_BATCH_SIZE
    }

    private val log = LoggerFactory.getLogger("LedgerDao")
    private var nextLogTime = Long.MIN_VALUE
    override val rxBufferSize = 8_388_608

    private enum class State { Idle, Busy, Streaming, Done }

    @Volatile private var state = State.Idle
        set(value) {
            field = value
            agencyUpdates.tryEmit(agency)
        }
    private val agencyUpdates = MutableSharedFlow<Agency>(replay = 1, extraBufferCapacity = 4).apply { tryEmit(agency) }
    override val agencyFlow: Flow<Agency> = agencyUpdates
    override val agency: Agency get() = when (state) {
        State.Idle -> Agency.Client
        State.Busy, State.Streaming -> Agency.Server
        State.Done -> Agency.None
    }

    private data class FetchedBatch(
        val requests: List<NetworkChainSession.Request>,
        val payloads: Result<List<Pair<Int, ByteArray>>>,
    )

    private val permits = Semaphore(2)
    private val wireLock = Any()
    private var current: List<NetworkChainSession.Request>? = null
    private var receivedCount = 0
    private val pending = mutableListOf<Pair<Int, ByteArray>>()

    init {
        require(maxBatchSize in 1..NetworkChainSession.MAX_BATCH_SIZE) { "Invalid maxBatchSize $maxBatchSize" }
    }

    private fun releaseBatch(sessionCount: Int) {
        repeat(sessionCount) { session.release() }
        permits.release()
    }

    private val batches = Channel<FetchedBatch>(2, onUndeliveredElement = { releaseBatch(it.requests.size) })
    private val worker = scope.launch(Dispatchers.IO) {
        for (batch in batches) {
            var remainingSessionPermits = batch.requests.size
            try {
                val first = batch.requests.first()
                if (first.generation == session.generation) {
                    val blocks =
                        try {
                            batch.payloads.getOrThrow()
                        } catch (e: Exception) {
                            throw recordFailure(first, null, e)
                        }
                    for (index in batch.requests.indices) {
                        val request = batch.requests[index]
                        if (request.generation != session.generation) break
                        save(request, blocks[index])
                        session.release()
                        remainingSessionPermits--
                    }
                }
            } finally {
                releaseBatch(remainingSessionPermits)
            }
        }
    }

    override suspend fun sendData(): ByteBuffer {
        check(state == State.Idle)
        permits.acquire()
        var batch: List<NetworkChainSession.Request>? = null
        var buffer: ByteBuffer? = null
        var handedOff = false
        try {
            batch = session.nextBatch(maxBatchSize)
            currentCoroutineContext().ensureActive()
            val firstPoint = batch
                .first()
                .point.slot
                .toLong() to batch
                .first()
                .point.hash
                .hexToByteArray()
            val lastPoint = batch
                .last()
                .point.slot
                .toLong() to batch
                .last()
                .point.hash
                .hexToByteArray()
            buffer = muxByteBufferPool.borrow()
            MsgRequestRange(firstPoint, lastPoint).writeToBuffer(buffer)
            buffer.flip()
            currentCoroutineContext().ensureActive()
            synchronized(wireLock) {
                check(state == State.Idle) { "Block fetch is shut down" }
                current = batch
                receivedCount = 0
                pending.clear()
                state = State.Busy
                handedOff = true
            }
            return buffer
        } finally {
            if (!handedOff) {
                buffer?.let { muxByteBufferPool.recycle(it) }
                releaseBatch(batch?.size ?: 0)
            }
        }
    }

    override fun receiveData(payload: ByteBuffer) {
        ByteArrayInputStream(payload.array(), payload.position(), payload.remaining()).use { stream ->
            val reader = CborReader.createFromInputStream(stream)
            while (stream.available() > 0) {
                val message = reader.readDataItem() as CborArray
                val id = message.elementToLong(0)
                when (state) {
                    State.Busy -> when (id) {
                        MsgStartBatch.MESSAGE_ID -> synchronized(wireLock) {
                            if (current != null) state = State.Streaming
                        }
                        MsgNoBlocks.MESSAGE_ID -> transfer { requests ->
                            Result.failure(
                                IllegalStateException(
                                    "Requested block range unavailable at ${requests.first().point}..${requests.last().point}"
                                )
                            )
                        }
                        else -> error("Unexpected block fetch message $id")
                    }
                    State.Streaming -> when (id) {
                        MsgBlock.MESSAGE_ID -> {
                            val activeBatch = synchronized(wireLock) {
                                val requests = current ?: return@synchronized null
                                check(receivedCount < requests.size) {
                                    "More blocks returned than requested (${requests.size})"
                                }
                                receivedCount++
                                requests
                            } ?: continue
                            if (activeBatch.first().generation == session.generation) {
                                val encoded = message.elementToByteArray(1)
                                val envelope = CborSpans.parse(encoded).array()
                                require(envelope.size == 2) { "Invalid Cardano block envelope" }
                                // Cardano's disk-compatible block tags reserve 0 and 1 for Byron blocks.
                                val tag = envelope[0].integer().intValueExact()
                                require(tag in 0..8) { "Unknown Cardano block tag $tag" }
                                val era = if (tag < 2) 0 else tag - 1
                                val block = if (era == 0) encoded else envelope[1].bytes()
                                synchronized(wireLock) {
                                    if (current === activeBatch) pending.add(era to block)
                                }
                            }
                        }
                        MsgBatchDone.MESSAGE_ID -> transfer { requests ->
                            when {
                                receivedCount != requests.size ->
                                    Result.failure(
                                        IllegalStateException(
                                            "Truncated block batch at ${requests[receivedCount].point}: expected ${requests.size}, received $receivedCount"
                                        )
                                    )
                                requests.first().generation != session.generation -> Result.success(emptyList())
                                pending.size != requests.size -> Result.success(emptyList())
                                else -> Result.success(pending.toList())
                            }
                        }
                        else -> error("Unexpected streaming block fetch message $id")
                    }
                    State.Done -> return
                    else -> error("Unexpected block fetch response in $state")
                }
            }
        }
    }

    private fun transfer(payloads: (List<NetworkChainSession.Request>) -> Result<List<Pair<Int, ByteArray>>>) {
        var failed = false
        synchronized(wireLock) {
            val requests = current ?: return
            val item = FetchedBatch(requests, payloads(requests))
            current = null
            pending.clear()
            receivedCount = 0
            if (batches.trySend(item).isSuccess) {
                state = State.Idle
            } else {
                releaseBatch(requests.size)
                failed = true
            }
        }
        check(!failed) { "Block fetch processing channel closed or full" }
    }

    private fun recordFailure(
        request: NetworkChainSession.Request,
        commitment: String?,
        cause: Exception,
    ): IllegalStateException {
        val content = commitment?.let { "Leios commitment $it" } ?: "Block"
        val contextual = IllegalStateException("$content at ${request.point}: ${cause.message ?: "acquisition failed"}", cause)
        if (request.generation == session.generation) {
            session.coordinator.boundaryFailure(session.networkKey, requireNotNull(contextual.message), request.generation)
        }
        return contextual
    }

    private suspend fun save(
        request: NetworkChainSession.Request,
        payload: Pair<Int, ByteArray>,
    ) {
        var fetchingLeios = false
        var commitment: String? = null

        try {
            val (era, bytes) = payload
            val decoded = LedgerBlockDecoder.decode(era, bytes, session.byronEpochSlots)
            val closure = if (decoded.leiosCertificate != null) {
                val previous = requireNotNull(decoded.headerDetails?.previousHash)
                fetchingLeios = true
                val announced = requireNotNull(session.coordinator.announcedClosure(session.networkKey, previous.toHexString())) {
                    "Certified predecessor announcement unavailable at ${previous.toHexString()}"
                }
                commitment = announced.hash.toHexString()
                leios.acquire(announced.slot, announced.hash, announced.size, request.generation)
            } else {
                null
            }
            currentCoroutineContext().ensureActive()
            if (request.generation == session.generation) {
                session.coordinator.adopt(session.networkKey, request.generation, era, decoded, request.point, closure)
                logSavedBlock((decoded.byronHeader?.blockNumber ?: requireNotNull(decoded.headerDetails).blockNumber).longValueExact())
            }
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive && fetchingLeios) throw recordFailure(request, commitment, e)
            throw e
        } catch (e: Exception) {
            throw recordFailure(request, commitment, e)
        }
    }

    internal fun logSavedBlock(
        blockNumber: Long,
        now: Long = System.nanoTime()
    ) {
        if (!log.isInfoEnabled) return
        val periodic = now >= nextLogTime
        val tip = maxOf(blockNumber, session.tipBlockNumber)
        if (!periodic && blockNumber < tip) return
        if (periodic) nextLogTime = now + 10_000_000_000L
        val percent = if (tip == 0L) 100.0 else floor(blockNumber.toDouble() / tip * 10000.0) / 100.0
        log.info("BlckFetch: Saved block: $blockNumber of $tip, %.2f%% synced".format(Locale.ROOT, percent))
    }

    override fun shutdown() {
        var inFlightCount: Int? = null
        synchronized(wireLock) {
            state = State.Done
            val active = current
            if (active != null) {
                current = null
                pending.clear()
                receivedCount = 0
                inFlightCount = active.size
            }
        }
        inFlightCount?.let { releaseBatch(it) }
        batches.cancel()
        worker.cancel()
    }
}
