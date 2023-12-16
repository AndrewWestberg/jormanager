package com.swiftmako.jormanager.nodeclient.protocols.keepalive

import com.firehose.controllers.nodeclient.protocol.Agency
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborReader
import com.swiftmako.jormanager.ktx.elementToLong
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import com.swiftmako.jormanager.nodeclient.protocols.mux.muxByteBufferPool
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.time.Instant

class KeepAliveProtocol(delaySeconds: Long = 10L) : MiniProtocol(protocolId = 0x0008.toShort()) {
    private val log by lazy { LoggerFactory.getLogger("KeepAliveProtocol") }

    private var cookie: Short = 0
    private var nextSendTime: Instant = Instant.now().plusSeconds(delaySeconds)

    private var state = State.Client
        set(value) {
            field = value
            _agencyFlow.tryEmit(agency)
        }

    private val _agencyFlow = MutableSharedFlow<Agency>(replay = 1, extraBufferCapacity = 4).apply { tryEmit(agency) }
    override val agencyFlow: Flow<Agency> = _agencyFlow

    override val RX_BUFFER_SIZE: Int = 64 * 1024

    override val agency: Agency
        get() = when (state) {
            State.Client -> Agency.Client
            State.Server -> Agency.Server
            State.Done -> Agency.None
        }

    enum class State {
        Client,
        Server,
        Done,
    }

    override suspend fun sendData(): ByteBuffer {
        return when (state) {
            State.Client -> {
                val delayTime = nextSendTime.toEpochMilli() - Instant.now().toEpochMilli()
                if (delayTime > 0) {
                    delay(delayTime)
                }

                cookie = (0..Short.MAX_VALUE).random().toShort()
                log.debug("Sending cookie: {}", cookie)
                val payload = muxByteBufferPool.borrow()
                MsgKeepAlive(cookie).writeToBuffer(payload)
                state = State.Server
                payload.flip()
            }

            else -> throw IllegalStateException("We should not call sendData() when we're in a $state state!")
        }

    }

    override fun receiveData(payload: ByteBuffer) {
        when (state) {
            State.Server -> {
                ByteArrayInputStream(payload.array(), payload.position(), payload.remaining()).use { byteStream ->
                    CborReader.createFromInputStream(byteStream).apply {
                        while (byteStream.available() > 0) {
                            val cborArray = readDataItem() as CborArray
                            when (val messageId: Long = cborArray.elementToLong(0)) {
                                MsgKeepAliveResponse.MESSAGE_ID -> {
                                    val msgKeepAliveResponse = MsgKeepAliveResponse(cborArray)
                                    log.debug("Received cookie: {}", msgKeepAliveResponse.cookie)
                                    if (msgKeepAliveResponse.cookie != cookie) {
                                        throw IllegalStateException("Cookie mismatch! -> ${msgKeepAliveResponse.cookie} != $cookie}")
                                    }
                                }

                                else -> throw IllegalStateException("Unknown message ID: $messageId")
                            }
                        }
                    }
                }
                state = State.Client
                nextSendTime = Instant.now().plusSeconds(10L)
            }

            else -> throw IllegalStateException("We should not call receiveData() when we're in a $state state!")
        }
    }

    override fun shutdown() {
        state = State.Done
    }
}