package com.swiftmako.jormanager.nodeclient.protocols.chainsync

import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocolMessage
import java.nio.ByteBuffer

class MsgIntersectNotFound : MiniProtocolMessage {
    companion object {
        const val MESSAGE_ID = 6L
    }

    override fun writeToBuffer(buffer: ByteBuffer) {
        // no-op
    }
}
