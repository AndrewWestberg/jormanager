package com.swiftmako.jormanager.nodeclient.protocols.handshake

import com.google.iot.cbor.*
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocolMessage
import java.nio.ByteBuffer


class MsgProposeVersions(private val networkMagic: Long) : MiniProtocolMessage {

    companion object {
        const val PROTOCOL_VERSION_7 = 7L
        const val MESSAGE_ID = 0L
    }

    override fun writeToBuffer(buffer: ByteBuffer) {
        val payload = CborArray.create()
        payload.add(CborInteger.create(MESSAGE_ID))
        payload.add(CborMap.create(mutableMapOf<CborObject, CborObject>(
                CborInteger.create(PROTOCOL_VERSION_7) to CborArray.create(listOf(CborInteger.create(networkMagic), CborSimple.FALSE)),
        )))
        CborWriter.createFromByteBuffer(buffer).writeDataItem(payload)
    }
}