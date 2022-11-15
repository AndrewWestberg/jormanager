package com.swiftmako.jormanager.nodeclient.protocols.txsubmission2

import com.firehose.controllers.nodeclient.protocol.Agency
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import com.swiftmako.jormanager.nodeclient.protocols.mux.muxByteBufferPool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer

class TxSubmission2Protocol : MiniProtocol(protocolId = 0x0004.toShort()) {

    private val log by lazy { LoggerFactory.getLogger("TxSubmission2Protocol") }

    override val RX_BUFFER_SIZE: Int = 64 * 1024

    private var state = State.Idle
        set(value) {
            field = value
            if (!_agencyFlow.tryEmit(agency)) {
                log.error("Failed to emit Agency!")
            }
        }

    private val _agencyFlow = MutableSharedFlow<Agency>(
        replay = 1,
        extraBufferCapacity = 4
    ).apply { tryEmit(agency) }
    override val agencyFlow: Flow<Agency> = _agencyFlow

    override val agency: Agency
        get() = when (state) {
            State.Idle -> Agency.Server
            State.Init, State.TxIdsNonBlocking, State.TxIdsBlocking, State.Txs -> Agency.Client
            State.Done -> Agency.None
        }

    enum class State {
        Init,
        Idle,
        TxIdsNonBlocking,
        TxIdsBlocking,
        Txs,
        Done
    }

    override fun shutdown() {
        state = State.Done
    }

    override suspend fun sendData(): ByteBuffer {
        //log.info("sendData(): state = $state")
        val payload = muxByteBufferPool.borrow()
        return when (state) {
            State.Init -> {
                // move the agency to the server to request tx info from us
                MsgInit().writeToBuffer(payload)
                state = State.Idle
                payload.flip()
            }

            else -> throw IllegalStateException("We should not call sendData() when we're in a $state state!")
        }
    }

    override fun receiveData(payload: ByteBuffer) {
//        ByteArrayInputStream(payload.array(), payload.position(), payload.remaining()).use { byteStream ->
//            CborReader.createFromInputStream(byteStream).apply {
//                while (byteStream.available() > 0) {
//                    val cborArray = try {
//                        readDataItem() as CborArray
//                    } catch (e: Throwable) {
//                        log.error("Error parsing cbor (position: ${payload.position()}, limit: ${payload.limit()}, remaining: ${payload.remaining()}: ${payload.array()}")
//                        throw e
//                    }
//                    val messageId: Long = cborArray.elementToLong(0)
//                    when (state) {
//                        State.Busy -> {
//                            when (messageId) {
//                                MsgNoBlocks.MESSAGE_ID -> {
//                                    log.warn("MsgNoBlocks")
//                                    state = State.Idle
//                                }
//
//                                MsgStartBatch.MESSAGE_ID -> {
//                                    //log.warn("MsgStartBatch")
//                                    state = State.Streaming
//                                }
//                            }
//                        }
//
//                        State.Streaming -> {
//                            when (messageId) {
//                                MsgBatchDone.MESSAGE_ID -> {
//                                    //log.warn("MsgBatchDone")
//
//                                    commitBlocksJob = launch {
//                                        val isTip = blockBuffer.last().hash == ChainSyncProtocol.tipHash
//                                        ledgerDao.commitBlocks(blockBuffer, isTip)
//                                        blockBuffer.clear()
//                                        TxSubmission2Protocol.isTip = isTip
//                                    }
//
//                                    state = State.Idle
//                                }
//
//                                MsgBlock.MESSAGE_ID -> {
//                                    //log.warn("MsgBlock: ${blockBuffer.size + 1}")
//                                    processBlock(cborArray)
//                                    state = State.Streaming
//                                }
//                            }
//                        }
//
//                        else -> throw IllegalStateException("We should not call receiveData() when we're in a $state state!")
//                    }
//                }
//            }
//        }
    }
}