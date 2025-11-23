package com.swiftmako.jormanager.nodeclient.protocols

import com.firehose.controllers.nodeclient.protocol.Agency
import kotlinx.coroutines.flow.Flow
import java.nio.ByteBuffer

abstract class MiniProtocol(
    val protocolId: Short
) {
    // Tells us what agency state the protocol is in
    abstract val agency: Agency

    // Tells us what agency state the protocol is in
    abstract val agencyFlow: Flow<Agency>

    // Tells us how big of a receive buffer this MiniProtocol needs
    abstract val rxBufferSize: Int

    /**
     * Get the data to send. suspend until there is something to send
     * @return ByteBuffer to send
     */
    abstract suspend fun sendData(): ByteBuffer

    /**
     * Receive data and mutate the protocol state.
     */
    abstract fun receiveData(payload: ByteBuffer)

    /**
     * Gracefully shutdown the protocol
     */
    abstract fun shutdown()
}
