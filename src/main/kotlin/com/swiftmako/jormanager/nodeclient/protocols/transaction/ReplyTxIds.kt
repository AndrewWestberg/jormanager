package com.swiftmako.jormanager.nodeclient.protocols.transaction

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborObject
import com.google.iot.cbor.CborWriter
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocolMessage
import java.nio.ByteBuffer

class ReplyTxIds: MiniProtocolMessage {
    override fun writeToBuffer(buffer: ByteBuffer) {
        // we need to do manual cbor encoding to do the empty indefinite array for txs.
        buffer.put(0x82.toByte()) // array of length 2
        buffer.put(MESSAGE_ID.toByte())
        buffer.put(0x9f.toByte()) // indefinite array start
        buffer.put(0xff.toByte()) // indefinite array end
    }

    companion object {
        private const val MESSAGE_ID = 1L
    }
}