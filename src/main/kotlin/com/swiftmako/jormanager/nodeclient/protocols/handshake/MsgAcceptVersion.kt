package com.swiftmako.jormanager.nodeclient.protocols.handshake

import com.google.iot.cbor.CborArray
import com.swiftmako.jormanager.ktx.elementToLong

class MsgAcceptVersion(
    cborArray: CborArray
) {
    companion object {
        const val MESSAGE_ID = 1L
    }

    val versionNumber: Long by lazy { cborArray.elementToLong(1) }
    val networkMagic: Long by lazy { (cborArray.elementAt(2) as CborArray).elementToLong(0) }

    override fun toString(): String = "MsgAcceptVersion(messageId = $MESSAGE_ID, versionNumber = $versionNumber, extraParams = $networkMagic)"
}

// [1,5,[764824073,false]]
