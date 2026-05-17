package com.swiftmako.jormanager.tracing.forwarding

import com.firehose.controllers.nodeclient.protocol.Agency
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborReader
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import com.swiftmako.jormanager.nodeclient.protocols.mux.muxByteBufferPool
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class ForwardingHandshakeProtocol(
    private val networkMagic: Long,
) : MiniProtocol(protocolId = 0x0000) {
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

    lateinit var acceptVersion: ForwardingAcceptVersion

    override fun shutdown() {
        state = State.Done
    }

    override suspend fun sendData(): ByteBuffer =
        when (state) {
            State.Propose -> {
                val payload = muxByteBufferPool.borrow()
                ForwardingProposeVersions(networkMagic).writeToBuffer(payload)
                state = State.Confirm
                payload.flip()
            }

            else -> throw IllegalStateException("sendData() invalid in state $state")
        }

    override fun receiveData(payload: ByteBuffer) {
        when (state) {
            State.Confirm -> {
                state = State.Done
                ByteArrayInputStream(payload.array(), payload.position(), payload.remaining()).use { byteStream ->
                    val cborArray = CborReader.createFromInputStream(byteStream).readDataItem() as? CborArray
                        ?: throw IOException("Expected forwarding handshake CBOR array")
                    acceptVersion = ForwardingHandshakeDecoder.decode(cborArray)
                    if (acceptVersion.networkMagic != networkMagic) {
                        throw IOException("Forwarding handshake succeeded, but networkMagic did not match")
                    }
                }
            }

            else -> throw IllegalStateException("receiveData() invalid in state $state")
        }
    }

    enum class State {
        Propose,
        Confirm,
        Done,
    }
}
