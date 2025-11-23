package com.swiftmako.jormanager.nodeclient.protocols.keepalive

import com.google.iot.cbor.CborArray
import com.swiftmako.jormanager.ktx.elementToLong

class MsgKeepAliveResponse(
    cborArray: CborArray
) {
    companion object {
        const val MESSAGE_ID = 1L
    }

    val cookie: Short by lazy { cborArray.elementToLong(1).toShort() }

    override fun toString(): String = "MsgKeepAliveResponse(cookie = $cookie)"
}
