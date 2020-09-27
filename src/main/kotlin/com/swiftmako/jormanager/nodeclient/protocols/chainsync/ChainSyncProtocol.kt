package com.swiftmako.jormanager.nodeclient.protocols.chainsync

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborReader
import com.swiftmako.jormanager.ktx.elementToLong
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import com.swiftmako.jormanager.nodeclient.utils.BufferPool
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class ChainSyncProtocol : MiniProtocol(protocolId = 0x0002.toShort(), LoggerFactory.getLogger("ChainSyncProtocol")) {

    var state: State = State.Idle

    var isIntersectFound = false

    override suspend fun start() {
        log.info("Starting ChainSyncProtocol...")
        coroutineScope {
            launch {
                while (true) {
                    when (state) {
                        State.Idle -> {
                            if (canLog(false)) {
                                log.debug("State.Idle")
                            }
                            state = if (!isIntersectFound) {
                                val txBuffer = BufferPool.borrow()
                                MsgFindIntersect().writeToBuffer(txBuffer)
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
                            if (canLog(false)) {
                                log.debug("State.CanAwait")
                            }
                            val rxBuffer = rxChannel.receive()
                            try {
                                // TODO remove debug code
                                val pos = rxBuffer.position()
                                val limit = rxBuffer.limit()
                                val remaining = rxBuffer.remaining()
                                val bytes = ByteArray(remaining)
                                rxBuffer.get(bytes)
                                rxBuffer.position(pos)
                                rxBuffer.limit(limit)
                                if (canLog(false)) {
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
                                            if (canLog()) {
                                                log.debug("CanAwait->RollForward: $msgRollForward")
                                            }
                                            state = State.Idle
                                            //state = State.Done
                                        }
                                        3L -> {
                                            // Roll backward
                                            val point = cborArray.elementAt(1)
                                            val tip = cborArray.elementAt(2)
                                            if (canLog()) {
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
                            if (canLog(false)) {
                                log.debug("State.MustReply")
                            }
                            val rxBuffer = rxChannel.receive()
                            try {
                                // TODO remove debug code
                                val pos = rxBuffer.position()
                                val limit = rxBuffer.limit()
                                val remaining = rxBuffer.remaining()
                                val bytes = ByteArray(remaining)
                                rxBuffer.get(bytes)
                                rxBuffer.position(pos)
                                rxBuffer.limit(limit)
                                if (canLog(false)) {
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
                                            if (canLog()) {
                                                log.debug("CanAwait->RollForward: $msgRollForward")
                                            }
                                            state = State.Idle
                                            //state = State.Done
                                        }
                                        3L -> {
                                            // Roll backward
                                            val point = cborArray.elementAt(1)
                                            val tip = cborArray.elementAt(2)
                                            if (canLog()) {
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
                                // TODO remove debug code
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