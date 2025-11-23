package com.swiftmako.jormanager.nodeclient.protocols.handshake

import com.firehose.controllers.nodeclient.protocol.Agency
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborReader
import com.swiftmako.jormanager.ktx.elementToLong
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import com.swiftmako.jormanager.nodeclient.protocols.mux.muxByteBufferPool
import io.ktor.utils.io.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.io.use

class HandshakeProtocol(
    private val networkMagic: Long
) : MiniProtocol(protocolId = 0x0000) {
    private val log by lazy { LoggerFactory.getLogger("HandshakeProtocol") }

    private var state = State.Propose
        set(value) {
            field = value
            _agencyFlow.tryEmit(agency)
        }

    private val _agencyFlow = MutableSharedFlow<Agency>(replay = 1, extraBufferCapacity = 4).apply { tryEmit(agency) }
    override val agencyFlow: Flow<Agency> = _agencyFlow

    override val rxBufferSize: Int = 64 * 1024

    override val agency: Agency
        get() =
            when (state) {
                State.Propose -> Agency.Client
                State.Confirm -> Agency.Server
                State.Done -> Agency.None
            }

    lateinit var msgAcceptVersion: MsgAcceptVersion

    override fun shutdown() {
        state = State.Done
    }

    override suspend fun sendData(): ByteBuffer {
        log.debug("send: {}", state)
        return when (state) {
            State.Propose -> {
                val payload = muxByteBufferPool.borrow()
                MsgProposeVersions(networkMagic).writeToBuffer(payload)
                state = State.Confirm
                payload.flip()
            }
            else -> throw IllegalStateException("We should not call sendData() when we're in a $state state!")
        }
    }

    override fun receiveData(payload: ByteBuffer) {
        when (state) {
            State.Confirm -> {
                state = State.Done
                ByteArrayInputStream(payload.array(), payload.position(), payload.remaining()).use { byteStream ->
                    CborReader.createFromInputStream(byteStream).apply {
                        while (byteStream.available() > 0) {
                            val cborArray = readDataItem() as CborArray
                            val messageId: Long = cborArray.elementToLong(0)
                            when (messageId) {
                                MsgAcceptVersion.MESSAGE_ID -> {
                                    msgAcceptVersion = MsgAcceptVersion(cborArray)
                                    if (msgAcceptVersion.networkMagic != networkMagic) {
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
                    }
                }
            }
            else -> throw IllegalStateException("We should not call receiveData() when we're in a $state state!")
        }
    }

    enum class State {
        Propose,
        Confirm,
        Done,
    }
}
