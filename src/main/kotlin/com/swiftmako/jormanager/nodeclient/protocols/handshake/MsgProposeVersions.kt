package com.swiftmako.jormanager.nodeclient.protocols.handshake

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborMap
import com.google.iot.cbor.CborObject
import com.google.iot.cbor.CborWriter
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocolMessage
import java.nio.ByteBuffer


class MsgProposeVersions(private val networkMagic: Long) : MiniProtocolMessage {

    companion object {
        const val PROTOCOL_VERSION = 3L  // 3 is the latest version from cardano-node
        const val MESSAGE_ID = 0L
    }

    override fun writeToBuffer(buffer: ByteBuffer) {
        val payload = CborArray.create()
        payload.add(CborInteger.create(MESSAGE_ID))
        payload.add(CborMap.create(mutableMapOf<CborObject, CborObject>(
                CborInteger.create(PROTOCOL_VERSION) to CborInteger.create(networkMagic),
        )))
        CborWriter.createFromByteBuffer(buffer).writeDataItem(payload)
    }
}