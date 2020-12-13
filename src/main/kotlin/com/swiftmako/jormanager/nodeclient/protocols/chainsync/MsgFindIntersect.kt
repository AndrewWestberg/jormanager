package com.swiftmako.jormanager.nodeclient.protocols.chainsync

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborByteString
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborWriter
import com.swiftmako.jormanager.entities.ChainBlock
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocolMessage
import org.springframework.security.crypto.codec.Hex
import java.nio.ByteBuffer

class MsgFindIntersect(val chainBlocks:List<ChainBlock>) : MiniProtocolMessage {
    override fun writeToBuffer(buffer: ByteBuffer) {
        val payload = CborArray.create()
        payload.add(CborInteger.create(MESSAGE_ID))
        val points = CborArray.create()
        chainBlocks.forEach { chainBlock ->
            val point = CborArray.create()
            point.add(CborInteger.create(chainBlock.slotNumber))
            point.add(CborByteString.create(chainBlock.hash.hexToByteArray()))
            points.add(point)
        }

        payload.add(points)

        CborWriter.createFromByteBuffer(buffer).writeDataItem(payload)
    }

    companion object {
        private const val MESSAGE_ID = 4L
    }
}