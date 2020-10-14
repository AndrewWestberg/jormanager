package com.swiftmako.jormanager.nodeclient.protocols.chainsync

import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocolMessage
import java.nio.ByteBuffer

class MsgRollForward(
        val blockNumber: Long,
        val slotNumber: Long,
        val prevHash: String,
        val etaVrf: String,
) : MiniProtocolMessage {
    override fun writeToBuffer(buffer: ByteBuffer) {
        //noop
    }

    override fun toString(): String {
        return "MsgRollForward(blockNumber=$blockNumber, slotNumber=$slotNumber, prevHash='$prevHash', etaVrf='$etaVrf')"
    }


    companion object {
        const val MESSAGE_ID = 2L
    }
}