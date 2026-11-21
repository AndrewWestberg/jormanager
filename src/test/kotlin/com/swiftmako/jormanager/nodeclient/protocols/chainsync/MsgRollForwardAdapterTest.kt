package com.swiftmako.jormanager.nodeclient.protocols.chainsync

import com.google.common.truth.Truth.assertThat
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborReader
import com.muquit.libsodiumjna.SodiumLibrary
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.nodeclient.protocols.NetworkChainSession
import com.swiftmako.jormanager.nodeclient.protocols.fixtures.CatchupFixtures as F
import com.swiftmako.jormanager.nodeclient.protocols.mux.muxByteBufferPool
import com.swiftmako.jormanager.repositories.ChainRepository
import com.swiftmako.jormanager.services.ChainIngestionCoordinator
import com.swiftmako.jormanager.services.PooltoolService
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock

class MsgRollForwardAdapterTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun initSodium() {
            listOf(
                "/usr/local/lib/libsodium.so",
                "/usr/lib/x86_64-linux-gnu/libsodium.so",
                "/usr/lib/x86_64-linux-gnu/libsodium.so.23",
            ).firstOrNull { File(it).exists() }?.let(SodiumLibrary::setLibraryPath)
        }
    }

    private fun parseRollForward(bytes: ByteArray): CborArray =
        CborReader.createFromByteArray(bytes).readDataItem() as CborArray

    @Test
    fun `supported Dijkstra and Conway eras decode cleanly`() {
        val dijkstraBlock = F.block(7, 1)
        val dijkstraHash = F.blockHash(dijkstraBlock)
        val dijkstraForward = parseRollForward(F.rollForward(7, dijkstraBlock, 10, dijkstraHash, 1))
        val decodedDijkstra = MsgRollForwardAdapter.fromCborArray(dijkstraForward)
        assertThat(decodedDijkstra.isPresent).isTrue()
        assertThat(decodedDijkstra.get().blockNumber).isEqualTo(1L)

        val sodiumAvailable = runCatching { SodiumLibrary.cryptoBlake2bHash(ByteArray(1), null) }.isSuccess
        if (sodiumAvailable) {
            val conwayBlock = F.block(6, 1)
            val conwayHash = F.blockHash(conwayBlock)
            val conwayForward = parseRollForward(F.rollForward(6, conwayBlock, 10, conwayHash, 1))
            val decodedConway = MsgRollForwardAdapter.fromCborArray(conwayForward)
            assertThat(decodedConway.isPresent).isTrue()
            assertThat(decodedConway.get().blockNumber).isEqualTo(1L)
        }
    }

    @Test
    fun `unknown future eras and negative eras are rejected instead of misparsed as Babbage or Conway`() {
        val conwayBlock = F.block(6, 1)
        val conwayHash = F.blockHash(conwayBlock)
        val dijkstraBlock = F.block(7, 1)

        for (unsupportedEra in listOf(8, 9, 99, -1, -42)) {
            for (headerBytes in listOf(F.header(conwayBlock), F.header(dijkstraBlock))) {
                val payload = F.array(
                    F.integer(MsgRollForward.MESSAGE_ID),
                    F.array(F.integer(unsupportedEra.toLong()), F.tagged24(headerBytes)),
                    F.tip(10, conwayHash, 1),
                )
                val decoded = MsgRollForwardAdapter.fromCborArray(parseRollForward(payload))
                assertThat(decoded.isPresent).isFalse()
            }
        }
    }

    @Test
    fun `ChainSyncProtocol rejects unknown future era MsgRollForward with IllegalStateException`() = runBlocking {
        val conwayBlock = F.block(6, 1)
        val conwayHash = F.blockHash(conwayBlock)
        val session = NetworkChainSession("era-bounds-test", mock(ChainIngestionCoordinator::class.java), false, 1, 21600)
        val sync = ChainSyncProtocol(
            mock(Host::class.java),
            ByteArray(32),
            emptyList(),
            mock(ChainRepository::class.java),
            false,
            mock(PooltoolService::class.java),
            "",
            "",
            session,
        )
        try {
            muxByteBufferPool.recycle(sync.sendData())
            sync.receiveData(ByteBuffer.wrap(F.intersectFound(F.array(), F.tip(1, conwayHash, 1))))
            muxByteBufferPool.recycle(sync.sendData())

            val futureEraRollForward = F.array(
                F.integer(MsgRollForward.MESSAGE_ID),
                F.array(F.integer(8L), F.tagged24(F.header(conwayBlock))),
                F.tip(2, conwayHash, 2),
            )
            val error = assertThrows<IllegalStateException> {
                sync.receiveData(ByteBuffer.wrap(futureEraRollForward))
            }
            assertThat(error.message).contains("Unable to decode era 8 chain header")
        } finally {
            sync.shutdown()
            session.close()
        }
    }
}
