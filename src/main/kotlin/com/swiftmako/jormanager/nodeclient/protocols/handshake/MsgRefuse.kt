package com.swiftmako.jormanager.nodeclient.protocols.handshake

import com.google.iot.cbor.CborArray

class MsgRefuse(
    private val cborArray: CborArray
) {
    companion object {
        const val MESSAGE_ID = 2L
    }

    override fun toString(): String = "MsgRefuse(reason = ${cborArray.toJsonString()})"
}
