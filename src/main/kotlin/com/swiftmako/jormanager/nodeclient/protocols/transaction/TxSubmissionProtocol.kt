package com.swiftmako.jormanager.nodeclient.protocols.transaction

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborReader
import com.swiftmako.jormanager.ktx.elementToLong
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * In JorManager we don't want to participate in Transaction passing, but we still need to tell the server that we
 * have no transactions in the non-blocking state. We can transition to Done from the blocking state to disconnect
 * this protocol.
 */
class TxSubmissionProtocol : MiniProtocol(protocolId = 0x0004, LoggerFactory.getLogger("TxSubmissionProtocol")) {

    var state: State = State.Idle

    override suspend fun start() {
        log.info("Starting TxSubmissionProtocol...")
        coroutineScope {
            launch {
                while (true) {
                    when (state) {
                        State.Idle -> {
                            log.debug("State.Idle")
                            val rxBuffer = rxChannel.receive()
                            val cborReader = CborReader.createFromByteArray(rxBuffer.array(), rxBuffer.position())
                            while (cborReader.hasRemainingDataItems()) {
                                val cborArray = cborReader.readDataItem() as CborArray
                                when (cborArray.elementToLong(0)) {
                                    //msgRequestTxIds = [0, tsBlocking, txCount, txCount]
                                    //msgReplyTxIds   = [1, [ *txIdAndSize] ]
                                    //msgRequestTxs   = [2, tsIdList ]
                                    //msgReplyTxs     = [3, tsIdList ]
                                    //tsMsgDone       = [4]
                                    //msgReplyKTnxBye = [5]
                                    0L -> {

                                    }
                                    1L -> {

                                    }
                                    2L -> {

                                    }
                                    3L -> {

                                    }
                                    4L -> {

                                    }
                                    5L -> {
                                        
                                    }
                                }
                            }
                        }
//                        HandshakeProtocol.State.PROPOSE -> {
//                            log.debug("State.PROPOSE")
//                            val txBuffer = BufferPool.borrow()
//                            MsgProposeVersions(networkMagic).writeToBuffer(txBuffer)
//                            txBuffer.flip()
//                            txChannel.send(txBuffer)
//                            state = HandshakeProtocol.State.CONFIRM
//                        }
//                        HandshakeProtocol.State.CONFIRM -> {
//                            log.debug("State.CONFIRM")
//                            try {
//                                val rxBuffer = rxChannel.receive()
//                                handleConfirm(rxBuffer)
//                                BufferPool.recycle(rxBuffer)
//                            } finally {
//                                state = HandshakeProtocol.State.DONE
//                            }
//                        }
//                        HandshakeProtocol.State.DONE -> {
//                            log.debug("State.DONE")
//                            txChannel.cancel()
//                            rxChannel.cancel()
//                            break
//                        }
                    }
                }

                log.info("TxSubmissionProtocol exited.")
            }.join()
        }
    }

    enum class State {
        Idle,
        TxIdsNonBlocking,
        TxIdsBlocking,
        Txs,
        Done
    }

}