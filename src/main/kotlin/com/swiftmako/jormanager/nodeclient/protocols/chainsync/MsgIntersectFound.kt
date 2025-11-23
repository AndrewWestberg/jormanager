package com.swiftmako.jormanager.nodeclient.protocols.chainsync

import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocolMessage
import java.nio.ByteBuffer

class MsgIntersectFound : MiniProtocolMessage {
    companion object {
        const val MESSAGE_ID = 5L
    }

    override fun writeToBuffer(buffer: ByteBuffer) {
        // no-op
    }
}
