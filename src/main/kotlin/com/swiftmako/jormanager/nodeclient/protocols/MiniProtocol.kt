package com.swiftmako.jormanager.nodeclient.protocols

import kotlinx.coroutines.channels.Channel
import org.slf4j.Logger
import java.nio.ByteBuffer

abstract class MiniProtocol(val protocolId: Short, protected val log: Logger) {

    val txChannel: Channel<ByteBuffer> = Channel(Channel.RENDEZVOUS)
    val rxChannel: Channel<ByteBuffer> = Channel(Channel.RENDEZVOUS)

    abstract suspend fun start()
}