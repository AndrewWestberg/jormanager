package com.swiftmako.jormanager.nodeclient.protocols.blockfetch

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborByteString
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborWriter
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocolMessage
import java.nio.ByteBuffer

class MsgRequestRange(private val start: Pair<Long, ByteArray>, private val end: Pair<Long, ByteArray>) :
    MiniProtocolMessage {
    companion object {
        private const val MESSAGE_ID = 0L
    }

    override fun writeToBuffer(buffer: ByteBuffer) {
        val payload = CborArray.create().apply {
            add(CborInteger.create(MESSAGE_ID))
            add(CborArray.create().apply { // point
                add(CborInteger.create(start.first)) // slot
                add(CborByteString.create(start.second)) // hash
            })
            add(CborArray.create().apply { // point
                add(CborInteger.create(end.first)) // slot
                add(CborByteString.create(end.second)) // hash
            })
        }

        CborWriter.createFromByteBuffer(buffer).writeDataItem(payload)
    }

}