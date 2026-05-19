package com.swiftmako.jormanager.tracing.forwarding

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborMap
import com.google.iot.cbor.CborObject
import com.google.iot.cbor.CborWriter
import com.swiftmako.jormanager.ktx.elementToLong
import java.nio.ByteBuffer

data class ForwardingAcceptVersion(
    val versionNumber: Long,
    val networkMagic: Long,
)

class ForwardingProposeVersions(
    private val networkMagic: Long,
) {
    fun writeToBuffer(buffer: ByteBuffer) {
        val payload =
            CborArray.create().apply {
                add(CborInteger.create(MESSAGE_ID_PROPOSE))
                add(
                    CborMap.create(
                        mutableMapOf<CborObject, CborObject>(
                            CborInteger.create(FORWARDING_VERSION_1) to CborInteger.create(networkMagic)
                        )
                    )
                )
            }
        CborWriter.createFromByteBuffer(buffer).writeDataItem(payload)
    }

    companion object {
        const val MESSAGE_ID_PROPOSE = 0L
        const val FORWARDING_VERSION_1 = 1L
    }
}

object ForwardingHandshakeDecoder {
    fun decode(cborArray: CborArray): ForwardingAcceptVersion =
        when (val messageId = cborArray.elementToLong(0)) {
            MESSAGE_ID_ACCEPT -> {
                ForwardingAcceptVersion(
                    versionNumber = cborArray.elementToLong(1),
                    networkMagic = cborArray.elementToLong(2),
                )
            }

            MESSAGE_ID_REFUSE -> throw IllegalStateException("Forwarding handshake refused: ${cborArray.toJsonString()}")
            else -> throw IllegalStateException("Unexpected forwarding handshake message id: $messageId")
        }

    private const val MESSAGE_ID_ACCEPT = 1L
    private const val MESSAGE_ID_REFUSE = 2L
}
