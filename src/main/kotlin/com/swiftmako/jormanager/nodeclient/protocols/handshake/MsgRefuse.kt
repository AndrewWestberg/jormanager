package com.swiftmako.jormanager.nodeclient.protocols.handshake

import com.google.iot.cbor.CborArray
import com.swiftmako.jormanager.ktx.elementToLong

class MsgRefuse(private val cborArray: CborArray) {
    companion object {
        const val MESSAGE_ID = 2L

        private const val REFUSE_REASON_VERSION_MISMATCH = 0L
        private const val REFUSE_REASON_HANDSHAKE_DECODE_ERROR = 1L
        private const val REFUSE_REASON_REFUSED = 2L
    }

    val refuseReasonId: Long by lazy { cborArray.elementToLong(0) }

    override fun toString(): String {
        return "MsgRefuse(refuseReason = ${cborArray.toJsonString()}"
    }
}