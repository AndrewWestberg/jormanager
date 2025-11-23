package com.swiftmako.jormanager.nodeclient.protocols.blockfetch

import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocolMessage
import java.nio.ByteBuffer

class MsgBlock : MiniProtocolMessage {
    companion object {
        const val MESSAGE_ID = 4L
    }

    override fun writeToBuffer(buffer: ByteBuffer) {
        // no-op
    }
}
