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
    companion object {
        @JvmStatic
        @org.junit.jupiter.api.BeforeAll
        fun initSodium() {
            listOf(
                "/usr/local/lib/libsodium.so",
                "/usr/lib/x86_64-linux-gnu/libsodium.so",
                "/usr/lib/x86_64-linux-gnu/libsodium.so.23",
            ).firstOrNull { java.io.File(it).exists() }?.let(com.muquit.libsodiumjna.SodiumLibrary::setLibraryPath)
        }
    }

    private val fixture = F.leios(1)
    private val firstHash = F.blockHash(fixture.blocks[0])
    private val secondHash = F.blockHash(fixture.blocks[1])
    private val origin = F.array()
    private val intersection = F.intersectFound(origin, F.tip(1, firstHash, 1))
    private val forward = F.rollForward(7, fixture.blocks[0], 2, secondHash, 2)

    private fun protocol(coordinator: ChainIngestionCoordinator): Pair<ChainSyncProtocol, NetworkChainSession> {
        val session = NetworkChainSession("responsiveness", coordinator, false, 1, 21600)
        return ChainSyncProtocol(
            mock(Host::class.java),
            ByteArray(32),
            emptyList(),
            mock(ChainRepository::class.java),
            false,
            mock(PooltoolService::class.java),
            "",
            "",
            session
        ) to session
    }

    private suspend fun send(sync: ChainSyncProtocol): ByteArray {
        val buffer = sync.sendData()
        return try {
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } finally {
            muxByteBufferPool.recycle(buffer)
        }
    }

    private fun messageId(bytes: ByteArray) =
        CborSpans
            .parse(bytes)
            .array()[0]
            .integer()
            .toInt()

    private fun blockTip(
        coordinator: ChainIngestionCoordinator,
        entered: CountDownLatch,
        release: CountDownLatch,
        point: ChainPoint = ChainPoint("1", firstHash.toHexString())
    ) {
        doAnswer {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Tip work was not released" }
            null
        }.`when`(coordinator).observeTip("responsiveness", point, 1)
    }

    @Test
    fun intersectionAndForwardDeferWorkUntilTransmit() =
        runBlocking {
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
    fun shutdownOrCancellationDuringTipWriteCannotEmitRequest() =
        runBlocking {
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
                            } finally {
                                exited.countDown()
                            }
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

    private data class Frame(
        val protocol: Int,
        val payload: ByteArray
    )

    private suspend fun readFrame(socket: Socket): Frame =
        withContext(Dispatchers.IO) {
            val input = DataInputStream(socket.getInputStream())
            input.readInt()
            val protocol = input.readUnsignedShort()
            val size = input.readUnsignedShort()
            Frame(protocol, ByteArray(size).also(input::readFully))
        }

    private suspend fun reply(
        socket: Socket,
        protocol: Int,
        payload: ByteArray
    ) = withContext(Dispatchers.IO) {
        val output = DataOutputStream(socket.getOutputStream())
        output.writeInt(0)
        output.writeShort(protocol or 0x8000)
        output.writeShort(payload.size)
        output.write(payload)
        output.flush()
    }

    @Test
    fun muxPropagatesDatabaseFailure() =
        runBlocking {
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
                            mux.shutdownGracefully()
                            peer.close()
                            socket.close()
                            session.close()
                            selector.close()
                            running.cancelAndJoin()
                        }
                    }
                }
            }
        }

    @Test
    fun muxContinuesRealLeiosExchangeWhileTipWriteIsBlocked() =
        runBlocking {
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
                            assertThat(
                                closure.entries
                                    .single()
                                    .batch.parent.bodyId
                            ).isEqualTo(F.transactionId(fixture.transactions.single()))
                            peer.soTimeout = 200
                            val noRequest = withContext(Dispatchers.IO) {
                                try {
                                    readFrame(peer)
                                    false
                                } catch (_: SocketTimeoutException) {
                                    true
                                }
                            }
                            assertThat(noRequest).isTrue()
                            peer.soTimeout = 3000
                            release.countDown()
                            val next = readFrame(peer)
                            assertThat(next.protocol).isEqualTo(2)
                            assertThat(messageId(next.payload)).isEqualTo(0)
                        } finally {
                            release.countDown()
                            mux.shutdownGracefully()
                            peer.close()
                            socket.close()
                            session.close()
                            selector.close()
                            running.cancelAndJoin()
                        }
                    }
                }
            }
        }

    private suspend fun assertAllPermitsReturned(session: NetworkChainSession) {
        withTimeout(3000) { repeat(128) { session.reserve() } }
        assertThat(
            kotlinx.coroutines.withTimeoutOrNull(100) {
                session.reserve()
                true
            }
        ).isNull()
        repeat(128) { session.release() }
    }

    @Test
    fun chainSyncPipelinesMsgRequestNextOverDuplexMuxAndPausesCleanlyOnAwaitReply() =
        runBlocking {
            withTimeout(10000) {
                supervisorScope {
                    val blocks = (1..6).map { height -> F.block(7, height.toLong()) }
                    val tipHash = F.blockHash(blocks[3])
                    val coordinator = mock(ChainIngestionCoordinator::class.java)
                    val session = NetworkChainSession("duplex-pipeline", coordinator, false, 1, 21600)
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
                        maxInFlight = 6,
                    )
                    val selector = ActorSelectorManager(Dispatchers.IO)
                    ServerSocket(0).use { server ->
                        val socket = aSocket(selector).tcp().connect(InetSocketAddress("127.0.0.1", server.localPort))
                        val peer = withContext(Dispatchers.IO) { server.accept().apply { soTimeout = 3000 } }
                        val mux = Mux(socket.connection())
                        val running = async(Dispatchers.IO) { mux.execute(sync) }
                        try {
                            val find = readFrame(peer)
                            assertThat(find.protocol).isEqualTo(2)
                            assertThat(messageId(find.payload)).isEqualTo(4)

                            reply(peer, 2, F.intersectFound(origin, F.tip(4, tipHash, 4)))

                            // Without sending a single MsgRollForward reply yet, ChainSync pipelines all 6 MsgRequestNext frames!
                            repeat(6) {
                                val req = readFrame(peer)
                                assertThat(req.protocol).isEqualTo(2)
                                assertThat(messageId(req.payload)).isEqualTo(0)
                            }
                            // Bounded by maxInFlight = 6
                            peer.soTimeout = 150
                            val bounded = withContext(Dispatchers.IO) {
                                try {
                                    readFrame(peer)
                                    false
                                } catch (_: SocketTimeoutException) {
                                    true
                                }
                            }
                            assertThat(bounded).isTrue()
                            peer.soTimeout = 3000

                            // Peer streams 2 headers (not yet tip -> replenishes 2 pipelined requests) + 2 headers reaching tip + MsgAwaitReply
                            reply(peer, 2, F.rollForward(7, blocks[0], 4, tipHash, 4))
                            reply(peer, 2, F.rollForward(7, blocks[1], 4, tipHash, 4))
                            repeat(2) {
                                val replenished = readFrame(peer)
                                assertThat(replenished.protocol).isEqualTo(2)
                                assertThat(messageId(replenished.payload)).isEqualTo(0)
                            }

                            reply(peer, 2, F.rollForward(7, blocks[2], 4, tipHash, 4))
                            reply(peer, 2, F.rollForward(7, blocks[3], 4, tipHash, 4))
                            // Request #5 hits tip -> MsgAwaitReply!
                            reply(peer, 2, F.array(F.integer(1)))

                            // Even while paused on MsgAwaitReply, all 4 headers drain in order into NetworkChainSession!
                            val drainedHashes = mutableListOf<String>()
                            while (drainedHashes.size < 4) {
                                val batch = session.nextBatch(64)
                                drainedHashes.addAll(batch.map { it.point.hash })
                                repeat(batch.size) { session.release() }
                            }
                            assertThat(drainedHashes)
                                .containsExactly(
                                    F.blockHash(blocks[0]).toHexString(),
                                    F.blockHash(blocks[1]).toHexString(),
                                    F.blockHash(blocks[2]).toHexString(),
                                    F.blockHash(blocks[3]).toHexString(),
                                ).inOrder()

                            // Drain at most 1 replenishment frame if blocks[2] (pre-tip) triggered one before MsgAwaitReply arrived,
                            // then verify ChainSync stays completely paused while MustReply is active!
                            peer.soTimeout = 150
                            while (true) {
                                val maybeReplenish = withContext(Dispatchers.IO) {
                                    try {
                                        readFrame(peer)
                                    } catch (_: SocketTimeoutException) {
                                        null
                                    }
                                } ?: break
                                assertThat(maybeReplenish.protocol).isEqualTo(2)
                                assertThat(messageId(maybeReplenish.payload)).isEqualTo(0)
                            }
                            peer.soTimeout = 200
                            val paused = withContext(Dispatchers.IO) {
                                try {
                                    readFrame(peer)
                                    false
                                } catch (_: SocketTimeoutException) {
                                    true
                                }
                            }
                            assertThat(paused).isTrue()

                            // Now peer produces block 5 to satisfy MustReply -> ChainSync drains remaining in-flight without piling new requests!
                            val tip5Hash = F.blockHash(blocks[4])
                            reply(peer, 2, F.rollForward(7, blocks[4], 5, tip5Hash, 5))
                            val fifth = session.nextBatch(64)
                            assertThat(fifth.map { it.point.hash }).containsExactly(tip5Hash.toHexString())
                            repeat(fifth.size) { session.release() }

                            mux.shutdownGracefully()
                            running.cancelAndJoin()
                            assertAllPermitsReturned(session)
                        } finally {
                            mux.shutdownGracefully()
                            running.cancelAndJoin()
                            peer.close()
                            socket.close()
                            session.close()
                            selector.close()
                        }
                    }
                }
            }
        }

    @Test
    fun midCatchupRollbackDrainsInFlightRepliesSkipsStaleBatchesAndConservesPermits() =
        runBlocking {
            withTimeout(10000) {
                supervisorScope {
                    val forkA = F.conway(4, 1).blocks
                    val forkB3 = F.block(6, 3, previous = F.blockHash(forkA[1]))
                    val forkB4 = F.block(6, 4, previous = F.blockHash(forkB3))
                    val adopted = java.util.Collections.synchronizedList(mutableListOf<ChainPoint>())
                    val forkBDone = CountDownLatch(2)
                    var nextGen = 1L

                    val coordinator = mock(
                        ChainIngestionCoordinator::class.java,
                        org.mockito.Mockito.withSettings().defaultAnswer { call ->
                            when (call.method.name) {
                                "rollback" -> ++nextGen
                                "adopt" -> {
                                    val p = call.arguments[4] as ChainPoint
                                    adopted.add(p)
                                    if (p.hash == F.blockHash(forkB3).toHexString() || p.hash == F.blockHash(forkB4).toHexString()) {
                                        forkBDone.countDown()
                                    }
                                    null
                                }
                                else -> null
                            }
                        }
                    )

                    val session = NetworkChainSession("duplex-rollback", coordinator, false, 1, 21600)
                    val leios = LeiosFetchProtocol({ it == session.generation })
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
                        maxInFlight = 5,
                    )
                    val fetch = com.swiftmako.jormanager.nodeclient.protocols.blockfetch
                        .BlockFetchProtocol(session, leios, this)
                    val selector = ActorSelectorManager(Dispatchers.IO)
                    ServerSocket(0).use { server ->
                        val socket = aSocket(selector).tcp().connect(InetSocketAddress("127.0.0.1", server.localPort))
                        val peer = withContext(Dispatchers.IO) { server.accept().apply { soTimeout = 3000 } }
                        val mux = Mux(socket.connection())
                        val running = async(Dispatchers.IO) { mux.execute(sync, fetch, leios) }
                        try {
                            assertThat(messageId(readFrame(peer).payload)).isEqualTo(4)
                            reply(peer, 2, F.intersectFound(origin, F.tip(4, F.blockHash(forkA[3]), 4)))

                            // Drain initial 5 pipelined MsgRequestNext frames
                            repeat(5) {
                                val frame = readFrame(peer)
                                assertThat(frame.protocol).isEqualTo(2)
                                assertThat(messageId(frame.payload)).isEqualTo(0)
                            }

                            // Replies #1..#2: Fork A blocks 1..2; Reply #3: Fork A block 3 (stale); Reply #4: Rollback to block 2; Reply #5: Fork B block 3
                            // First send blocks 1..2 on Fork A so BlockFetch adopts 1..2, then block 3 + rollback + Fork B blocks 3..4!
                            reply(peer, 2, F.rollForward(6, forkA[0], 4, F.blockHash(forkA[3]), 4))
                            reply(peer, 2, F.rollForward(6, forkA[1], 4, F.blockHash(forkA[3]), 4))
                            reply(peer, 2, F.rollForward(6, forkA[2], 4, F.blockHash(forkA[3]), 4))
                            reply(
                                peer,
                                2,
                                F.array(
                                    F.integer(3),
                                    F.point(2, F.blockHash(forkA[1])),
                                    F.tip(4, F.blockHash(forkB4), 4),
                                ),
                            )
                            reply(peer, 2, F.rollForward(6, forkB3, 4, F.blockHash(forkB4), 4))

                            // Serve BlockFetch requests as they arrive while also answering subsequent ChainSync requests
                            var sentForkB4Header = false
                            var peerAwaitingReply = false
                            peer.soTimeout = 100
                            val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                            while (forkBDone.count > 0 && System.nanoTime() < deadlineNanos) {
                                val frame =
                                    withContext(Dispatchers.IO) {
                                        try {
                                            readFrame(peer)
                                        } catch (_: SocketTimeoutException) {
                                            null
                                        }
                                    } ?: continue
                                when (frame.protocol) {
                                    2 -> {
                                        assertThat(messageId(frame.payload)).isEqualTo(0)
                                        if (!sentForkB4Header) {
                                            sentForkB4Header = true
                                            reply(peer, 2, F.rollForward(6, forkB4, 4, F.blockHash(forkB4), 4))
                                        } else if (!peerAwaitingReply) {
                                            peerAwaitingReply = true
                                            reply(peer, 2, F.array(F.integer(1))) // MsgAwaitReply at Fork B tip
                                        }
                                    }
                                    3 -> {
                                        val req = CborSpans.parse(frame.payload).array()
                                        val startHash = req[1].array()[1].payload().toHexString()
                                        val endHash = req[2].array()[1].payload().toHexString()
                                        val blocksByHash = listOf(forkA[0], forkA[1], forkA[2], forkB3, forkB4)
                                            .associateBy { F.blockHash(it).toHexString() }
                                        val sequence = when {
                                            startHash == F.blockHash(forkB3).toHexString() && endHash == F.blockHash(forkB4).toHexString() ->
                                                listOf(forkB3, forkB4)
                                            startHash == endHash -> listOf(requireNotNull(blocksByHash[startHash]))
                                            startHash == F.blockHash(forkA[0]).toHexString() && endHash == F.blockHash(forkA[2]).toHexString() ->
                                                listOf(forkA[0], forkA[1], forkA[2])
                                            startHash == F.blockHash(forkA[0]).toHexString() && endHash == F.blockHash(forkA[1]).toHexString() ->
                                                listOf(forkA[0], forkA[1])
                                            else -> error("Unexpected range $startHash..$endHash")
                                        }
                                        reply(peer, 3, F.startBatch())
                                        sequence.forEach { reply(peer, 3, F.blockReply(6, it)) }
                                        reply(peer, 3, F.batchDone())
                                    }
                                }
                            }

                            assertThat(withContext(Dispatchers.IO) { forkBDone.await(3, TimeUnit.SECONDS) }).isTrue()
                            assertThat(session.generation).isEqualTo(2L)
                            assertThat(adopted.map { it.hash })
                                .containsAtLeast(
                                    F.blockHash(forkB3).toHexString(),
                                    F.blockHash(forkB4).toHexString(),
                                ).inOrder()

                            mux.shutdownGracefully()
                            sync.shutdown()
                            fetch.shutdown()
                            leios.shutdown()
                            running.cancelAndJoin()
                            session.close()
                            assertAllPermitsReturned(session)
                        } finally {
                            mux.shutdownGracefully()
                            sync.shutdown()
                            fetch.shutdown()
                            leios.shutdown()
                            running.cancelAndJoin()
                            peer.close()
                            socket.close()
                            session.close()
                            selector.close()
                        }
                    }
                }
            }
        }

    @Test
    fun nonPipelinedProtocolsPreserveHalfDuplexAgencyAndChainSyncShutdownConservesPermits() =
        runBlocking {
            val coordinator = mock(ChainIngestionCoordinator::class.java)
            val (sync, session) = protocol(coordinator)
            val leios = LeiosFetchProtocol({ true })
            val fetch = com.swiftmako.jormanager.nodeclient.protocols.blockfetch
                .BlockFetchProtocol(session, leios, this)
            val keepAlive = com.swiftmako.jormanager.nodeclient.protocols.keepalive
                .KeepAliveProtocol()
            val pooltoolSync =
                ChainSyncProtocol(
                    mock(Host::class.java),
                    ByteArray(32),
                    emptyList(),
                    mock(ChainRepository::class.java),
                    true,
                    mock(PooltoolService::class.java),
                    "",
                    "",
                    null,
                )

            try {
                assertThat(sync.duplex).isTrue()
                assertThat(pooltoolSync.duplex).isFalse()
                assertThat(fetch.duplex).isFalse()
                assertThat(leios.duplex).isFalse()
                assertThat(keepAlive.duplex).isFalse()

                // Send intersection + pipeline 5 MsgRequestNext frames directly, receive 2 replies into pendingUpdates without draining TX,
                // then shutdown() and verify all 128 NetworkChainSession permits are conserved.
                assertThat(messageId(send(sync))).isEqualTo(4)
                sync.receiveData(ByteBuffer.wrap(F.intersectFound(origin, F.tip(4, secondHash, 4))))
                repeat(5) {
                    assertThat(messageId(send(sync))).isEqualTo(0)
                }
                sync.receiveData(ByteBuffer.wrap(forward))
                sync.receiveData(
                    ByteBuffer.wrap(
                        F.array(
                            F.integer(3),
                            F.point(1, firstHash),
                            F.tip(4, secondHash, 4),
                        ),
                    ),
                )
                sync.shutdown()
                fetch.shutdown()
                leios.shutdown()
                keepAlive.shutdown()
                pooltoolSync.shutdown()
                assertAllPermitsReturned(session)
            } finally {
                sync.shutdown()
                fetch.shutdown()
                leios.shutdown()
                keepAlive.shutdown()
                pooltoolSync.shutdown()
                session.close()
            }
        }
}
