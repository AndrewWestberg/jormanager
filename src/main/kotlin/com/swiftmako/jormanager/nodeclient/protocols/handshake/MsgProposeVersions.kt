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
        const val PROTOCOL_VERSION_15 = 15L
        const val PROTOCOL_VERSION_16 = 16L
        val SUPPORTED_VERSIONS = listOf(PROTOCOL_VERSION_13, PROTOCOL_VERSION_14, PROTOCOL_VERSION_15, PROTOCOL_VERSION_16)

        const val MESSAGE_ID = 0L
        private val initiatorOnlyDiffusionMode = CborSimple.TRUE
        private val peerSharingDisabled = CborInteger.create(0)
        private val queryHandshake = CborSimple.FALSE
    }

    override fun writeToBuffer(buffer: ByteBuffer) {
        require(networkMagic in 0..0xffffffffL) { "Invalid network magic" }
        val versions = linkedMapOf<CborObject, CborObject>()
        for (version in SUPPORTED_VERSIONS) {
            val data = CborArray.create()
            data.add(CborInteger.create(networkMagic))
            data.add(initiatorOnlyDiffusionMode)
            data.add(peerSharingDisabled)
            data.add(queryHandshake)
            if (version >= PROTOCOL_VERSION_16) data.add(CborSimple.FALSE)
            versions[CborInteger.create(version)] = data
        }
        val payload = CborArray.create()
        payload.add(CborInteger.create(MESSAGE_ID))
        payload.add(CborMap.create(versions))
        CborWriter.createFromByteBuffer(buffer).writeDataItem(payload)
    }
}
