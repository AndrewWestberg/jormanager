package com.swiftmako.jormanager.nodeclient.protocols.chainsync

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborByteString
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborWriter
import com.swiftmako.jormanager.entities.ChainBlock
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocolMessage
import java.nio.ByteBuffer

class MsgFindIntersect(
    private val intersectBlocks: List<Pair<Long, ByteArray>>
) : MiniProtocolMessage {
    companion object {
        private const val MESSAGE_ID = 4L
    }

    override fun writeToBuffer(buffer: ByteBuffer) {
        val payload =
            CborArray.create().apply {
                add(CborInteger.create(MESSAGE_ID))
                add(
                    CborArray.create().apply {
                        // points
                        intersectBlocks.forEach { block ->
                            add(
                                CborArray.create().apply {
                                    // point
                                    add(CborInteger.create(block.first)) // slot
                                    add(CborByteString.create(block.second)) // hash
                                }
                            )
                        }
                    }
                )
            }

        CborWriter.createFromByteBuffer(buffer).writeDataItem(payload)
    }
}
