package com.swiftmako.jormanager.nodeclient.utils

import kotlinx.io.pool.DefaultPool
import java.nio.ByteBuffer

object BufferPool : DefaultPool<ByteBuffer>(16) {
    private const val BUFFER_SIZE = 8192

    override fun produceInstance(): ByteBuffer {
        return ByteBuffer.allocate(BUFFER_SIZE)
    }

    override fun clearInstance(instance: ByteBuffer): ByteBuffer {
        return instance.clear()
    }
}