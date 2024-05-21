package com.swiftmako.jormanager.nodeclient.protocols.handshake

import com.google.iot.cbor.*
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocolMessage
import java.nio.ByteBuffer


class MsgProposeVersions(private val networkMagic: Long) : MiniProtocolMessage {

    companion object {
        const val PROTOCOL_VERSION_7 = 7L
        const val PROTOCOL_VERSION_8 = 8L
        const val PROTOCOL_VERSION_9 = 9L
        const val PROTOCOL_VERSION_10 = 10L
        const val MESSAGE_ID = 0L
        private val initiatorOnlyDiffusionMode = CborSimple.TRUE
    }

    override fun writeToBuffer(buffer: ByteBuffer) {
        val payload = CborArray.create()
        payload.add(CborInteger.create(MESSAGE_ID))
        payload.add(
            CborMap.create(
                mutableMapOf<CborObject, CborObject>(
                    CborInteger.create(PROTOCOL_VERSION_7) to CborArray.create(
                        listOf(
                            CborInteger.create(networkMagic),
                            initiatorOnlyDiffusionMode
                        )
                    ),
                    CborInteger.create(PROTOCOL_VERSION_8) to CborArray.create(
                        listOf(
                            CborInteger.create(networkMagic),
                            initiatorOnlyDiffusionMode
                        )
                    ),
                    CborInteger.create(PROTOCOL_VERSION_9) to CborArray.create(
                        listOf(
                            CborInteger.create(networkMagic),
                            initiatorOnlyDiffusionMode
                        )
                    ),
                    CborInteger.create(PROTOCOL_VERSION_10) to CborArray.create(
                        listOf(
                            CborInteger.create(networkMagic),
                            initiatorOnlyDiffusionMode
                        )
                    ),
                )
            )
        )
        CborWriter.createFromByteBuffer(buffer).writeDataItem(payload)
    }
}