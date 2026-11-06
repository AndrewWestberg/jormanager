package com.swiftmako.jormanager.nodeclient.protocols.handshake

import com.firehose.controllers.nodeclient.protocol.Agency
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborReader
import com.google.iot.cbor.CborInteger
import com.swiftmako.jormanager.ktx.elementToBigInteger
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
                try {
                    ByteArrayInputStream(payload.array(), payload.position(), payload.remaining()).use { byteStream ->
                        val cborArray = CborReader.createFromInputStream(byteStream).readDataItem() as? CborArray
                            ?: throw IOException("Invalid handshake message")
                        if (byteStream.available() != 0) throw IOException("Trailing handshake data")
                        require(cborArray.elementAt(0) is CborInteger) { "Invalid handshake message ID" }
                        when (cborArray.elementToBigInteger(0).longValueExact()) {
                            MsgAcceptVersion.MESSAGE_ID -> {
                                val accepted = MsgAcceptVersion(cborArray)
                                if (accepted.networkMagic != networkMagic) {
                                    throw IOException("Handshake succeeded, but networkMagic did not match!")
                                }
                                msgAcceptVersion = accepted
                                state = State.Done
                                log.info("Handshake Successful: $msgAcceptVersion")
                            }
                            MsgRefuse.MESSAGE_ID -> throw IOException("Handshake Failed: ${MsgRefuse(cborArray)}")
                            else -> throw IOException("Unexpected Message: ${cborArray.toJsonString()}")
                        }
                    }
                } catch (e: RuntimeException) {
                    throw IOException("Invalid handshake response", e)
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
