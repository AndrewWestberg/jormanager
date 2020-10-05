package com.swiftmako.jormanager.nodeclient.protocols.chainsync

import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocolMessage
import java.nio.ByteBuffer

class MsgRollForward(
        val blockNumber: Long,
        val slotNumber: Long,
        val prevHash: String,
        val nodeVkey: String,
        val nodeVrfVkey: String,
        val etaVrfFirstPart: String,
        val etaVrfSecondPart: String,
        val leaderVrfFirstPart: String,
        val leaderVrfSecondPart: String,
        val blockSize: Long,
        val blockBodyHash: String,
        val poolOpcert: String,
        val unknown1: Long,
        val kesPeriod: Long,
        val unknown2: String,
        val protocolMajorVersion: Long,
        val protocolMinorVersion: Long
) : MiniProtocolMessage {
    override fun writeToBuffer(buffer: ByteBuffer) {
        //noop
    }

    override fun toString(): String {
        return "MsgRollForward(blockNumber=$blockNumber, slotNumber=$slotNumber, prevHash='$prevHash', nodeVkey='$nodeVkey', nodeVrfVkey='$nodeVrfVkey', blockSize=$blockSize, blockBodyHash='$blockBodyHash', poolOpcert='$poolOpcert', kesPeriod=$kesPeriod, protocolMajorVersion=$protocolMajorVersion, protocolMinorVersion=$protocolMinorVersion)"
    }


    companion object {
        const val MESSAGE_ID = 2L
    }
}