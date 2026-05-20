package com.swiftmako.jormanager.tracing.forwarding

import com.firehose.controllers.nodeclient.protocol.Agency
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborObject
import com.google.iot.cbor.CborReader
import com.google.iot.cbor.CborTextString
import com.google.iot.cbor.CborWriter
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import com.swiftmako.jormanager.nodeclient.protocols.mux.muxByteBufferPool
import com.swiftmako.jormanager.tracing.TraceForwardMessage
import com.swiftmako.jormanager.tracing.TracingRawMetricValue
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

sealed interface ForwardingMetricsRequest {
    data object GetAllMetrics : ForwardingMetricsRequest

    data class GetMetrics(
        val names: List<String>,
    ) : ForwardingMetricsRequest
}

class ForwardingMetricsProtocol(
    private val request: ForwardingMetricsRequest = ForwardingMetricsRequest.GetAllMetrics,
    private val singleReplyMode: Boolean = false,
    private val pollInterval: Duration = ZERO,
) : MiniProtocol(protocolId = 0x0001) {
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
    private var nextRequestAtNanos: Long? = null

    override fun shutdown() {
        state = if (state == State.Reply) State.DoneToSend else State.Done
    }

    override suspend fun sendData(): ByteBuffer =
        when (state) {
            State.Request -> {
                delayUntilNextRequest()
                val payload = muxByteBufferPool.borrow()
                buildRequest(payload)
                state = State.Reply
                payload.flip()
                payload
            }

            State.DoneToSend -> {
                val payload = muxByteBufferPool.borrow()
                buildDone(payload)
                state = State.Done
                payload.flip()
                payload
            }

            State.Done -> muxByteBufferPool.borrow().apply {
                clear()
                limit(0)
            }

            State.Reply -> error("sendData() invalid in state $state")
        }

    override fun receiveData(payload: ByteBuffer) {
        if (state == State.Done) return
        if (state != State.Reply) error("receiveData() invalid in state $state")

        ByteArrayInputStream(payload.array(), payload.position(), payload.remaining()).use { byteStream ->
            val cborArray = CborReader.createFromInputStream(byteStream).readDataItem() as? CborArray
                ?: error("Expected metrics reply array")
            when ((cborArray.elementAt(0) as? CborInteger)?.longValue()) {
                MSG_METRICS_REPLY_ID -> {
                    _messages.tryEmit(
                        TraceForwardMessage.MetricsReply(
                            metrics = decodeMetrics(cborArray.elementAt(1)),
                            rawJson = cborArray.toJsonString(),
                        )
                    )
                    scheduleNextRequest()
                    state = if (singleReplyMode) State.DoneToSend else State.Request
                }

                else -> error("Unexpected metrics message id")
            }
        }
    }

    private fun buildRequest(buffer: ByteBuffer) {
        val payload =
            when (request) {
                ForwardingMetricsRequest.GetAllMetrics -> CborArray.create().apply {
                    add(CborInteger.create(MSG_REQUEST_ID))
                    add(CborInteger.create(GET_ALL_METRICS_REQUEST_ID))
                }

                is ForwardingMetricsRequest.GetMetrics -> CborArray.create().apply {
                    add(CborInteger.create(MSG_REQUEST_ID))
                    add(
                        CborArray.create().apply {
                            add(CborInteger.create(GET_METRICS_REQUEST_ID))
                            add(
                                CborArray.create().apply {
                                    request.names.forEach { add(CborTextString.create(it)) }
                                }
                            )
                        }
                    )
                }
            }
        CborWriter.createFromByteBuffer(buffer).writeDataItem(payload)
    }

    private fun buildDone(buffer: ByteBuffer) {
        val payload = CborArray.create().apply { add(CborInteger.create(MSG_DONE_ID)) }
        CborWriter.createFromByteBuffer(buffer).writeDataItem(payload)
    }

    private suspend fun delayUntilNextRequest() {
        val scheduledAtNanos = nextRequestAtNanos ?: return
        val remainingNanos = scheduledAtNanos - System.nanoTime()
        if (remainingNanos > 0L) {
            delay(Duration.parse("${remainingNanos}ns"))
        }
    }

    private fun scheduleNextRequest() {
        nextRequestAtNanos = if (pollInterval == ZERO) null else System.nanoTime() + pollInterval.inWholeNanoseconds
    }

    private fun decodeMetrics(item: CborObject): Map<String, TracingRawMetricValue> {
        val array = item as? CborArray ?: return emptyMap()
        val metricsArray =
            when {
                array.size() == 2 && (array.elementAt(0) as? CborInteger)?.longValue() == RESPONSE_METRICS_TAG -> {
                    array.elementAt(1) as? CborArray ?: return emptyMap()
                }

                else -> array
            }

        val metrics = linkedMapOf<String, TracingRawMetricValue>()
        for (index in 0 until metricsArray.size()) {
            val pair = metricsArray.elementAt(index) as? CborArray ?: continue
            if (pair.size() < 2) {
                continue
            }
            val name = (pair.elementAt(0) as? CborTextString)?.stringValue() ?: continue
            val value = decodeMetricValue(pair.elementAt(1)) ?: continue
            metrics[name] = value
        }
        return metrics
    }

    private fun decodeMetricValue(item: CborObject): TracingRawMetricValue? {
        val valueArray = item as? CborArray ?: return null
        if (valueArray.size() != 2) {
            return null
        }

        return when ((valueArray.elementAt(0) as? CborInteger)?.longValue()) {
            COUNTER_TAG -> TracingRawMetricValue.Counter((valueArray.elementAt(1) as? CborInteger)?.longValue() ?: return null)
            INT_GAUGE_TAG -> TracingRawMetricValue.IntGauge((valueArray.elementAt(1) as? CborInteger)?.longValue() ?: return null)
            LABEL_TAG -> TracingRawMetricValue.Label((valueArray.elementAt(1) as? CborTextString)?.stringValue() ?: return null)
            else -> null
        }
    }

    private enum class State {
        Request,
        Reply,
        DoneToSend,
        Done,
    }

    companion object {
        private const val MSG_REQUEST_ID = 0L
        private const val MSG_METRICS_REPLY_ID = 1L
        private const val MSG_DONE_ID = 2L
        private const val GET_ALL_METRICS_REQUEST_ID = 0L
        private const val GET_METRICS_REQUEST_ID = 1L
        private const val RESPONSE_METRICS_TAG = 0L
        private const val COUNTER_TAG = 0L
        private const val INT_GAUGE_TAG = 1L
        private const val LABEL_TAG = 2L
    }
}
