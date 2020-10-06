package com.swiftmako.jormanager.nodeclient.protocols.chainsync

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborReader
import com.swiftmako.jormanager.entities.ChainBlock
import com.swiftmako.jormanager.ktx.elementToLong
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import com.swiftmako.jormanager.nodeclient.utils.BufferPool
import com.swiftmako.jormanager.repositories.ChainRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class ChainSyncProtocol(private val chainBlocks: List<ChainBlock>, private val chainRepository: ChainRepository) : MiniProtocol(protocolId = 0x0002.toShort(), LoggerFactory.getLogger("ChainSyncProtocol")) {

    var state: State = State.Idle
    var isIntersectFound = false

    private val blockSaveChannel = Channel<MsgRollForward>(Channel.UNLIMITED)

    override fun startAsync(scope: CoroutineScope) = scope.async {
        log.info("Starting ChainSyncProtocol...")
        saveToChain(scope)
        while (true) {
            when (state) {
                State.Idle -> {
                    if (canLogDebug(false)) {
                        log.debug("State.Idle")
                    }
                    state = if (!isIntersectFound) {
                        val txBuffer = BufferPool.borrow()
                        MsgFindIntersect(chainBlocks).writeToBuffer(txBuffer)
                        txBuffer.flip()
                        txChannel.send(txBuffer)
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
                                    blockSaveChannel.send(msgRollForward)
                                    if (canLogDebug()) {
                                        log.debug("CanAwait->RollForward: $msgRollForward")
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
                                    blockSaveChannel.send(msgRollForward)
                                    if (canLogDebug()) {
                                        log.debug("CanAwait->RollForward: $msgRollForward")
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

    private fun saveToChain(scope: CoroutineScope) {
        scope.launch {
            val pendingBlockMap = mutableMapOf<Long, ChainBlock>()
            blockSaveChannel.consumeEach { msgRollForward ->
                // update the hash value for the previous block now that we know it
                val previousChainBlock = pendingBlockMap[msgRollForward.blockNumber - 1]
                        ?: chainRepository.findByBlockNumber(msgRollForward.blockNumber - 1)
                previousChainBlock?.let {
                    chainRepository.save(previousChainBlock.copy(hash = msgRollForward.prevHash))
                }
                pendingBlockMap.remove(msgRollForward.blockNumber - 1)

                // delete any blocks that have higher block numbers than this one in case we jumped back on a fork
                chainRepository.deleteByBlockNumberAndAbove(msgRollForward.blockNumber)

                // add this block to the database
                val savedChainBlock = chainRepository.save(
                        ChainBlock(
                                blockNumber = msgRollForward.blockNumber,
                                slotNumber = msgRollForward.slotNumber,
                                prevHash = msgRollForward.prevHash
                        )
                )
                pendingBlockMap[savedChainBlock.blockNumber] = savedChainBlock

                if (canLog()) {
                    log.info("ChainSync: Saved block: ${msgRollForward.blockNumber}, slot: ${msgRollForward.slotNumber}")
                }
            }
        }
    }

    private var nextLogTimeDebug = System.currentTimeMillis()
    private fun canLogDebug(updateNext: Boolean = true): Boolean {
        val now = System.currentTimeMillis()
        return if (now > nextLogTimeDebug) {
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
        return if (now > nextLogTime) {
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