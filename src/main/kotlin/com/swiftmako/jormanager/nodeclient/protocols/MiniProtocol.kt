package com.swiftmako.jormanager.nodeclient.protocols

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import org.slf4j.Logger
import java.nio.ByteBuffer

abstract class MiniProtocol(val protocolId: Short, protected val log: Logger) {

    abstract val txChannel: SendChannel<ByteBuffer>
    abstract val rxChannel: ReceiveChannel<ByteBuffer>

    abstract suspend fun start()
}