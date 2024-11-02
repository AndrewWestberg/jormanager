package com.swiftmako.jormanager.nodeclient.protocols.handshake

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborMap
import com.google.iot.cbor.CborObject
import com.google.iot.cbor.CborSimple
import com.google.iot.cbor.CborWriter
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocolMessage
import java.nio.ByteBuffer

class MsgProposeVersions(
    private val networkMagic: Long
) : MiniProtocolMessage {
    companion object {
        const val PROTOCOL_VERSION_13 = 13L
        const val PROTOCOL_VERSION_14 = 14L

        const val MESSAGE_ID = 0L
        private val initiatorOnlyDiffusionMode = CborSimple.TRUE
        private val peerSharingDisabled = CborInteger.create(0)
        private val queryHandshake = CborSimple.FALSE
    }

    override fun writeToBuffer(buffer: ByteBuffer) {
        val payload = CborArray.create()
        payload.add(CborInteger.create(MESSAGE_ID))
        payload.add(
            CborMap.create(
                mutableMapOf<CborObject, CborObject>(
                    CborInteger.create(PROTOCOL_VERSION_13) to
                        CborArray.create(
                            listOf(
                                CborInteger.create(networkMagic),
                                initiatorOnlyDiffusionMode,
                                peerSharingDisabled,
                                queryHandshake
                            )
                        ),
                    CborInteger.create(PROTOCOL_VERSION_14) to
                        CborArray.create(
                            listOf(
                                CborInteger.create(networkMagic),
                                initiatorOnlyDiffusionMode,
                                peerSharingDisabled,
                                queryHandshake
                            )
                        )
                )
            )
        )
        CborWriter.createFromByteBuffer(buffer).writeDataItem(payload)
    }
}
