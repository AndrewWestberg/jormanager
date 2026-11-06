package com.swiftmako.jormanager.nodeclient.protocols.handshake

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborSimple
import com.google.iot.cbor.CborTag
import com.swiftmako.jormanager.ktx.elementToBigInteger

class MsgAcceptVersion(
    cborArray: CborArray
) {
    companion object {
        const val MESSAGE_ID = 1L
    }

    val versionNumber: Long
    val networkMagic: Long

    init {
        require(cborArray.tag == CborTag.UNTAGGED && cborArray.size() == 3 &&
            cborArray.elementAt(0).tag == CborTag.UNTAGGED && cborArray.elementAt(0) is CborInteger &&
            cborArray.elementToBigInteger(0).longValueExact() == MESSAGE_ID) { "Invalid acceptance message" }
        require(cborArray.elementAt(1).tag == CborTag.UNTAGGED && cborArray.elementAt(1) is CborInteger) { "Invalid selected version" }
        versionNumber = cborArray.elementToBigInteger(1).longValueExact()
        require(versionNumber in MsgProposeVersions.SUPPORTED_VERSIONS) { "Unoffered version $versionNumber" }
        val data = cborArray.elementAt(2) as? CborArray ?: throw IllegalArgumentException("Invalid version data")
        require(data.tag == CborTag.UNTAGGED) { "Invalid version data" }
        require(data.size() == if (versionNumber >= MsgProposeVersions.PROTOCOL_VERSION_16) 5 else 4) {
            "Invalid version data length for version $versionNumber"
        }
        require((0 until data.size()).all { data.elementAt(it).tag == CborTag.UNTAGGED }) { "Tagged version data" }
        require(data.elementAt(0) is CborInteger) { "Invalid network magic" }
        networkMagic = data.elementToBigInteger(0).longValueExact()
        require(networkMagic in 0..0xffffffffL) { "Invalid network magic" }
        require(data.elementAt(1) == CborSimple.TRUE &&
            data.elementAt(2) is CborInteger && data.elementToBigInteger(2).signum() == 0 &&
            data.elementAt(3) == CborSimple.FALSE &&
            (versionNumber < MsgProposeVersions.PROTOCOL_VERSION_16 || data.elementAt(4) == CborSimple.FALSE)
        ) { "Unsupported negotiated version data" }
    }

    override fun toString(): String = "MsgAcceptVersion(messageId = $MESSAGE_ID, versionNumber = $versionNumber, networkMagic = $networkMagic)"
}

