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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/** Fetches exactly the headers delivered on this connection, never from a global tip. */
class BlockFetchProtocol(private val session: NetworkChainSession, private val leios: LeiosFetchProtocol) : MiniProtocol(protocolId = 0x0003.toShort()) {
    override val rxBufferSize = 8_388_608
    private enum class State { Idle, Busy, Streaming, Saving, Done }
    @Volatile private var state = State.Idle
        set(value) { field = value; agencyUpdates.tryEmit(agency) }
    private val agencyUpdates = MutableSharedFlow<Agency>(replay = 1, extraBufferCapacity = 4).apply { tryEmit(agency) }
    override val agencyFlow: Flow<Agency> = agencyUpdates
    override val agency: Agency get() = when (state) {
        State.Idle -> Agency.Client
        State.Busy, State.Streaming, State.Saving -> Agency.Server
        State.Done -> Agency.None
    }
    private var current: NetworkChainSession.Request? = null
    private var received = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var failure: Throwable? = null
    private var pending: Pair<Int, ByteArray>? = null

    override suspend fun sendData(): ByteBuffer {
        check(state == State.Idle)
        failure?.let { throw IllegalStateException(it.message ?: "Block acquisition failed", it) }
        val request = session.next()
        current = request
        received = false
        pending = null
        val point = request.point.slot.toLong() to request.point.hash.hexToByteArray()
        return muxByteBufferPool.borrow().also {
            MsgRequestRange(point, point).writeToBuffer(it)
            state = State.Busy
            it.flip()
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
                        MsgStartBatch.MESSAGE_ID -> state = State.Streaming
                        MsgNoBlocks.MESSAGE_ID -> {
                            val request = requireNotNull(current)
                            if (request.generation == session.generation) {
                                session.coordinator.boundaryFailure(session.networkKey, "Block not available at ${request.point}", request.generation)
                                throw IllegalStateException("Requested block unavailable at ${request.point}")
                            }
                            finish()
                        }
                        else -> error("Unexpected block fetch message $id")
                    }
                    State.Streaming -> when (id) {
                        MsgBlock.MESSAGE_ID -> {
                            check(!received) { "More than one block returned for a single point" }
                            received = true
                            val request = requireNotNull(current)
                            if (request.generation == session.generation) {
                                val encoded = message.elementToByteArray(1)
                                val envelope = CborSpans.parse(encoded).array()
                                require(envelope.size == 2) { "Invalid Cardano block envelope" }
                                // Cardano's disk-compatible block tags reserve 0 and 1 for
                                // Byron EBB and regular blocks; header era indices do not.
                                val tag = envelope[0].integer().intValueExact()
                                require(tag in 0..8) { "Unknown Cardano block tag $tag" }
                                val era = if (tag < 2) 0 else tag - 1
                                val block = if (era == 0) encoded else envelope[1].bytes()
                                pending = era to block
                            }
                        }
                        MsgBatchDone.MESSAGE_ID -> {
                            val request = requireNotNull(current)
                            if (!received && request.generation == session.generation) {
                                session.coordinator.boundaryFailure(session.networkKey, "Empty block batch at ${request.point}", request.generation)
                                throw IllegalStateException("Empty block batch at ${request.point}")
                            }
                            if (request.generation != session.generation) finish()
                            else {
                                val block = requireNotNull(pending)
                                state = State.Saving
                                scope.launch { save(request, block.first, block.second) }
                            }
                        }
                        else -> error("Unexpected streaming block fetch message $id")
                    }
                    else -> error("Unexpected block fetch response in $state")
                }
            }
        }
    }
    private suspend fun save(request: NetworkChainSession.Request, era: Int, bytes: ByteArray) {
        var fetchingLeios = false
        var commitment: String? = null
        fun recordFailure(cause: Exception) {
            val content = commitment?.let { "Leios commitment $it" } ?: "Block"
            val contextual = IllegalStateException("$content at ${request.point}: ${cause.message ?: "acquisition failed"}", cause)
            failure = contextual
            if (request.generation == session.generation)
                session.coordinator.boundaryFailure(session.networkKey, requireNotNull(contextual.message), request.generation)
        }
        try {
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
            if (request.generation == session.generation)
                session.coordinator.adopt(session.networkKey, request.generation, era, decoded, request.point, closure)
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive && fetchingLeios) {
                recordFailure(e)
            } else throw e
        } catch (e: Exception) {
            recordFailure(e)
        } finally {
            if (state != State.Done) finish()
        }
    }
    private fun finish() { current = null; pending = null; session.release(); state = State.Idle }
    override fun shutdown() { state = State.Done; scope.cancel() }
}
