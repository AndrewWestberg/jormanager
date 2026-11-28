package com.swiftmako.jormanager.nodeclient.protocols.chainsync

import com.firehose.controllers.nodeclient.protocol.Agency
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborReader
import com.muquit.libsodiumjna.SodiumLibrary
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.ChainBlock
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.ktx.elementToLong
import com.swiftmako.jormanager.ktx.elementToHexString
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.pooltool.Data
import com.swiftmako.jormanager.model.pooltool.PooltoolStats
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import com.swiftmako.jormanager.nodeclient.protocols.NetworkChainSession
import com.swiftmako.jormanager.model.ChainPoint
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.nodeclient.protocols.mux.muxByteBufferPool
import com.swiftmako.jormanager.repositories.ChainRepository
import com.swiftmako.jormanager.services.PooltoolService
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.floor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.ISODateTimeFormat
import org.slf4j.LoggerFactory

class ChainSyncProtocol(
    private val host: Host,
    private val shelleyGenesisHash: ByteArray,
    private val chainBlocks: List<ChainBlock>,
    private val chainRepository: ChainRepository,
    private val isPooltool: Boolean,
    private val pooltoolService: PooltoolService,
    private val pooltoolApiKey: String,
    private val poolId: String,
    private val session: NetworkChainSession? = null,
    private val node: Node? = null,
    private val cardanoRepository: CardanoRepository? = null,
    private val intersectionPoints: List<ChainPoint> = emptyList(),
    private val maxInFlight: Int = MAX_IN_FLIGHT,
) : MiniProtocol(protocolId = 0x0002.toShort()),
    CoroutineScope {
    companion object {
        const val MAX_IN_FLIGHT = NetworkChainSession.MAX_BATCH_SIZE
    }

    private class PendingUpdate(
        val holdsPermit: Boolean,
        val action: () -> Unit,
    )

    private val log by lazy { LoggerFactory.getLogger("ChainSyncProtocol") }
    private var nextLogTime = Long.MIN_VALUE

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext =
        job + Dispatchers.IO +
            CoroutineExceptionHandler { _, throwable ->
                if (throwable !is CancellationException) {
                    log.error("Uncaught coroutine exception!", throwable)
                }
            }

    override val rxBufferSize: Int = 64 * 1024

    override val duplex: Boolean
        get() = session != null && !isPooltool

    @Volatile
    private var state = State.Idle
        set(value) {
            field = value
            _agencyFlow.tryEmit(agency)
        }

    private val pendingUpdates = ArrayDeque<PendingUpdate>()
    private var inFlight = 0
    private var awaitingReply = false
    private var pausedAtTip = false
    private var drainingRollback = false
    private val txWakeup = MutableStateFlow(0L)
    private var lastPersistedTip: Pair<Long, ChainPoint>? = null
    private val stateLock = Any()

    private val _agencyFlow = MutableSharedFlow<Agency>(replay = 1, extraBufferCapacity = 4).apply { tryEmit(agency) }
    override val agencyFlow: Flow<Agency> = _agencyFlow

    override val agency: Agency
        get() =
            when (state) {
                State.Idle -> Agency.Client
                State.Done -> Agency.None
                else -> Agency.Server
            }

    override val canSend: Boolean
        get() =
            if (!duplex) {
                agency == Agency.Client
            } else {
                synchronized(stateLock) { canSendWireLocked() }
            }

    override val canReceive: Boolean
        get() =
            if (!duplex) {
                agency == Agency.Server
            } else {
                synchronized(stateLock) {
                    state == State.Intersect || state == State.CanAwait || state == State.MustReply
                }
            }

    private var isIntersectFound = false

    // hardcode for now
    private val lastByronBlocks: List<Pair<Long, ByteArray>> =
        listOf(
            // testing bad block with huge token value
            // Pair(18342080L, "9d9a97918b9b77bed4e72eabb43b64b34affc929846d528babcd1d2f90976c2f".hexToByteArray()),
            // testing bad block with null value
            // Pair(23428799L, "fae8158c9fb6f53389d55ecb80b0538fde879a0b56dffc04904eb6d3359de4e3".hexToByteArray()),
            // testing hanging
            // Pair(25530527L, "cd02dd5f4e7a3198e94261d0a49335a0e0442a7dde794a161be175053781eabb".hexToByteArray()),
            // Pair(25530581L, "d9a9004c29643ff43cfe217f0e59017be359c6b864e66e795c884c5d3a7bf470".hexToByteArray()),
            Pair(4492799L, "f8084c61b6a238acec985b59310b6ecec49c0ab8352249afd7268da5cff2a457".hexToByteArray()), // mainnet
            Pair(84242L, "45899e8002b27df291e09188bfe3aeb5397ac03546a7d0ead93aa2500860f1af".hexToByteArray()), // preprod
            Pair(719L, "e5400faf19e712ebc5ff5b4b44cecb2b140d1cca25a011e36a91d89e97f53e2e".hexToByteArray()), // guild
        )

    private val blockSaveFlow = MutableSharedFlow<MsgRollForward>()
    private val eras = Channel<Int>(Channel.CONFLATED)
    private var lastObservedEra: Int? = null

    private val chainBlocksPairs by lazy {
        chainBlocks.map { chainBlock ->
            Pair(
                chainBlock.slotNumber,
                chainBlock.hash.hexToByteArray()
            )
        }
    }

    private var _tipToIntersect: List<Pair<Long, ByteArray>>? = null
    val tipToIntersect: List<Pair<Long, ByteArray>>
        get() = _tipToIntersect ?: chainBlocksPairs

    init {
        require(maxInFlight in 1..NetworkChainSession.MAX_BATCH_SIZE) { "Invalid maxInFlight $maxInFlight" }
        initBlockReceiveHandler()
        if (session != null && node != null && cardanoRepository != null) {
            launch {
                for (era in eras) {
                    try {
                        cardanoRepository.observeBlockEra(host, node, era)
                    } catch (e: Exception) {
                        log.warn("Current-era refresh failed for node ${node.id}", e)
                    }
                }
            }
        }
    }

    private fun canSendWireLocked(): Boolean {
        if (state == State.Done || state == State.Intersect) return false
        if (!isIntersectFound) return true
        return !awaitingReply && !drainingRollback && inFlight < maxInFlight && (!pausedAtTip || inFlight == 0)
    }

    private fun refreshDuplexStateLocked() {
        if (state == State.Done || state == State.Intersect) return
        state =
            when {
                inFlight == 0 -> State.Idle
                awaitingReply -> State.MustReply
                else -> State.CanAwait
            }
    }

    private suspend fun drainPendingUpdates() {
        while (true) {
            currentCoroutineContext().ensureActive()
            val next =
                synchronized(stateLock) {
                    if (state == State.Done || pendingUpdates.isEmpty()) null else pendingUpdates.removeFirst()
                } ?: return
            next.action.invoke()
        }
    }

    override suspend fun awaitCanSend(): Boolean {
        if (!duplex) return false
        while (true) {
            currentCoroutineContext().ensureActive()
            drainPendingUpdates()
            currentCoroutineContext().ensureActive()
            val decision =
                synchronized(stateLock) {
                    when {
                        state == State.Done -> false
                        pendingUpdates.isNotEmpty() -> null
                        canSendWireLocked() -> true
                        else -> null
                    }
                }
            if (decision != null) return decision
            val shouldWait =
                synchronized(stateLock) {
                    state != State.Done && pendingUpdates.isEmpty() && !canSendWireLocked()
                }
            if (shouldWait) {
                txWakeup.first {
                    synchronized(stateLock) {
                        state == State.Done || pendingUpdates.isNotEmpty() || canSendWireLocked()
                    }
                }
            }
        }
    }

    override fun shutdown() {
        var permitsToRelease = 0
        synchronized(stateLock) {
            state = State.Done
            permitsToRelease = inFlight + pendingUpdates.count { it.holdsPermit }
            inFlight = 0
            awaitingReply = false
            pausedAtTip = false
            drainingRollback = false
            pendingUpdates.clear()
            txWakeup.value++
        }
        if (session != null) {
            repeat(permitsToRelease) { session.release() }
        }
        eras.close()
        job.cancelChildren()
    }

    override suspend fun sendDataOrNull(): ByteBuffer? = sendDataInternal(allowNullOnPause = true)

    override suspend fun sendData(): ByteBuffer =
        sendDataInternal(allowNullOnPause = false)
            ?: throw IllegalStateException("We should not call sendData() when we're in a $state state!")

    private suspend fun sendDataInternal(allowNullOnPause: Boolean): ByteBuffer? {
        currentCoroutineContext().ensureActive()
        drainPendingUpdates()
        currentCoroutineContext().ensureActive()

        if (!duplex) {
            val reserved = isIntersectFound && session != null
            if (reserved) session.reserve()
            try {
                currentCoroutineContext().ensureActive()
                synchronized(stateLock) {
                    check(state == State.Idle) { "We should not call sendData() when we're in a $state state!" }
                    val payload = muxByteBufferPool.borrow()
                    state =
                        if (isIntersectFound) {
                            log.trace("MsgRequestNext")
                            MsgRequestNext().writeToBuffer(payload)
                            State.CanAwait
                        } else {
                            log.trace("MsgFindIntersect")
                            if (tipToIntersect.isNotEmpty()) {
                                MsgFindIntersect(tipToIntersect).writeToBuffer(payload)
                            } else {
                                MsgFindIntersect(lastByronBlocks).writeToBuffer(payload)
                            }
                            _tipToIntersect = null
                            State.Intersect
                        }
                    return payload.flip()
                }
            } catch (e: Throwable) {
                if (reserved) session.release()
                throw e
            }
        }

        val intersectPayload =
            synchronized(stateLock) {
                if (state == State.Done) {
                    if (allowNullOnPause) return null
                    error("We should not call sendData() when we're in a $state state!")
                }
                if (!isIntersectFound) {
                    check(state == State.Idle) { "We should not call sendData() when we're in a $state state!" }
                    val payload = muxByteBufferPool.borrow()
                    try {
                        log.trace("MsgFindIntersect")
                        MsgFindIntersect(
                            intersectionPoints.map { it.slot.toLong() to it.hash.hexToByteArray() },
                            includeOrigin = true,
                        ).writeToBuffer(payload)
                        _tipToIntersect = null
                        state = State.Intersect
                        txWakeup.value++
                        payload.flip()
                    } catch (e: Throwable) {
                        muxByteBufferPool.recycle(payload)
                        throw e
                    }
                } else {
                    if (!canSendWireLocked()) {
                        if (allowNullOnPause) return null
                        error("We should not call sendData() when we're in a $state state!")
                    }
                    null
                }
            }
        if (intersectPayload != null) return intersectPayload

        val active = requireNotNull(session)
        active.reserve()
        var committed = false
        try {
            drainPendingUpdates()
            currentCoroutineContext().ensureActive()
            synchronized(stateLock) {
                if (state == State.Done || !canSendWireLocked()) {
                    if (allowNullOnPause) return null
                    error("We should not call sendData() when we're in a $state state!")
                }
                val payload = muxByteBufferPool.borrow()
                try {
                    log.trace("MsgRequestNext")
                    MsgRequestNext().writeToBuffer(payload)
                    inFlight++
                    refreshDuplexStateLocked()
                    txWakeup.value++
                    committed = true
                    return payload.flip()
                } catch (e: Throwable) {
                    muxByteBufferPool.recycle(payload)
                    throw e
                }
            }
        } finally {
            if (!committed) active.release()
        }
    }

    override fun receiveData(payload: ByteBuffer) {
        val currentState = synchronized(stateLock) { state }
        when (currentState) {
            State.Done -> return

            State.CanAwait, State.MustReply -> {
                ByteArrayInputStream(payload.array(), payload.position(), payload.remaining()).use { byteStream ->
                    CborReader.createFromInputStream(byteStream).apply {
                        while (byteStream.available() > 0) {
                            val cborArray =
                                try {
                                    readDataItem() as CborArray
                                } catch (e: Throwable) {
                                    log.error("Error parsing cbor (position: ${payload.position()}, limit: ${payload.limit()}, remaining: ${payload.remaining()}: ${payload.array()}")
                                    throw e
                                }
                            val messageId: Long = cborArray.elementToLong(0)
                            when (messageId) {
                                MsgRollForward.MESSAGE_ID -> {
                                    if (log.isTraceEnabled) {
                                        log.trace("MsgRollForward: ${cborArray.toCborByteArray().toHexString()}")
                                    }
                                    val era = (cborArray.elementAt(1) as CborArray).elementToLong(0).toInt()
                                    val msgRollForward = MsgRollForwardAdapter.fromCborArray(cborArray, session?.byronEpochSlots).orElseThrow {
                                        IllegalStateException("Unable to decode era $era chain header")
                                    }
                                    if (isPooltool) {
                                        val isTip = msgRollForward.chainTip.hash == msgRollForward.hash
                                        if (isTip) {
                                            runBlocking {
                                                blockSaveFlow.emit(msgRollForward)
                                            }
                                        } else {
                                            _tipToIntersect = listOf(
                                                Pair(
                                                    msgRollForward.chainTip.slot,
                                                    msgRollForward.chainTip.hash.hexToByteArray()
                                                )
                                            ) + chainBlocksPairs
                                            isIntersectFound = false
                                        }
                                        state = State.Idle
                                    } else {
                                        val active = requireNotNull(session)
                                        val tip = ChainPoint(msgRollForward.chainTip.slot.toString(), msgRollForward.chainTip.hash)
                                        val point = ChainPoint(msgRollForward.slotNumber.toString(), msgRollForward.hash)
                                        val atTip = msgRollForward.chainTip.hash == msgRollForward.hash || msgRollForward.blockNumber >= msgRollForward.chainTip.block
                                        enqueueReplyUpdate(atTip = atTip, isRollback = false, holdsPermit = true) {
                                            var handedOff = false
                                            try {
                                                if (synchronized(stateLock) { state == State.Done }) return@enqueueReplyUpdate
                                                if (lastObservedEra != era) {
                                                    lastObservedEra = era
                                                    eras.trySend(era)
                                                }
                                                observeTip(active, tip, force = false)
                                                active.tipBlockNumber = msgRollForward.chainTip.block
                                                if (active.defaultNetwork && era != 0) saveHeader(msgRollForward)
                                                synchronized(stateLock) {
                                                    if (state != State.Done) {
                                                        active.enqueue(point)
                                                        handedOff = true
                                                    }
                                                }
                                            } finally {
                                                if (!handedOff) active.release()
                                            }
                                        }
                                    }
                                }

                                MsgRollBackward.MESSAGE_ID -> {
                                    log.info("MsgRollBackward: ${cborArray.toCborByteArray().toHexString()}")
                                    if (session != null) {
                                        val active = session
                                        val point = parsePoint(cborArray.elementAt(1) as CborArray)
                                        val tip = cborArray.elementAt(2) as CborArray
                                        val tipHeight = tip.elementToLong(1)
                                        val tipPoint = parsePoint(tip.elementAt(0) as CborArray)
                                        enqueueReplyUpdate(atTip = point == tipPoint, isRollback = true, holdsPermit = true) {
                                            try {
                                                if (synchronized(stateLock) { state == State.Done }) return@enqueueReplyUpdate
                                                active.rollback(point)
                                                if (active.defaultNetwork) rollbackHeaders(point)
                                                active.tipBlockNumber = tipHeight
                                                observeTip(active, tipPoint, force = true)
                                            } finally {
                                                active.release()
                                            }
                                        }
                                    } else {
                                        state = State.Idle
                                    }
                                }

                                MsgAwaitReply.MESSAGE_ID -> {
                                    log.trace("MsgAwaitReply: ${cborArray.toCborByteArray().toHexString()}")
                                    synchronized(stateLock) {
                                        if (state == State.Done) return
                                        check(state == State.CanAwait && !awaitingReply) { "Unexpected MsgAwaitReply in $state" }
                                        awaitingReply = true
                                        pausedAtTip = true
                                        state = State.MustReply
                                        txWakeup.value++
                                    }
                                }
                                else -> throw IllegalStateException("Unexpected chain sync message $messageId in $state")
                            }
                        }
                    }
                }
            }

            State.Intersect -> {
                ByteArrayInputStream(payload.array(), payload.position(), payload.remaining()).use { byteStream ->
                    CborReader.createFromInputStream(byteStream).apply {
                        while (byteStream.available() > 0) {
                            val cborArray = readDataItem() as CborArray
                            val messageId: Long = cborArray.elementToLong(0)
                            when (messageId) {
                                MsgIntersectFound.MESSAGE_ID -> {
                                    log.info("MsgIntersectFound: ${cborArray.toCborByteArray().toHexString()}")
                                    if (session != null) {
                                        val active = session
                                        val point = parsePoint(cborArray.elementAt(1) as CborArray)
                                        val tip = cborArray.elementAt(2) as CborArray
                                        val tipHeight = tip.elementToLong(1)
                                        val tipPoint = parsePoint(tip.elementAt(0) as CborArray)
                                        synchronized(stateLock) {
                                            if (state == State.Done) return
                                            check(state == State.Intersect && inFlight == 0) { "Unexpected chain sync response in $state" }
                                            isIntersectFound = true
                                            pendingUpdates.addLast(
                                                PendingUpdate(holdsPermit = false) {
                                                    if (synchronized(stateLock) { state == State.Done }) return@PendingUpdate
                                                    if (point != intersectionPoints.firstOrNull()) {
                                                        active.rollback(point)
                                                        if (active.defaultNetwork) rollbackHeaders(point)
                                                    }
                                                    active.tipBlockNumber = tipHeight
                                                    observeTip(active, tipPoint, force = true)
                                                }
                                            )
                                            state = State.Idle
                                            txWakeup.value++
                                        }
                                    } else {
                                        isIntersectFound = true
                                        state = State.Idle
                                    }
                                }

                                MsgIntersectNotFound.MESSAGE_ID -> {
                                    log.info("MsgIntersectNotFound: ${cborArray.toCborByteArray().toHexString()}")
                                    if (session != null) throw IllegalStateException("No chain intersection, including origin")
                                    isIntersectFound = tipToIntersect.isEmpty()
                                    state = State.Idle
                                }
                                else -> throw IllegalStateException("Unexpected chain sync message $messageId in $state")
                            }
                        }
                    }
                }
            }

            else -> throw IllegalStateException("We should not call receiveData() when we're not in a $currentState state!")
        }
    }

    private fun enqueueReplyUpdate(
        atTip: Boolean,
        isRollback: Boolean,
        holdsPermit: Boolean,
        update: () -> Unit,
    ) {
        synchronized(stateLock) {
            if (state == State.Done) return
            check((state == State.CanAwait || state == State.MustReply) && inFlight > 0) {
                "Unexpected chain sync response in $state"
            }
            inFlight--
            awaitingReply = false
            if (isRollback) {
                drainingRollback = inFlight > 0
            } else if (inFlight == 0) {
                drainingRollback = false
            }
            if (!atTip) {
                pausedAtTip = false
            }
            pendingUpdates.addLast(PendingUpdate(holdsPermit = holdsPermit, action = update))
            refreshDuplexStateLocked()
            txWakeup.value++
        }
    }

    private fun observeTip(
        active: NetworkChainSession,
        point: ChainPoint?,
        force: Boolean
    ) {
        if (point == null) {
            lastPersistedTip = null
            return
        }
        val key = active.generation to point
        if (force || lastPersistedTip != key) {
            active.coordinator.observeTip(active.networkKey, point, key.first)
            lastPersistedTip = key
        }
    }

    private fun parsePoint(point: CborArray): ChainPoint? = if (point.size() == 0) null else ChainPoint(point.elementToLong(0).toString(), point.elementToHexString(1))

    private fun rollbackHeaders(point: ChainPoint?) {
        transaction {
            if (point == null) {
                chainRepository.deleteAll()
            } else {
                val block = chainRepository.findBySlot(point.slot.toLong())
                if (block != null && block.hash == point.hash) {
                    chainRepository.deleteByBlockNumberAndAbove(block.blockNumber + 1)
                } else {
                    chainRepository.deleteAll()
                }
            }
        }
    }

    private fun saveHeader(msg: MsgRollForward) {
        val producer = nodeVKeyToPoolId(msg.nodeVKey)
        transaction {
            chainRepository.deleteByBlockNumberAndAbove(msg.blockNumber)
            val previous = chainRepository.findByBlockNumber(msg.blockNumber - 1)?.etaV?.hexToByteArray() ?: shelleyGenesisHash
            val eta = SodiumLibrary.cryptoBlake2bHash(msg.etaVrf.hexToByteArray(), null)
            chainRepository.save(
                ChainBlock(
                    blockNumber = msg.blockNumber,
                    slotNumber = msg.slotNumber,
                    hash = msg.hash,
                    prevHash = msg.prevHash,
                    etaV = SodiumLibrary.cryptoBlake2bHash(previous + eta, null).toHexString(),
                    poolId = producer,
                    leaderVrf = msg.leaderVrf,
                )
            )
        }
        logSavedBlock(msg, producer)
    }

    internal fun logSavedBlock(
        msg: MsgRollForward,
        producer: String,
        now: Long = System.nanoTime()
    ) {
        if (!log.isInfoEnabled) return
        val periodic = now >= nextLogTime
        val isTip = msg.hash == msg.chainTip.hash || msg.blockNumber >= msg.chainTip.block
        if (!periodic && !isTip) return
        if (periodic) nextLogTime = now + 10_000_000_000L
        val tip = maxOf(msg.blockNumber, msg.chainTip.block)
        val percent = if (tip == 0L) 100.0 else floor(msg.blockNumber.toDouble() / tip * 10000.0) / 100.0
        log.info("ChainSync: Saved block: ${msg.blockNumber} of $tip, %.2f%% synced, poolId: ${producer.take(9)}...".format(Locale.ROOT, percent))
    }

    private fun initBlockReceiveHandler() {
        launch { blockSaveFlow.collect { sendBlockToPooltool(it) } }
    }

    private var lastNodeVersionTime = 0L
    private var nodeVersion = ""

    private suspend fun sendBlockToPooltool(msgRollForward: MsgRollForward) {
        val now = System.currentTimeMillis()
        if (now - lastNodeVersionTime > 3600L) {
            val hostConnection = HostConnection(host)
            val versionString = hostConnection.command("${host.cardanoNodePath} --version").trim()
            Regex("cardano-node (\\d+\\.\\d+\\.\\d+) .*\ngit rev ([a-f0-9]{5}).*")
                .matchEntire(versionString)
                ?.let { matchResult ->
                    nodeVersion = "${matchResult.groupValues[1]}:${matchResult.groupValues[2]}"
                }
            lastNodeVersionTime = now
        }
        try {
            val at = DateTime(now, DateTimeZone.UTC).toString(ISODateTimeFormat.dateTime())
            val stats =
                PooltoolStats(
                    apiKey = pooltoolApiKey,
                    poolId = poolId,
                    data =
                        Data(
                            nodeId = "", // future use
                            version = nodeVersion,
                            at = at, // 2020-12-12T23:47:04.112Z
                            blockNo = msgRollForward.blockNumber,
                            slotNo = msgRollForward.slotNumber,
                            blockHash = msgRollForward.hash,
                            parentHash = msgRollForward.prevHash,
                            leaderVrf = msgRollForward.leaderVrf,
                            blockVrf = msgRollForward.blockVrf,
                            blockVrfProof = msgRollForward.blockVrfProof,
                            nodeVKey = msgRollForward.nodeVKey,
                        )
                )
            log.info("Pooltool Request: $stats")
            val response = pooltoolService.sendStats(stats)
            log.debug("pooltool response: {}", response.body())
        } catch (e: Throwable) {
            log.error("Error sending stats to pooltool!", e)
        }
    }

    private val blake2b224 = Blake2bDigest(224)

    private fun nodeVKeyToPoolId(nodeVKey: String): String {
        blake2b224.reset()
        val vKeyByteArray = nodeVKey.hexToByteArray()
        blake2b224.update(vKeyByteArray, 0, vKeyByteArray.size)
        val output = ByteArray(28)
        blake2b224.doFinal(output, 0)
        return output.toHexString()
    }

    enum class State {
        Idle,
        Intersect,
        CanAwait,
        MustReply,
        Done
    }
}
