package com.swiftmako.jormanager.nodeclient.protocols

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.ChainPoint
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.LeiosFetchProtocol
import com.swiftmako.jormanager.nodeclient.protocols.chainsync.ChainSyncProtocol
import com.swiftmako.jormanager.nodeclient.protocols.fixtures.CatchupFixtures as F
import com.swiftmako.jormanager.nodeclient.protocols.mux.Mux
import com.swiftmako.jormanager.nodeclient.protocols.mux.muxByteBufferPool
import com.swiftmako.jormanager.repositories.ChainRepository
import com.swiftmako.jormanager.services.ChainIngestionCoordinator
import com.swiftmako.jormanager.services.PooltoolService
import com.swiftmako.jormanager.utils.Blake2b
import com.swiftmako.jormanager.utils.CborSpans
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.connection
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock

class ChainSyncResponsivenessTest {
    private val fixture = F.leios(1)
    private val firstHash = F.blockHash(fixture.blocks[0])
    private val secondHash = F.blockHash(fixture.blocks[1])
    private val origin = F.array()
    private val intersection = F.intersectFound(origin, F.tip(1, firstHash, 1))
    private val forward = F.rollForward(7, fixture.blocks[0], 2, secondHash, 2)

    private fun protocol(coordinator: ChainIngestionCoordinator): Pair<ChainSyncProtocol, NetworkChainSession> {
        val session = NetworkChainSession("responsiveness", coordinator, false, 1, 21600)
        return ChainSyncProtocol(mock(Host::class.java), ByteArray(32), emptyList(), mock(ChainRepository::class.java),
            false, mock(PooltoolService::class.java), "", "", session) to session
    }

    private suspend fun send(sync: ChainSyncProtocol): ByteArray {
        val buffer = sync.sendData()
        return try { ByteArray(buffer.remaining()).also { buffer.get(it) } } finally { muxByteBufferPool.recycle(buffer) }
    }

    private fun messageId(bytes: ByteArray) = CborSpans.parse(bytes).array()[0].integer().toInt()

    private fun blockTip(coordinator: ChainIngestionCoordinator, entered: CountDownLatch, release: CountDownLatch, point: ChainPoint = ChainPoint("1", firstHash.toHexString())) {
        doAnswer {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Tip work was not released" }
            null
        }.`when`(coordinator).observeTip("responsiveness", point, 1)
    }

    @Test
    fun intersectionAndForwardDeferWorkUntilTransmit() = runBlocking {
        withTimeout(10000) {
            for (isForward in listOf(false, true)) {
                val coordinator = mock(ChainIngestionCoordinator::class.java)
                val (sync, session) = protocol(coordinator)
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                try {
                    assertThat(messageId(send(sync))).isEqualTo(4)
                    if (isForward) {
                        sync.receiveData(ByteBuffer.wrap(intersection))
                        assertThat(messageId(send(sync))).isEqualTo(0)
                    }
                    blockTip(coordinator, entered, release, if (isForward) ChainPoint("2", secondHash.toHexString()) else ChainPoint("1", firstHash.toHexString()))
                    val reply = if (isForward) forward else intersection
                    withTimeout(2000) { withContext(Dispatchers.IO) { sync.receiveData(ByteBuffer.wrap(reply)) } }
                    val next = async(Dispatchers.IO) { send(sync) }
                    assertThat(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) }).isTrue()
                    assertThat(next.isCompleted).isFalse()
                    release.countDown()
                    assertThat(messageId(next.await())).isEqualTo(0)
                    if (isForward) assertThat(session.next().point.hash).isEqualTo(firstHash.toHexString())
                } finally {
                    release.countDown()
                    sync.shutdown()
                    session.close()
                }
            }
        }
    }

    @Test
    fun shutdownOrCancellationDuringTipWriteCannotEmitRequest() = runBlocking {
        withTimeout(10000) {
            for (cancel in listOf(false, true)) {
                val coordinator = mock(ChainIngestionCoordinator::class.java)
                val (sync, session) = protocol(coordinator)
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val exited = CountDownLatch(1)
                val emitted = AtomicBoolean(false)
                try {
                    send(sync)
                    sync.receiveData(ByteBuffer.wrap(intersection))
                    blockTip(coordinator, entered, release)
                    val next = async(Dispatchers.IO) {
                        try {
                            runCatching { send(sync) }.onSuccess { emitted.set(true) }
                        } finally { exited.countDown() }
                    }
                    assertThat(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) }).isTrue()
                    if (cancel) next.cancel() else sync.shutdown()
                    release.countDown()
                    assertThat(withContext(Dispatchers.IO) { exited.await(2, TimeUnit.SECONDS) }).isTrue()
                    next.cancelAndJoin()
                    assertThat(emitted.get()).isFalse()
                } finally {
                    release.countDown()
                    sync.shutdown()
                    session.close()
                }
            }
        }
    }

    private data class Frame(val protocol: Int, val payload: ByteArray)

    private suspend fun readFrame(socket: Socket): Frame = withContext(Dispatchers.IO) {
        val input = DataInputStream(socket.getInputStream())
        input.readInt()
        val protocol = input.readUnsignedShort()
        val size = input.readUnsignedShort()
        Frame(protocol, ByteArray(size).also(input::readFully))
    }

    private suspend fun reply(socket: Socket, protocol: Int, payload: ByteArray) = withContext(Dispatchers.IO) {
        val output = DataOutputStream(socket.getOutputStream())
        output.writeInt(0)
        output.writeShort(protocol or 0x8000)
        output.writeShort(payload.size)
        output.write(payload)
        output.flush()
    }

    @Test
    fun muxPropagatesDatabaseFailure() = runBlocking {
        withTimeout(10000) {
            supervisorScope {
                val coordinator = mock(ChainIngestionCoordinator::class.java)
                val failure = java.sql.SQLException("tip write failed")
                doAnswer { throw failure }.`when`(coordinator).observeTip("responsiveness", ChainPoint("1", firstHash.toHexString()), 1)
                val (sync, session) = protocol(coordinator)
                val selector = ActorSelectorManager(Dispatchers.IO)
                ServerSocket(0).use { server ->
                    val socket = aSocket(selector).tcp().connect(InetSocketAddress("127.0.0.1", server.localPort))
                    val peer = withContext(Dispatchers.IO) { server.accept().apply { soTimeout = 3000 } }
                    val mux = Mux(socket.connection())
                    val running = async(Dispatchers.IO) { runCatching { mux.execute(sync) }.exceptionOrNull() }
                    try {
                        assertThat(messageId(readFrame(peer).payload)).isEqualTo(4)
                        reply(peer, 2, intersection)
                        assertThat(running.await()).isInstanceOf(java.sql.SQLException::class.java)
                    } finally {
                        mux.shutdownGracefully(); peer.close(); socket.close(); session.close(); selector.close()
                        running.cancelAndJoin()
                    }
                }
            }
        }
    }

    @Test
    fun muxContinuesRealLeiosExchangeWhileTipWriteIsBlocked() = runBlocking {
        withTimeout(10000) {
            supervisorScope {
                val coordinator = mock(ChainIngestionCoordinator::class.java)
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                blockTip(coordinator, entered, release)
                val (sync, session) = protocol(coordinator)
                val leios = LeiosFetchProtocol({ it == 1L })
                val selector = ActorSelectorManager(Dispatchers.IO)
                ServerSocket(0).use { server ->
                    val socket = aSocket(selector).tcp().connect(InetSocketAddress("127.0.0.1", server.localPort))
                    val peer = withContext(Dispatchers.IO) { server.accept().apply { soTimeout = 3000 } }
                    val mux = Mux(socket.connection())
                    val running = async(Dispatchers.IO) { mux.execute(sync, leios) }
                    try {
                        val find = readFrame(peer)
                        assertThat(find.protocol).isEqualTo(2)
                        assertThat(messageId(find.payload)).isEqualTo(4)
                        reply(peer, 2, intersection)
                        assertThat(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) }).isTrue()
                        val body = requireNotNull(fixture.endorsement)
                        val hash = Blake2b.hash256(body)
                        val acquisition = async(Dispatchers.IO) { leios.acquire(1, hash, body.size.toLong(), 1) }
                        val requestBody = readFrame(peer)
                        assertThat(requestBody.protocol).isEqualTo(19)
                        assertThat(messageId(requestBody.payload)).isEqualTo(1)
                        reply(peer, 19, F.array(F.integer(2), body))
                        val requestTransactions = readFrame(peer)
                        assertThat(requestTransactions.protocol).isEqualTo(19)
                        assertThat(messageId(requestTransactions.payload)).isEqualTo(3)
                        reply(peer, 19, F.array(F.integer(4), F.array(F.tagged24(fixture.transactions.single()))))
                        val closure = acquisition.await()
                        assertThat(closure.ebHash).isEqualTo(hash)
                        assertThat(closure.entries).hasSize(1)
                        assertThat(closure.entries.single().fullTransactionHash).isEqualTo(Blake2b.hash256(fixture.transactions.single()))
                        assertThat(closure.entries.single().batch.parent.bodyId).isEqualTo(F.transactionId(fixture.transactions.single()))
                        peer.soTimeout = 200
                        val noRequest = withContext(Dispatchers.IO) {
                            try { readFrame(peer); false } catch (_: SocketTimeoutException) { true }
                        }
                        assertThat(noRequest).isTrue()
                        peer.soTimeout = 3000
                        release.countDown()
                        val next = readFrame(peer)
                        assertThat(next.protocol).isEqualTo(2)
                        assertThat(messageId(next.payload)).isEqualTo(0)
                    } finally {
                        release.countDown()
                        mux.shutdownGracefully(); peer.close(); socket.close(); session.close(); selector.close()
                        running.cancelAndJoin()
                    }
                }
            }
        }
    }
}
