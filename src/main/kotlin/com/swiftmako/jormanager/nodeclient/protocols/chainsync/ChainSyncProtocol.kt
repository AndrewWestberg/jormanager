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
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
) : MiniProtocol(protocolId = 0x0002.toShort()),
    CoroutineScope {
    private val log by lazy { LoggerFactory.getLogger("ChainSyncProtocol") }

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext =
        job + Dispatchers.IO +
            CoroutineExceptionHandler { _, throwable ->
                if (throwable !is CancellationException) {
                    log.error("Uncaught coroutine exception!", throwable)
                }
            }

    override val rxBufferSize: Int = 64 * 1024

    private var state = State.Idle
        set(value) {
            field = value
            _agencyFlow.tryEmit(agency)
        }

    private val _agencyFlow = MutableSharedFlow<Agency>(replay = 1, extraBufferCapacity = 4).apply { tryEmit(agency) }
    override val agencyFlow: Flow<Agency> = _agencyFlow

    override val agency: Agency
        get() =
            when (state) {
                State.Idle -> Agency.Client
                State.Done -> Agency.None
                else -> Agency.Server
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
        initBlockReceiveHandler()
        if (session != null && node != null && cardanoRepository != null) {
            launch {
                for (era in eras) {
                    try { cardanoRepository.observeBlockEra(host, node, era) }
                    catch (e: Exception) { log.warn("Current-era refresh failed for node ${node.id}", e) }
                }
            }
        }
    }

    override fun shutdown() {
        state = State.Done
        eras.close()
        job.cancelChildren()
    }

    override suspend fun sendData(): ByteBuffer =
        when (state) {
            State.Idle -> {
                if (isIntersectFound) session?.reserve()
                val payload = muxByteBufferPool.borrow()
                state =
                    if (isIntersectFound) {
                        log.trace("MsgRequestNext")
                        MsgRequestNext().writeToBuffer(payload)
                        State.CanAwait
                    } else {
                        log.trace("MsgFindIntersect")
                        if (session != null) {
                            MsgFindIntersect(intersectionPoints.map { it.slot.toLong() to it.hash.hexToByteArray() }, includeOrigin = true).writeToBuffer(payload)
                        } else if (tipToIntersect.isNotEmpty()) {
                            MsgFindIntersect(tipToIntersect).writeToBuffer(payload)
                        } else {
                            MsgFindIntersect(lastByronBlocks).writeToBuffer(payload)
                        }
                        _tipToIntersect = null
                        State.Intersect
                    }
                payload.flip()
            }

            else -> throw IllegalStateException("We should not call sendData() when we're in a $state state!")
        }

    override fun receiveData(payload: ByteBuffer) {
        when (state) {
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
                                    run {
                                        val isTip = msgRollForward.chainTip.hash == msgRollForward.hash

                                        if (isPooltool) {
                                            if (isTip) {
                                                // We're on tip! Send to pooltool
                                                runBlocking {
                                                    blockSaveFlow.emit(msgRollForward)
                                                }
                                            } else {
                                                // try to jump to tip since we're doing pooltool sending
                                                _tipToIntersect = listOf(
                                                    Pair(
                                                        msgRollForward.chainTip.slot,
                                                        msgRollForward.chainTip.hash.hexToByteArray()
                                                    )
                                                ) + chainBlocksPairs
                                                isIntersectFound = false
                                            }
                                        } else {
                                            val active = requireNotNull(session)
                                            if (lastObservedEra != era) {
                                                lastObservedEra = era
                                                eras.trySend(era)
                                            }
                                            active.coordinator.observeTip(active.networkKey, ChainPoint(msgRollForward.chainTip.slot.toString(), msgRollForward.chainTip.hash), active.generation)
                                            if (active.defaultNetwork && era != 0) saveHeader(msgRollForward)
                                            active.enqueue(ChainPoint(msgRollForward.slotNumber.toString(), msgRollForward.hash))
                                        }
                                    }
                                    state = State.Idle
                                }

                                MsgRollBackward.MESSAGE_ID -> {
                                    log.info("MsgRollBackward: ${cborArray.toCborByteArray().toHexString()}")
                                    session?.let { active ->
                                        val point = parsePoint(cborArray.elementAt(1) as CborArray)
                                        active.rollback(point)
                                        active.release()
                                        if (active.defaultNetwork) rollbackHeaders(point)
                                        val tip = cborArray.elementAt(2) as CborArray
                                        parsePoint(tip.elementAt(0) as CborArray)?.let {
                                            active.coordinator.observeTip(active.networkKey, it, active.generation)
                                        }
                                    }
                                    state = State.Idle
                                }

                                MsgAwaitReply.MESSAGE_ID -> {
                                    log.trace("MsgAwaitReply: ${cborArray.toCborByteArray().toHexString()}")
                                    state = State.MustReply
                                }
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
                                    session?.let { active ->
                                        val point = parsePoint(cborArray.elementAt(1) as CborArray)
                                        if (point != intersectionPoints.firstOrNull()) {
                                            active.rollback(point)
                                            if (active.defaultNetwork) rollbackHeaders(point)
                                        }
                                        val tip = cborArray.elementAt(2) as CborArray
                                        parsePoint(tip.elementAt(0) as CborArray)?.let {
                                            active.coordinator.observeTip(active.networkKey, it, active.generation)
                                        }
                                    }
                                    isIntersectFound = true
                                    state = State.Idle
                                }

                                MsgIntersectNotFound.MESSAGE_ID -> {
                                    log.info("MsgIntersectNotFound: ${cborArray.toCborByteArray().toHexString()}")
                                    if (session != null) throw IllegalStateException("No chain intersection, including origin")
                                    isIntersectFound = tipToIntersect.isEmpty()
                                    state = State.Idle
                                }
                            }
                        }
                    }
                }
            }

            else -> throw IllegalStateException("We should not call receiveData() when we're not in a $state state!")
        }
    }

    private fun parsePoint(point: CborArray): ChainPoint? =
        if (point.size() == 0) null else ChainPoint(point.elementToLong(0).toString(), point.elementToHexString(1))

    private fun rollbackHeaders(point: ChainPoint?) {
        transaction {
            if (point == null) {
                chainRepository.deleteAll()
            } else {
                val block = chainRepository.findBySlot(point.slot.toLong())
                if (block != null && block.hash == point.hash) chainRepository.deleteByBlockNumberAndAbove(block.blockNumber + 1)
                else chainRepository.deleteAll()
            }
        }
    }

    private fun saveHeader(msg: MsgRollForward) {
        transaction {
            chainRepository.deleteByBlockNumberAndAbove(msg.blockNumber)
            val previous = chainRepository.findByBlockNumber(msg.blockNumber - 1)?.etaV?.hexToByteArray() ?: shelleyGenesisHash
            val eta = SodiumLibrary.cryptoBlake2bHash(msg.etaVrf.hexToByteArray(), null)
            chainRepository.save(ChainBlock(
                blockNumber = msg.blockNumber, slotNumber = msg.slotNumber, hash = msg.hash,
                prevHash = msg.prevHash, etaV = SodiumLibrary.cryptoBlake2bHash(previous + eta, null).toHexString(),
                poolId = nodeVKeyToPoolId(msg.nodeVKey), leaderVrf = msg.leaderVrf,
            ))
        }
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
