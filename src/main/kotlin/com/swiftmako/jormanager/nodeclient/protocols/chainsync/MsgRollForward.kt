package com.swiftmako.jormanager.nodeclient.protocols.chainsync

import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocolMessage
import java.nio.ByteBuffer

class MsgRollForward(
    val blockNumber: Long,
    val slotNumber: Long,
    val hash: String,
    val prevHash: String,
    val nodeVKey: String,
    val etaVrf: String,
    val leaderVrf: String,
    val blockVrf: String,
    val blockVrfProof: String,
    val chainTip: ChainTip,
) : MiniProtocolMessage {
    override fun writeToBuffer(buffer: ByteBuffer) {
        //noop
    }

    override fun toString(): String {
        return "MsgRollForward(blockNumber=$blockNumber, slotNumber=$slotNumber, hash='$hash', prevHash='$prevHash', nodeVKey='$nodeVKey', etaVrf='$etaVrf', leaderVrf='$leaderVrf', blockVrf='$blockVrf', blockVrfProof='$blockVrfProof', chainTip=$chainTip)"
    }


    companion object {
        const val MESSAGE_ID = 2L
    }
}