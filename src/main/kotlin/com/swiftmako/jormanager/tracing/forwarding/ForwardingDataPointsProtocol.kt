package com.swiftmako.jormanager.tracing.forwarding

import com.firehose.controllers.nodeclient.protocol.Agency
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborReader
import com.google.iot.cbor.CborTextString
import com.google.iot.cbor.CborWriter
import com.swiftmako.jormanager.ktx.elementToLong
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import com.swiftmako.jormanager.nodeclient.protocols.mux.muxByteBufferPool
import com.swiftmako.jormanager.tracing.TraceForwardMessage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class ForwardingDataPointsProtocol(
    private val requestedNames: List<String>,
    private val singleReplyMode: Boolean = true,
) : MiniProtocol(protocolId = 0x0003) {
    private var state = State.Request
        set(value) {
            field = value
            _agencyFlow.tryEmit(agency)
        }

    private val _agencyFlow = MutableSharedFlow<Agency>(replay = 1, extraBufferCapacity = 4).apply { tryEmit(agency) }
    override val agencyFlow: Flow<Agency> = _agencyFlow

    override val rxBufferSize: Int = 1024 * 1024

    override val agency: Agency
        get() =
            when (state) {
                State.Request, State.DoneToSend -> Agency.Client
                State.Reply -> Agency.Server
                State.Done -> Agency.None
            }

    private val _messages = MutableSharedFlow<TraceForwardMessage>(extraBufferCapacity = 4)
    val messages: Flow<TraceForwardMessage> = _messages

    override fun shutdown() {
        state = if (state == State.Reply) State.DoneToSend else State.Done
    }

    override suspend fun sendData(): ByteBuffer =
        when (state) {
            State.Request -> {
                val payload = muxByteBufferPool.borrow()
                buildRequest(payload)
                state = State.Reply
                payload.flip()
            }

            State.DoneToSend -> {
                val payload = muxByteBufferPool.borrow()
                buildDone(payload)
                state = State.Done
                payload.flip()
            }

            State.Done -> muxByteBufferPool.borrow().apply {
                clear()
                limit(0)
            }

            State.Reply -> throw IllegalStateException("sendData() invalid in state $state")
        }

    override fun receiveData(payload: ByteBuffer) {
        if (state == State.Done) {
            return
        }
        if (state != State.Reply) {
            throw IllegalStateException("receiveData() invalid in state $state")
        }

        ByteArrayInputStream(payload.array(), payload.position(), payload.remaining()).use { byteStream ->
            val cborArray = CborReader.createFromInputStream(byteStream).readDataItem() as? CborArray
                ?: throw IOException("Expected data points reply array")
            val message =
                when (val messageId = cborArray.elementToLong(0)) {
                    MSG_DATA_POINTS_REPLY_ID -> {
                        val dataPoints = cborArray.elementAt(1) as? CborArray
                            ?: throw IOException("Expected data points array payload")
                        TraceForwardMessage.DataPointsReply(dataPoints)
                    }

                    MSG_DONE_ID -> TraceForwardMessage.Done
                    else -> throw IOException("Unexpected data-point message id: $messageId")
                }
            _messages.tryEmit(message)
            state =
                when {
                    message == TraceForwardMessage.Done -> State.Done
                    singleReplyMode -> State.DoneToSend
                    else -> State.Request
                }
        }
    }

    private fun buildRequest(buffer: ByteBuffer) {
        val payload =
            CborArray.create().apply {
                add(CborInteger.create(MSG_DATA_POINTS_REQUEST_ID))
                add(CborArray.create().apply { requestedNames.forEach { add(CborTextString.create(it)) } })
            }
        CborWriter.createFromByteBuffer(buffer).writeDataItem(payload)
    }

    private fun buildDone(buffer: ByteBuffer) {
        val payload = CborArray.create().apply { add(CborInteger.create(MSG_DONE_ID)) }
        CborWriter.createFromByteBuffer(buffer).writeDataItem(payload)
    }

    private enum class State {
        Request,
        Reply,
        DoneToSend,
        Done,
    }

    companion object {
        private const val MSG_DATA_POINTS_REQUEST_ID = 1L
        private const val MSG_DONE_ID = 2L
        private const val MSG_DATA_POINTS_REPLY_ID = 3L
    }
}
