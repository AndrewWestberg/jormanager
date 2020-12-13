package com.swiftmako.jormanager.nodeclient.protocols.chainsync

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborReader
import com.muquit.libsodiumjna.SodiumLibrary
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.ChainBlock
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.ktx.elementToLong
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.pooltool.Data
import com.swiftmako.jormanager.model.pooltool.PooltoolStats
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import com.swiftmako.jormanager.nodeclient.utils.BufferPool
import com.swiftmako.jormanager.repositories.ChainRepository
import com.swiftmako.jormanager.services.PooltoolService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import org.joda.time.format.ISODateTimeFormat
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import java.io.IOException

class ChainSyncProtocol(
    private val host: Host,
    private val shelleyGenesisHash: ByteArray,
    private val chainBlocks: List<ChainBlock>,
    private val chainRepository: ChainRepository,
    private val isPooltool: Boolean,
    private val pooltoolService: PooltoolService,
    private val pooltoolApiKey: String,
    private val poolId: String,
) : MiniProtocol(protocolId = 0x0002.toShort(), LoggerFactory.getLogger("ChainSyncProtocol")) {

    var state: State = State.Idle
    var isIntersectFound = false

    private val blockSaveChannel = Channel<MsgRollForward>(Channel.UNLIMITED)

    private var _tipToIntersect: List<ChainBlock>? = null
    private val tipToIntersect: List<ChainBlock>
        get() = _tipToIntersect ?: chainBlocks

    override fun startAsync(scope: CoroutineScope) = scope.async {
        log.info("Starting ChainSyncProtocol...")
        handleBlockReceived(scope)
        while (true) {
            when (state) {
                State.Idle -> {
                    if (canLogDebug(false)) {
                        log.debug("State.Idle")
                    }
                    state = if (!isIntersectFound) {
                        val txBuffer = BufferPool.borrow()
                        MsgFindIntersect(tipToIntersect).writeToBuffer(txBuffer)
                        txBuffer.flip()
                        txChannel.send(txBuffer)
                        _tipToIntersect = null
                        State.Intersect
                    } else {
                        val txBuffer = BufferPool.borrow()
                        MsgRequestNext().writeToBuffer(txBuffer)
                        txBuffer.flip()
                        txChannel.send(txBuffer)
                        State.CanAwait
                    }
                }
                State.CanAwait -> {
                    if (canLogDebug(false)) {
                        log.debug("State.CanAwait")
                    }
                    val rxBuffer = rxChannel.receive()
                    try {
                        val pos = rxBuffer.position()
                        val limit = rxBuffer.limit()
                        val remaining = rxBuffer.remaining()
                        val bytes = ByteArray(remaining)
                        rxBuffer.get(bytes)
                        rxBuffer.position(pos)
                        rxBuffer.limit(limit)
                        if (canLogDebug(false)) {
                            log.debug("received ${bytes.toHexString()}")
                        }

                        CborReader.createFromByteArray(rxBuffer.array(), rxBuffer.position(), 1).apply {
                            val cborArray = readDataItem() as CborArray
                            //log.debug("received: ${cborArray.toJsonString()}")
                            when (val messageId = cborArray.elementToLong(0)) {
                                //msgRequestNext         = [0]
                                //msgAwaitReply          = [1]
                                //msgRollForward         = [2, wrappedHeader, tip]
                                //msgRollBackward        = [3, point, tip]
                                //msgFindIntersect       = [4, points]
                                //msgIntersectFound      = [5, point, tip]
                                //msgIntersectNotFound   = [6, tip]
                                //chainSyncMsgDone       = [7]
                                1L -> {
                                    // Server wants us to wait a bit until it gets a new block
                                    state = State.MustReply
                                }
                                2L -> {
                                    // Roll forward
                                    val msgRollForward = MsgRollForwardAdapter.fromCborArray(cborArray)
                                    if (isPooltool) {
                                        // SendTip mode
                                        if (msgRollForward.slotNumber == msgRollForward.chainTip.slot && msgRollForward.hash == msgRollForward.chainTip.hash) {
                                            // We're on tip! Send to pooltool
                                            blockSaveChannel.send(msgRollForward)
                                            state = State.Idle
                                        } else {
                                            _tipToIntersect = mutableListOf(
                                                ChainBlock(
                                                    slotNumber = msgRollForward.chainTip.slot,
                                                    hash = msgRollForward.chainTip.hash,
                                                    blockNumber = 0L,
                                                    prevHash = "",
                                                    etaV = "",
                                                    poolId = "",
                                                    leaderVrf = ""
                                                )
                                            ).apply {
                                                addAll(chainBlocks)
                                            }
                                            isIntersectFound = false
                                        }
                                    } else {
                                        // Sync mode
                                        blockSaveChannel.send(msgRollForward)
                                        if (canLogDebug()) {
                                            log.debug("CanAwait->RollForward: $msgRollForward")
                                        }
                                    }
                                    state = State.Idle
                                    //state = State.Done
                                }
                                3L -> {
                                    // Roll backward
                                    val point = cborArray.elementAt(1)
                                    val tip = cborArray.elementAt(2)
                                    if (canLogDebug()) {
                                        log.debug("CanAwait->RollBackward: point: ${point.toJsonString()}, tip: ${tip.toJsonString()}")
                                    }
                                    state = State.Idle
                                }
                                else -> {
                                    log.error("Got unexpected messageId: $messageId")
                                }
                            }
                        }
                    } finally {
                        BufferPool.recycle(rxBuffer)
                    }
                }
                State.MustReply -> {
                    if (canLogDebug(false)) {
                        log.debug("State.MustReply")
                    }
                    val rxBuffer = rxChannel.receive()
                    try {
                        val pos = rxBuffer.position()
                        val limit = rxBuffer.limit()
                        val remaining = rxBuffer.remaining()
                        val bytes = ByteArray(remaining)
                        rxBuffer.get(bytes)
                        rxBuffer.position(pos)
                        rxBuffer.limit(limit)
                        if (canLogDebug(false)) {
                            log.debug("received ${bytes.toHexString()}")
                        }

                        CborReader.createFromByteArray(rxBuffer.array(), rxBuffer.position(), 1).apply {
                            val cborArray = readDataItem() as CborArray
                            //log.debug("received: ${cborArray.toJsonString()}")
                            when (val messageId = cborArray.elementToLong(0)) {
                                //msgRequestNext         = [0]
                                //msgAwaitReply          = [1]
                                //msgRollForward         = [2, wrappedHeader, tip]
                                //msgRollBackward        = [3, point, tip]
                                //msgFindIntersect       = [4, points]
                                //msgIntersectFound      = [5, point, tip]
                                //msgIntersectNotFound   = [6, tip]
                                //chainSyncMsgDone       = [7]
                                2L -> {
                                    // Roll forward
                                    val msgRollForward = MsgRollForwardAdapter.fromCborArray(cborArray)
                                    if (isPooltool) {
                                        // SendTip mode
                                        if (msgRollForward.slotNumber == msgRollForward.chainTip.slot && msgRollForward.hash == msgRollForward.chainTip.hash) {
                                            // We're on tip! Send to pooltool
                                            blockSaveChannel.send(msgRollForward)
                                            state = State.Idle
                                        } else {
                                            _tipToIntersect = mutableListOf(
                                                ChainBlock(
                                                    slotNumber = msgRollForward.chainTip.slot,
                                                    hash = msgRollForward.chainTip.hash,
                                                    blockNumber = 0L,
                                                    prevHash = "",
                                                    etaV = "",
                                                    poolId = "",
                                                    leaderVrf = ""
                                                )
                                            ).apply {
                                                addAll(chainBlocks)
                                            }
                                            isIntersectFound = false
                                        }
                                    } else {
                                        // Sync mode
                                        blockSaveChannel.send(msgRollForward)
                                        if (canLogDebug()) {
                                            log.debug("MustReply->RollForward: $msgRollForward")
                                        }
                                    }
                                    state = State.Idle
                                    //state = State.Done
                                }
                                3L -> {
                                    // Roll backward
                                    val point = cborArray.elementAt(1)
                                    val tip = cborArray.elementAt(2)
                                    if (canLogDebug()) {
                                        log.debug("MustReply->RollBackward: point: ${point.toJsonString()}, tip: ${tip.toJsonString()}")
                                    }
                                    state = State.Idle
                                }
                                else -> {
                                    log.error("Got unexpected messageId: $messageId")
                                }
                            }
                        }
                    } finally {
                        BufferPool.recycle(rxBuffer)
                    }
                }
                State.Intersect -> {
                    log.debug("State.Intersect")
                    val rxBuffer = rxChannel.receive()
                    try {
                        val pos = rxBuffer.position()
                        val limit = rxBuffer.limit()
                        val remaining = rxBuffer.remaining()
                        val bytes = ByteArray(remaining)
                        rxBuffer.get(bytes)
                        rxBuffer.position(pos)
                        rxBuffer.limit(limit)
                        log.debug("intersect msg: ${bytes.toHexString()}")
                    } finally {
                        BufferPool.recycle(rxBuffer)
                    }
                    isIntersectFound = true
                    state = State.Idle
                }
                State.Done -> {
                    log.debug("State.Done")
                    txChannel.cancel()
                    rxChannel.cancel()
                    break
                }

            }
        }
        log.info("ChainSyncProtocol exited.")
    }

    private fun handleBlockReceived(scope: CoroutineScope) {
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

                    if (canLog()) {
                        log.info(
                            "ChainSync: Saved block: ${msgRollForward.blockNumber}, slot: ${msgRollForward.slotNumber}, poolId: ${
                                savedChainBlock.poolId.substring(
                                    0..8
                                )
                            }..."
                        )
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
}