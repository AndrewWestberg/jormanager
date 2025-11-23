package com.swiftmako.jormanager.nodeclient.protocols.keepalive

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborWriter
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocolMessage
import java.nio.ByteBuffer

class MsgKeepAlive(
    val cookie: Short
) : MiniProtocolMessage {
    companion object {
        const val MESSAGE_ID = 0L
    }

    override fun writeToBuffer(buffer: ByteBuffer) {
        val payload = CborArray.create()
        payload.add(CborInteger.create(MESSAGE_ID))
        payload.add(CborInteger.create(cookie))
        CborWriter.createFromByteBuffer(buffer).writeDataItem(payload)
    }
}
