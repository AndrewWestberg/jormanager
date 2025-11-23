package com.swiftmako.jormanager.nodeclient.protocols.chainsync

import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocolMessage
import java.nio.ByteBuffer

class MsgRollBackward : MiniProtocolMessage {
    companion object {
        const val MESSAGE_ID = 3L
    }

    override fun writeToBuffer(buffer: ByteBuffer) {
        // no-op
    }
}
