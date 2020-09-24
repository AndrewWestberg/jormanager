package com.swiftmako.jormanager.nodeclient.protocols

import java.nio.ByteBuffer

interface MiniProtocolMessage {
    fun writeToBuffer(buffer: ByteBuffer)
}