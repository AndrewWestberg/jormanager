package com.swiftmako.jormanager.nodeclient.protocols.handshake

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborReader
import com.swiftmako.jormanager.ktx.elementToLong
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import com.swiftmako.jormanager.nodeclient.utils.BufferPool
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.RENDEZVOUS
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.ByteBuffer

class HandshakeProtocol(private val networkMagic: Long) : MiniProtocol(protocolId = 0x0000, LoggerFactory.getLogger("HandshakeProtocol")) {

    var state: State = State.PROPOSE

    override suspend fun start() {
        log.info("Starting HandshakeProtocol...")
        coroutineScope {
            launch {
                while (true) {
                    when (state) {
                        State.PROPOSE -> {
                            log.debug("State.PROPOSE")
                            val txBuffer = BufferPool.borrow()
                            MsgProposeVersions(networkMagic).writeToBuffer(txBuffer)
                            txBuffer.flip()
                            txChannel.send(txBuffer)
                            state = State.CONFIRM
                        }
                        State.CONFIRM -> {
                            log.debug("State.CONFIRM")
                            try {
                                val rxBuffer = rxChannel.receive()
                                handleConfirm(rxBuffer)
                                BufferPool.recycle(rxBuffer)
                            } finally {
                                state = State.DONE
                            }
                        }
                        State.DONE -> {
                            log.debug("State.DONE")
                            txChannel.cancel()
                            rxChannel.cancel()
                            break
                        }
                    }
                }

                log.info("HandshakeProtocol exited.")
            }.join()
        }
    }

    private fun handleConfirm(rxBuffer: ByteBuffer) {
        try {
            CborReader.createFromByteArray(rxBuffer.array(), rxBuffer.position(), 1).apply {
                val cborArray = readDataItem() as CborArray
                val messageId: Long = cborArray.elementToLong(0)
                when (messageId) {
                    MsgAcceptVersion.MESSAGE_ID -> {
                        val msgAcceptVersion = MsgAcceptVersion(cborArray)
                        if (msgAcceptVersion.extraParams != networkMagic) {
                            throw IOException("Handshake succeeded, but networkMagic did not match!")
                        }
                        log.info("Handshake Successful: $msgAcceptVersion")
                    }
                    MsgRefuse.MESSAGE_ID -> {
                        val msgRefuse = MsgRefuse(cborArray)
                        throw IOException("Handshake Failed: $msgRefuse")
                    }
                    else -> {
                        throw IOException("Unexpected Message: ${cborArray.toJsonString()}")
                    }
                }
            }
        } finally {
            BufferPool.recycle(rxBuffer)
        }
    }

    enum class State {
        PROPOSE,
        CONFIRM,
        DONE
    }
}