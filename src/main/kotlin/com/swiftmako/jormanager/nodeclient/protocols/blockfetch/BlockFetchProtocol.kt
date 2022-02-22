package com.swiftmako.jormanager.nodeclient.protocols.blockfetch

import com.firehose.controllers.nodeclient.protocol.Agency
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import com.swiftmako.jormanager.nodeclient.protocols.chainsync.ChainSyncProtocol
import com.swiftmako.jormanager.nodeclient.protocols.chainsync.MsgFindIntersect
import com.swiftmako.jormanager.nodeclient.protocols.chainsync.MsgRequestNext
import com.swiftmako.jormanager.nodeclient.protocols.mux.muxByteBufferPool
import com.swiftmako.jormanager.repositories.BlockFetchRepository
import com.swiftmako.jormanager.repositories.ChainRepository
import com.swiftmako.jormanager.repositories.LedgerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer

class BlockFetchProtocol(
    private val chainRepository: ChainRepository,
    private val blockFetchRepository: BlockFetchRepository,
    private val ledgerRepository: LedgerRepository
) : MiniProtocol(protocolId = 0x0003.toShort()) {

    private val log by lazy { LoggerFactory.getLogger(BlockFetchProtocol::class.java) }

    override val RX_BUFFER_SIZE: Int = 128 * 1024

    private var state = State.Idle
        set(value) {
            field = value
            _agencyFlow.tryEmit(agency)
        }

    private val _agencyFlow = MutableSharedFlow<Agency>(replay = 1, extraBufferCapacity = 4).apply { tryEmit(agency) }
    override val agencyFlow: Flow<Agency> = _agencyFlow

    override val agency: Agency
        get() = when (state) {
            State.Idle -> Agency.Client
            State.Busy -> Agency.Server
            State.Streaming -> Agency.Server
            State.Done -> Agency.None
        }

    enum class State {
        Idle,
        Busy,
        Streaming,
        Done
    }

    override suspend fun sendData(): ByteBuffer {
        return when (state) {
            State.Idle -> {
                val payload = muxByteBufferPool.borrow()

                val tipHeaderBlockNumber = chainRepository.findTipBlockNumber()
                val tipFetchedBlockNumber = blockFetchRepository.findTipBlockNumber()
                if(tipFetchedBlockNumber < tipHeaderBlockNumber) {
                    // we have blocks to fetch
                    ***
                } else {
                    // wait until a new block has arrived
                    ***
                }

                state = if (isIntersectFound) {
                    log.trace("MsgRequestNext")
                    MsgRequestNext().writeToBuffer(payload)
                    ChainSyncProtocol.State.CanAwait
                } else {
                    log.trace("MsgFindIntersect")
                    MsgFindIntersect(tipToIntersect + lastByronBlocks).writeToBuffer(payload)
                    _tipToIntersect = null
                    ChainSyncProtocol.State.Intersect
                }
                payload.flip()
            }
            else -> throw IllegalStateException("We should not call sendData() when we're in a $state state!")
        }
    }

    override fun receiveData(payload: ByteBuffer) {
        TODO("Not yet implemented")
    }

}