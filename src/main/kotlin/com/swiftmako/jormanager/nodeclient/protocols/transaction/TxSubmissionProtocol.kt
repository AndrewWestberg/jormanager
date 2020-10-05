package com.swiftmako.jormanager.nodeclient.protocols.transaction

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborReader
import com.google.iot.cbor.CborSimple
import com.swiftmako.jormanager.ktx.elementToLong
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import com.swiftmako.jormanager.nodeclient.utils.BufferPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import org.slf4j.LoggerFactory

/**
 * In JorManager we don't want to participate in Transaction passing, but we still need to tell the server that we
 * have no transactions in the non-blocking state. We can transition to Done from the blocking state to disconnect
 * this protocol.
 */
class TxSubmissionProtocol : MiniProtocol(protocolId = 0x0004, LoggerFactory.getLogger("TxSubmissionProtocol")) {

    var state: State = State.Idle

    override fun startAsync(scope: CoroutineScope) = scope.async {
        log.info("Starting TxSubmissionProtocol...")
        while (true) {
            when (state) {
                State.Idle -> {
                    log.debug("State.Idle")
                    val rxBuffer = rxChannel.receive()
                    try {
                        CborReader.createFromByteArray(rxBuffer.array(), rxBuffer.position(), 1).apply {
                            val cborArray = readDataItem() as CborArray
                            log.debug("received: ${cborArray.toJsonString()}")
                            when (val messageId = cborArray.elementToLong(0)) {
                                //msgRequestTxIds = [0, tsBlocking, txCount, txCount]
                                //msgReplyTxIds   = [1, [ *txIdAndSize] ]
                                //msgRequestTxs   = [2, tsIdList ]
                                //msgReplyTxs     = [3, tsIdList ]
                                //tsMsgDone       = [4]
                                //msgReplyKTnxBye = [5]
                                0L -> {
                                    val isBlocking = cborArray.elementAt(1) == CborSimple.TRUE
                                    state = if (isBlocking) {
                                        State.TxIdsBlocking
                                    } else {
                                        State.TxIdsNonBlocking
                                    }
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
                State.TxIdsBlocking -> {
                    log.debug("State.TxIdsBlocking")
//                            // Tell the server that we don't want to use this MiniProtocol
//                            val txBuffer = BufferPool.borrow()
//                            MsgDone().writeToBuffer(txBuffer)
//                            txBuffer.flip()
//                            txChannel.send(txBuffer)
                    state = State.Done
                }
                State.TxIdsNonBlocking -> {
                    log.debug("State.TxIdsNonBlocking")
                    // Tell the server that we have no transactions to send them
                    val txBuffer = BufferPool.borrow()
                    ReplyTxIds().writeToBuffer(txBuffer)
                    txBuffer.flip()
                    txChannel.send(txBuffer)
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

        log.info("TxSubmissionProtocol exited.")
    }

    enum class State {
        Idle,
        TxIdsNonBlocking,
        TxIdsBlocking,
        Txs,
        Done
    }

}