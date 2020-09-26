package com.swiftmako.jormanager.nodeclient.protocols.chainsync

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborReader
import com.swiftmako.jormanager.ktx.elementToLong
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import com.swiftmako.jormanager.nodeclient.utils.BufferPool
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class ChainSyncProtocol : MiniProtocol(protocolId = 0x0002.toShort(), LoggerFactory.getLogger("ChainSyncProtocol")) {

    var state: State = State.Idle

    override suspend fun start() {
        log.info("Starting ChainSyncProtocol...")
        coroutineScope {
            launch {
                while (true) {
                    when (state) {
                        State.Idle -> {
                            log.debug("State.Idle")
                            val txBuffer = BufferPool.borrow()
                            MsgRequestNext().writeToBuffer(txBuffer)
                            txBuffer.flip()
                            txChannel.send(txBuffer)
                            state = State.CanAwait
                        }
                        State.CanAwait -> {
                            log.debug("State.CanAwait")
                            val rxBuffer = rxChannel.receive()
                            try {
                                CborReader.createFromByteArray(rxBuffer.array(), rxBuffer.position(), 1).apply {
                                    val cborArray = readDataItem() as CborArray
                                    log.debug("received: ${cborArray.toJsonString()}")
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
                                            val wrappedHeader = cborArray.elementAt(1)
                                            val tip = cborArray.elementAt(2)
                                            log.debug("CanAwait->RollForward: wrappedHeader: ${wrappedHeader.toJsonString()}, tip: ${tip.toJsonString()}")
                                            state = State.Idle
                                        }
                                        3L -> {
                                            // Roll backward
                                            val point = cborArray.elementAt(1)
                                            val tip = cborArray.elementAt(2)
                                            log.debug("CanAwait->RollBackward: point: ${point.toJsonString()}, tip: ${tip.toJsonString()}")
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
                            log.debug("State.MustReply")
                            val rxBuffer = rxChannel.receive()
                            try {
                                CborReader.createFromByteArray(rxBuffer.array(), rxBuffer.position(), 1).apply {
                                    val cborArray = readDataItem() as CborArray
                                    log.debug("received: ${cborArray.toJsonString()}")
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
                                            val wrappedHeader = cborArray.elementAt(1)
                                            val tip = cborArray.elementAt(2)
                                            log.debug("MustReply->RollForward: wrappedHeader: ${wrappedHeader.toJsonString()}, tip: ${tip.toJsonString()}")
                                            state = State.Idle
                                        }
                                        3L -> {
                                            // Roll backward
                                            val point = cborArray.elementAt(1)
                                            val tip = cborArray.elementAt(2)
                                            log.debug("MustReply->RollBackward: point: ${point.toJsonString()}, tip: ${tip.toJsonString()}")
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
                    }
                }
//                log.info("ChainSyncProtocol exited.")
            }
        }
    }

    enum class State {
        Idle,
        Intersect,
        CanAwait,
        MustReply
    }
}