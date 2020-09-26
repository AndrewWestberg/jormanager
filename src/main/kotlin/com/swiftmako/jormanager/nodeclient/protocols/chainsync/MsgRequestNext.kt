package com.swiftmako.jormanager.nodeclient.protocols.chainsync

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborWriter
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocolMessage
import java.nio.ByteBuffer

class MsgRequestNext : MiniProtocolMessage {
    override fun writeToBuffer(buffer: ByteBuffer) {
        val payload = CborArray.create()
        payload.add(CborInteger.create(MESSAGE_ID))
        CborWriter.createFromByteBuffer(buffer).writeDataItem(payload)
    }

    companion object {
        private const val MESSAGE_ID = 0L
    }
}