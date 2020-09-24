package com.swiftmako.jormanager.nodeclient.protocols.handshake

import com.google.iot.cbor.CborArray
import com.swiftmako.jormanager.ktx.elementToLong

class MsgAcceptVersion(cborArray: CborArray) {
    companion object {
        const val MESSAGE_ID = 1L
    }

    val versionNumber: Long by lazy { cborArray.elementToLong(1) }
    val extraParams: Long by lazy { cborArray.elementToLong(2) }

    override fun toString(): String {
        return "MsgAcceptVersion(messageId = $MESSAGE_ID, versionNumber = $versionNumber, extraParams = $extraParams)"
    }
}