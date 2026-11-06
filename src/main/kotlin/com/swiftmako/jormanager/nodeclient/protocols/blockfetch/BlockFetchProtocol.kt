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
class BlockFetchProtocol(private val session: NetworkChainSession, private val leios: LeiosFetchProtocol, scope: CoroutineScope) : MiniProtocol(protocolId = 0x0003.toShort()) {
    private val log = LoggerFactory.getLogger("LedgerDao")
    private var nextLogTime = Long.MIN_VALUE
    override val rxBufferSize = 8_388_608
    private enum class State { Idle, Busy, Streaming, Done }
    @Volatile private var state = State.Idle
        set(value) { field = value; agencyUpdates.tryEmit(agency) }
    private val agencyUpdates = MutableSharedFlow<Agency>(replay = 1, extraBufferCapacity = 4).apply { tryEmit(agency) }
    override val agencyFlow: Flow<Agency> = agencyUpdates
    override val agency: Agency get() = when (state) {
        State.Idle -> Agency.Client
        State.Busy, State.Streaming -> Agency.Server
        State.Done -> Agency.None
    }
    private data class FetchedBlock(val request: NetworkChainSession.Request, val payload: Result<Pair<Int, ByteArray>>)
    private val permits = Semaphore(2)
    private val wireLock = Any()
    private var current: NetworkChainSession.Request? = null
    private var received = false
    private var pending: Pair<Int, ByteArray>? = null
    private fun release() { session.release(); permits.release() }
    private val blocks = Channel<FetchedBlock>(1, onUndeliveredElement = { release() })
    private val worker = scope.launch(Dispatchers.IO) {
        for (block in blocks) {
            try {
                if (block.request.generation == session.generation) save(block)
            } finally {
                release()
            }
        }
    }

    override suspend fun sendData(): ByteBuffer {
        check(state == State.Idle)
        permits.acquire()
        var request: NetworkChainSession.Request? = null
        var buffer: ByteBuffer? = null
        var handedOff = false
        try {
            request = session.next()
            currentCoroutineContext().ensureActive()
            val point = request.point.slot.toLong() to request.point.hash.hexToByteArray()
            buffer = muxByteBufferPool.borrow()
            MsgRequestRange(point, point).writeToBuffer(buffer)
            buffer.flip()
            currentCoroutineContext().ensureActive()
            synchronized(wireLock) {
                check(state == State.Idle) { "Block fetch is shut down" }
                current = request
                received = false
                pending = null
                state = State.Busy
                handedOff = true
            }
            return buffer
        } finally {
            if (!handedOff) {
                buffer?.let { muxByteBufferPool.recycle(it) }
                if (request != null) session.release()
                permits.release()
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
                        MsgNoBlocks.MESSAGE_ID -> transfer { request ->
                            Result.failure(IllegalStateException("Requested block unavailable at ${request.point}"))
                        }
                        else -> error("Unexpected block fetch message $id")
                    }
                    State.Streaming -> when (id) {
                        MsgBlock.MESSAGE_ID -> {
                            val request = synchronized(wireLock) {
                                check(!received) { "More than one block returned for a single point" }
                                received = true
                                current
                            } ?: continue
                            if (request.generation == session.generation) {
                                val encoded = message.elementToByteArray(1)
                                val envelope = CborSpans.parse(encoded).array()
                                require(envelope.size == 2) { "Invalid Cardano block envelope" }
                                // Cardano's disk-compatible block tags reserve 0 and 1 for Byron blocks.
                                val tag = envelope[0].integer().intValueExact()
                                require(tag in 0..8) { "Unknown Cardano block tag $tag" }
                                val era = if (tag < 2) 0 else tag - 1
                                val block = if (era == 0) encoded else envelope[1].bytes()
                                synchronized(wireLock) {
                                    if (current === request) pending = era to block
                                }
                            }
                        }
                        MsgBatchDone.MESSAGE_ID -> transfer { request ->
                            pending?.let { Result.success(it) }
                                ?: Result.failure(IllegalStateException("Empty block batch at ${request.point}"))
                        }
                        else -> error("Unexpected streaming block fetch message $id")
                    }
                    State.Done -> return
                    else -> error("Unexpected block fetch response in $state")
                }
            }
        }
    }

    private fun transfer(payload: (NetworkChainSession.Request) -> Result<Pair<Int, ByteArray>>) {
        var failed = false
        synchronized(wireLock) {
            val request = current ?: return
            val item = FetchedBlock(request, payload(request))
            current = null
            pending = null
            received = false
            if (blocks.trySend(item).isSuccess) state = State.Idle
            else {
                release()
                failed = true
            }
        }
        check(!failed) { "Block fetch processing channel closed or full" }
    }

    private suspend fun save(block: FetchedBlock) {
        val request = block.request
        var fetchingLeios = false
        var commitment: String? = null
        fun failure(cause: Exception): IllegalStateException {
            val content = commitment?.let { "Leios commitment $it" } ?: "Block"
            val contextual = IllegalStateException("$content at ${request.point}: ${cause.message ?: "acquisition failed"}", cause)
            if (request.generation == session.generation)
                session.coordinator.boundaryFailure(session.networkKey, requireNotNull(contextual.message), request.generation)
            return contextual
        }
        try {
            val (era, bytes) = block.payload.getOrThrow()
            val decoded = LedgerBlockDecoder.decode(era, bytes, session.byronEpochSlots)
            val closure = if (decoded.leiosCertificate != null) {
                val previous = requireNotNull(decoded.headerDetails?.previousHash)
                fetchingLeios = true
                val announced = requireNotNull(session.coordinator.announcedClosure(session.networkKey, previous.toHexString())) {
                    "Certified predecessor announcement unavailable at ${previous.toHexString()}"
                }
                commitment = announced.hash.toHexString()
                leios.acquire(announced.slot, announced.hash, announced.size, request.generation)
            } else null
            currentCoroutineContext().ensureActive()
            if (request.generation == session.generation) {
                session.coordinator.adopt(session.networkKey, request.generation, era, decoded, request.point, closure)
                logSavedBlock((decoded.byronHeader?.blockNumber ?: requireNotNull(decoded.headerDetails).blockNumber).longValueExact())
            }
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive && fetchingLeios) throw failure(e)
            throw e
        } catch (e: Exception) {
            throw failure(e)
        }
    }
    internal fun logSavedBlock(blockNumber: Long, now: Long = System.nanoTime()) {
        if (!log.isInfoEnabled) return
        val periodic = now >= nextLogTime
        val tip = maxOf(blockNumber, session.tipBlockNumber)
        if (!periodic && blockNumber < tip) return
        if (periodic) nextLogTime = now + 10_000_000_000L
        val percent = if (tip == 0L) 100.0 else floor(blockNumber.toDouble() / tip * 10000.0) / 100.0
        log.info("BlckFetch: Saved block: $blockNumber of $tip, %.2f%% synced".format(Locale.ROOT, percent))
    }
    override fun shutdown() {
        synchronized(wireLock) {
            state = State.Done
            if (current != null) {
                current = null
                pending = null
                release()
            }
        }
        blocks.cancel()
        worker.cancel()
    }
}
