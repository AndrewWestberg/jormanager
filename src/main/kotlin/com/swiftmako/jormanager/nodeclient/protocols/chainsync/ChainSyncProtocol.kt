package com.swiftmako.jormanager.nodeclient.protocols.chainsync

import com.firehose.controllers.nodeclient.protocol.Agency
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborReader
import com.muquit.libsodiumjna.SodiumLibrary
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.ChainBlock
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.ktx.elementToLong
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.pooltool.Data
import com.swiftmako.jormanager.model.pooltool.PooltoolStats
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import com.swiftmako.jormanager.nodeclient.protocols.mux.muxByteBufferPool
import com.swiftmako.jormanager.repositories.ChainRepository
import com.swiftmako.jormanager.services.PooltoolService
import io.ktor.utils.io.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.ISODateTimeFormat
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import kotlin.io.use

class ChainSyncProtocol(
    private val host: Host,
    private val shelleyGenesisHash: ByteArray,
    private val chainBlocks: List<ChainBlock>,
    private val chainRepository: ChainRepository,
    private val isPooltool: Boolean,
    private val pooltoolService: PooltoolService,
    private val pooltoolApiKey: String,
    private val poolId: String,
) : MiniProtocol(protocolId = 0x0002.toShort()) {

    private val log by lazy { LoggerFactory.getLogger(ChainSyncProtocol::class.java) }

    override val RX_BUFFER_SIZE: Int = 64 * 1024

    private var state = State.Idle
        set(value) {
            field = value
            _agencyFlow.tryEmit(agency)
        }

    private val _agencyFlow = MutableSharedFlow<Agency>(replay = 1, extraBufferCapacity = 4).apply { tryEmit(agency) }
    override val agencyFlow: Flow<Agency> = _agencyFlow

    override val agency: Agency
        get() = when (state) {
            State.Idle -> Agency.Client
            State.Done -> Agency.None
            else -> Agency.Server
        }

    var isIntersectFound = false

    // hardcode for now
    private val lastByronBlocks: List<Pair<Long, ByteArray>> = listOf(
        // testing bad block with huge token value
        // Pair(18342080L, "9d9a97918b9b77bed4e72eabb43b64b34affc929846d528babcd1d2f90976c2f".hexToByteArray()),

        // testing bad block with null value
        // Pair(23428799L, "fae8158c9fb6f53389d55ecb80b0538fde879a0b56dffc04904eb6d3359de4e3".hexToByteArray()),

        // testing hanging
        //Pair(25530527L, "cd02dd5f4e7a3198e94261d0a49335a0e0442a7dde794a161be175053781eabb".hexToByteArray()),
        // Pair(25530581L, "d9a9004c29643ff43cfe217f0e59017be359c6b864e66e795c884c5d3a7bf470".hexToByteArray()),

        Pair(4492799L, "f8084c61b6a238acec985b59310b6ecec49c0ab8352249afd7268da5cff2a457".hexToByteArray()), //mainnet
        Pair(1598399L, "7e16781b40ebf8b6da18f7b5e8ade855d6738095ef2f1c58c77e88b6e45997a4".hexToByteArray()), //testnet
        Pair(719L, "e5400faf19e712ebc5ff5b4b44cecb2b140d1cca25a011e36a91d89e97f53e2e".hexToByteArray()), //guild
    )
    private val blockSaveChannel = Channel<MsgRollForward>(Channel.UNLIMITED)

    private val chainBlocksPairs by lazy {
        chainBlocks.map { chainBlock ->
            Pair(
                chainBlock.slotNumber,
                chainBlock.hash.hexToByteArray()
            )
        }
    }

    private var _tipToIntersect: List<Pair<Long, ByteArray>>? = null
    private val tipToIntersect: List<Pair<Long, ByteArray>>
        get() = _tipToIntersect ?: chainBlocksPairs

    @OptIn(ExperimentalIoApi::class)
    override suspend fun sendData(): ByteBuffer {
        return when (state) {
            State.Idle -> {
                val payload = muxByteBufferPool.borrow()
                state = if (isIntersectFound) {
                    log.trace("MsgRequestNext")
                    MsgRequestNext().writeToBuffer(payload)
                    State.CanAwait
                } else {
                    log.trace("MsgFindIntersect")
                    MsgFindIntersect(tipToIntersect + lastByronBlocks).writeToBuffer(payload)
                    _tipToIntersect = null
                    State.Intersect
                }
                payload.flip()
            }
            else -> throw IllegalStateException("We should not call sendData() when we're in a $state state!")
        }
    }

    override fun receiveData(payload: ByteBuffer) {
        when (state) {
            State.CanAwait, State.MustReply -> {
                ByteArrayInputStream(payload.array(), payload.position(), payload.remaining()).use { byteStream ->
                    CborReader.createFromInputStream(byteStream).apply {
                        while (byteStream.available() > 0) {
                            val cborArray = try {
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
                                    MsgRollForwardAdapter.fromCborArray(cborArray).ifPresent { msgRollForward ->
                                        val isTip = msgRollForward.chainTip.hash == msgRollForward.hash

                                        if (isPooltool) {
                                            if (isTip) {
                                                // We're on tip! Send to pooltool
                                                runBlocking {
                                                    blockSaveChannel.send(msgRollForward)
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
                                            // sync mode
                                            runBlocking {
                                                blockSaveChannel.send(msgRollForward)
                                                tipBlockNumber = msgRollForward.chainTip.block
                                                tipHash = msgRollForward.chainTip.hash
                                            }
                                        }
                                    }
                                    state = State.Idle
                                }
                                MsgRollBackward.MESSAGE_ID -> {
                                    log.info("MsgRollBackward: ${cborArray.toCborByteArray().toHexString()}")
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
                                    isIntersectFound = true
                                    state = State.Idle
                                }
                                MsgIntersectNotFound.MESSAGE_ID -> {
                                    log.info("MsgIntersectNotFound: ${cborArray.toCborByteArray().toHexString()}")
//                            // Jump to the tip
//                            val (slot, hash) = ((cborArray.elementAt(1) as CborArray).elementAt(0) as CborArray).let {
//                                Pair(it.elementToLong(0), (it.elementAt(1) as CborByteString).byteArrayValue())
//                            }
//                            intersectSlot = slot
//                            intersectHash = hash
                                    isIntersectFound = true
                                    state = State.Idle
                                }
                            }
                        }
                    }
                }
            }
            else -> throw IllegalStateException("We should not call receiveData() when we're not in a $state state!")
        }
        Unit
    }

    suspend fun initBlockReceiveHandler(scope: CoroutineScope) {
        scope.launch {
            val previousBlockMap = mutableMapOf<Long, ChainBlock>()
            blockSaveChannel.consumeEach { msgRollForward ->
                if (isPooltool) {
                    // We're on tip! Send to pooltool
                    sendBlockToPooltool(msgRollForward)
                } else {
                    val previousChainBlock = previousBlockMap[msgRollForward.blockNumber - 1]
                        ?: chainRepository.findByBlockNumber(msgRollForward.blockNumber - 1)
                    previousBlockMap.remove(msgRollForward.blockNumber - 1)

                    // delete any blocks that have higher block numbers than this one in case we jumped back on a fork
                    chainRepository.deleteByBlockNumberAndAbove(msgRollForward.blockNumber)

                    // evolve the etaV nonce value
                    val previousEtaV = previousChainBlock?.etaV?.hexToByteArray() ?: shelleyGenesisHash
                    val eta = SodiumLibrary.cryptoBlake2bHash(msgRollForward.etaVrf.hexToByteArray(), null)
                    val etaV = SodiumLibrary.cryptoBlake2bHash(previousEtaV + eta, null).toHexString()

                    // add this block to the database
                    val savedChainBlock = chainRepository.save(
                        ChainBlock(
                            blockNumber = msgRollForward.blockNumber,
                            slotNumber = msgRollForward.slotNumber,
                            hash = msgRollForward.hash,
                            prevHash = msgRollForward.prevHash,
                            etaV = etaV,
                            poolId = nodeVKeyToPoolId(msgRollForward.nodeVKey),
                            leaderVrf = msgRollForward.leaderVrf,
                        )
                    )
                    previousBlockMap[savedChainBlock.blockNumber] = savedChainBlock

                    val isTip = msgRollForward.chainTip.hash == msgRollForward.hash
                    if (canLog() || isTip) {
                        log.info(
                            "ChainSync: Saved block: ${msgRollForward.blockNumber} of ${msgRollForward.chainTip.block}, %.2f%% synced, poolId: ${
                                savedChainBlock.poolId.substring(0..8)
                            }...".format(
                                msgRollForward.blockNumber.toDouble() / msgRollForward.chainTip.block * 100.0
                            )
                        )

                        // Notify that a new block has arrived. If we're not on tip, we don't emit every block so
                        // we can hopefully BlockFetch a group at a time.
                        newBlockMutableSharedFlow.emit(msgRollForward.hash)
                    }
                }
            }
        }
    }

    private var lastNodeVersionTime = 0L
    private var nodeVersion = ""

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun sendBlockToPooltool(msgRollForward: MsgRollForward) {
        val now = System.currentTimeMillis()
        if (now - lastNodeVersionTime > 3600L) {
            val hostConnection = HostConnection(host)
            val versionString = hostConnection.command("${host.cardanoNodePath} --version").trim()
            Regex("cardano-node (\\d+\\.\\d+\\.\\d+) .*\ngit rev ([a-f0-9]{5}).*").matchEntire(versionString)
                ?.let { matchResult ->
                    nodeVersion = "${matchResult.groupValues[1]}:${matchResult.groupValues[2]}"
                }
            lastNodeVersionTime = now
        }
        try {

            val at = DateTime(now, DateTimeZone.UTC).toString(ISODateTimeFormat.dateTime())
            val stats = PooltoolStats(
                apiKey = pooltoolApiKey,
                poolId = poolId,
                data = Data(
                    nodeId = "", // future use
                    version = nodeVersion,
                    at = at, // 2020-12-12T23:47:04.112Z
                    blockNo = msgRollForward.blockNumber,
                    slotNo = msgRollForward.slotNumber,
                    blockHash = msgRollForward.hash,
                    parentHash = msgRollForward.prevHash,
                    leaderVrf = msgRollForward.leaderVrf,
                    leaderVrfProof = msgRollForward.leaderVrfProof,
                    nodeVKey = msgRollForward.nodeVKey,
                )
            )
            log.info("Pooltool Request: $stats")
            val response = pooltoolService.sendStats(stats)
            log.debug("pooltool response: ${response.body()}")
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

    private var nextLogTimeDebug = System.currentTimeMillis()
    private fun canLogDebug(updateNext: Boolean = true): Boolean {
        val now = System.currentTimeMillis()
        return if (!isPooltool && now > nextLogTimeDebug) {
            if (updateNext) {
                nextLogTimeDebug = now + 10_000L
            }
            true
        } else {
            false
        }
    }

    private var nextLogTime = System.currentTimeMillis()
    private fun canLog(updateNext: Boolean = true): Boolean {
        val now = System.currentTimeMillis()
        return if (!isPooltool && now > nextLogTime) {
            if (updateNext) {
                nextLogTime = now + 10_000L
            }
            true
        } else {
            false
        }
    }

    enum class State {
        Idle,
        Intersect,
        CanAwait,
        MustReply,
        Done
    }

    companion object {
        private val newBlockMutableSharedFlow = MutableSharedFlow<String>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val newBlockFlow = newBlockMutableSharedFlow.distinctUntilChanged()
        var tipBlockNumber: Long = 0L
        var tipHash: String = ""
    }
}