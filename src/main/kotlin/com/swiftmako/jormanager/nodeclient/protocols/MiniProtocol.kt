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
     * Opt-in full-duplex mode where transmit and receive may be active concurrently.
     * Non-pipelined protocols keep half-duplex [Agency] behavior unchanged.
     */
    open val duplex: Boolean
        get() = false

    /**
     * Whether a duplex protocol can currently transmit a frame.
     */
    open val canSend: Boolean
        get() = agency == Agency.Client

    /**
     * Whether this protocol can currently accept inbound frames from the peer.
     */
    open val canReceive: Boolean
        get() = agency == Agency.Server

    /**
     * Suspends until a duplex protocol has outbound work ready to transmit, or returns false when done.
     */
    open suspend fun awaitCanSend(): Boolean = false

    /**
     * Returns the next frame payload for a duplex protocol, or null if outbound readiness changed before commit.
     */
    open suspend fun sendDataOrNull(): ByteBuffer? = sendData()

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
