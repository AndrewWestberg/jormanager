package com.swiftmako.jormanager.nodeclient.protocols.chainsync

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborByteString
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborWriter
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocolMessage
import org.springframework.security.crypto.codec.Hex
import java.nio.ByteBuffer

class MsgFindIntersect : MiniProtocolMessage {
    override fun writeToBuffer(buffer: ByteBuffer) {
        val payload = CborArray.create()
        payload.add(CborInteger.create(MESSAGE_ID))
        val points = CborArray.create()
        val point = CborArray.create()
        point.add(CborInteger.create(4492799)) // Last slot of epoch 207 (last byron block)
        point.add(CborByteString.create(Hex.decode("f8084c61b6a238acec985b59310b6ecec49c0ab8352249afd7268da5cff2a457")))
        points.add(point)

//        val point1 = CborArray.create()
//        point1.add(CborInteger.create(6039955)) // one block before my first shelley block
//        point1.add(CborByteString.create(Hex.decode("37f1d05be8f881f85f7607dcd28a38da5c037406471dc3c691b203412474fe3f")))
//        points.add(point1)

        payload.add(points)

        CborWriter.createFromByteBuffer(buffer).writeDataItem(payload)
    }

    companion object {
        private const val MESSAGE_ID = 4L
    }
}