package com.swiftmako.jormanager.services

import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.ChainPoint
import com.swiftmako.jormanager.model.ChainReadState
import com.swiftmako.jormanager.model.NativeAsset
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.repositories.LedgerRepository
import com.swiftmako.jormanager.utils.Bech32
import com.swiftmako.jormanager.utils.CborSpans
import io.mockk.every
import io.mockk.mockk
import tools.jackson.databind.ObjectMapper
import java.math.BigInteger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PendingWalletStateTest {
    private val addressBytes = byteArrayOf(0x60) + ByteArray(28) { 7 }
    private val address = LedgerRepository.addressText(addressBytes)
    private val stakeBytes = byteArrayOf(0xe0.toByte()) + ByteArray(28) { 9 }
    private val stake = Bech32.encode("stake_test", stakeBytes)
    private val network = "network"
    private val ready = ChainReadState(network, "syncing", null, null, null, true, "4")
    private val root = "11".repeat(32)
    private val policy = "22".repeat(28)
    private val large = BigInteger("9007199254740993")

    private fun concat(items: List<ByteArray>): ByteArray = items.fold(byteArrayOf()) { a, b -> a + b }
    private fun header(major: Int, value: BigInteger): ByteArray {
        require(value.signum() >= 0 && value.bitLength() <= 64)
        if (value < BigInteger.valueOf(24)) return byteArrayOf(((major shl 5) or value.toInt()).toByte())
        val width = when { value.bitLength() <= 8 -> 1; value.bitLength() <= 16 -> 2; value.bitLength() <= 32 -> 4; else -> 8 }
        val prefix = when (width) { 1 -> 24; 2 -> 25; 4 -> 26; else -> 27 }
        return byteArrayOf(((major shl 5) or prefix).toByte()) + ByteArray(width) { i -> value.shiftRight((width - i - 1) * 8).toByte() }
    }
    private fun uint(value: Long) = header(0, BigInteger.valueOf(value))
    private fun bytes(value: ByteArray) = header(2, BigInteger.valueOf(value.size.toLong())) + value
    private fun array(vararg items: ByteArray) = header(4, BigInteger.valueOf(items.size.toLong())) + concat(items.toList())
    private fun map(vararg items: Pair<ByteArray, ByteArray>) = header(5, BigInteger.valueOf(items.size.toLong())) + concat(items.flatMap { listOf(it.first, it.second) })
    private fun input(hash: String, index: Int = 0) = array(bytes(hash.hexToByteArray()), uint(index.toLong()))
    private fun output(coin: Long, asset: Boolean = false): ByteArray {
        val value = if (!asset) uint(coin) else array(uint(coin), map(bytes(policy.hexToByteArray()) to
            map(bytes(byteArrayOf(1)) to header(0, large), bytes(byteArrayOf(2)) to uint(2))))
        return array(bytes(addressBytes), value)
    }
    private fun operation(id: String, from: String, coin: Long, asset: Boolean = false, state: String = "submitted",
                          withdrawal: Long? = null, collateral: String? = null, until: Long? = null): PendingWalletState.Operation {
        val fields = mutableListOf(uint(0) to (byteArrayOf(0xd9.toByte(), 0x01, 0x02) + array(input(from))),
            uint(1) to array(output(coin, asset)), uint(2) to uint(200000))
        if (withdrawal != null) fields += uint(5) to map(bytes(stakeBytes) to uint(withdrawal))
        if (collateral != null) fields += uint(13) to array(input(collateral))
        if (until != null) fields += uint(3) to uint(until)
        val envelope = array(map(*fields.toTypedArray()), map(), byteArrayOf(0xf5.toByte()), byteArrayOf(0xf6.toByte())).toHexString()
        val decoded = ReviewedTransactionIdentity.decodeSubmitted(envelope, "babbage")
        return PendingWalletState.Operation(id, network, decoded.id, "babbage", state, envelope, null, until)
    }
    private fun utxo(hash: String, coin: Long, asset: Boolean = false) = Utxo(hash, 0, BigInteger.valueOf(coin),
        if (asset) listOf(NativeAsset("02", policy, BigInteger.TWO), NativeAsset("01", policy, large)) else emptyList())
    private fun node(value: Utxo) = (value.hash to value.ix.toInt()) to PendingWalletState.PendingOutput(address, value, null)
    private fun project(ops: List<PendingWalletState.Operation>, base: List<Utxo>, node: List<Utxo>,
                        mempool: Map<String, Boolean>, excluded: String? = null, slot: Long = 10): PendingWalletState.Snapshot =
        PendingWalletState.merge(PendingWalletState.DatabaseSnapshot(ready, mapOf(address to base), ops, emptyList()),
            setOf(address), node.map(::node).toMap(), mempool, BigInteger.valueOf(slot), excluded)
    private fun balance(snapshot: PendingWalletState.Snapshot) = snapshot.utxosByAddress.getValue(address).sumOf { it.lovelace }

    private inner class NodeFixture {
        val op = operation("a", root, 59_800_000, asset = true)
        val host = mockk<Host>()
        val node = mockk<Node>()
        val connection = mockk<HostConnection>()
        val ledger = mockk<LedgerDao>()
        val coordinator = mockk<ChainIngestionCoordinator>()
        val cardano = mockk<CardanoRepository>()
        val service = PendingWalletState(ledger, coordinator, cardano, ObjectMapper())
        var database = PendingWalletState.DatabaseSnapshot(ready, mapOf(address to listOf(utxo(root, 100_000_000, true))), listOf(op), emptyList())
        var current = ready
        var tip = """{"slot":10,"hash":"${"aa".repeat(32)}","era":"babbage"}"""
        var mempool = """{"exists":true,"txId":"${op.txId}","slot":10}"""
        var utxos = """{"$root#0":{"address":"$address","value":{"lovelace":100000000,"$policy":{"02":2,"01":$large}}}}"""
        var failure: RuntimeException? = null
        val calls = mutableListOf<List<String>>()

        init {
            every { host.id } returns 1L
            every { host.nodeHomePath } returns "/node"
            every { host.cardanoCliPath } returns "cardano-cli"
            every { node.id } returns 2L
            every { node.hostId } returns 1L
            every { node.name } returns "relay"
            every { cardano.networkKey(node) } returns network
            every { cardano.getEra(host, node, "--mainnet", "--socket-path /node/relay/db/socket") } returns "babbage"
            every { cardano.evidence(node) } returns CardanoRepository.EraEvidence(network, "babbage", "current", "host:1/node:2")
            every { ledger.querySnapshot(network, setOf(address)) } answers { database }
            every { coordinator.status(network) } answers { current }
            every { connection.commandArgs(any()) } answers {
                val args = firstArg<List<String>>()
                calls += args
                when {
                    "tx-mempool" in args -> { failure?.let { throw it }; mempool }
                    "utxo" in args -> utxos
                    "tip" in args -> tip
                    else -> error("Unexpected node command")
                }
            }
        }
        fun snapshot() = service.snapshot(host, node, connection, "--mainnet", "--socket-path /node/relay/db/socket", "babbage", setOf(address))
    }

    @Test fun `snapshot observes only local mempool ids and parses exact node values`() {
        val fixture = NodeFixture()
        val result = fixture.snapshot()
        assertEquals(BigInteger.valueOf(59_800_000), balance(result))
        assertEquals(large, result.utxosByAddress.getValue(address).single().nativeAssets.first().amount)
        val mempool = fixture.calls.single { "tx-mempool" in it }
        assertEquals(listOf("tx-exists", fixture.op.txId), mempool.takeLast(2))
        assertTrue(fixture.calls.all { "--mainnet" in it && "--socket-path" in it })
        fixture.mempool = """{"exists":false,"txId":"${fixture.op.txId}","slot":10}"""
        assertEquals(BigInteger.ZERO, balance(fixture.snapshot()))
    }

    @Test fun `snapshot rejects failed malformed stale or mismatched node observations`() {
        val fixture = NodeFixture()
        fixture.failure = IllegalStateException("node command failed")
        assertThrows(IllegalStateException::class.java) { fixture.snapshot() }
        fixture.tip = """{"slot":10,"hash":"${"aa".repeat(32)}","era":"conway"}"""
        assertThrows(IllegalArgumentException::class.java) { fixture.snapshot() }
        fixture.tip = """{"slot":10,"hash":"${"aa".repeat(32)}","era":"babbage"}"""
        fixture.failure = null
        for (response in listOf(
            """{"exists":false,"txId":"$root","slot":10}""",
            """{"exists":false,"txId":"${fixture.op.txId}","slot":9}""",
            """{"exists":"false","txId":"${fixture.op.txId}","slot":10}""",
            """{"exists":false,"txId":"${fixture.op.txId}"}"""
        )) {
            fixture.mempool = response
            assertThrows(IllegalArgumentException::class.java) { fixture.snapshot() }
        }
        fixture.mempool = """{"exists":true,"txId":"${fixture.op.txId}","slot":10}"""
        for (response in listOf(
            """{"$root#0":{"address":"wrong","value":{"lovelace":100000000}}}""",
            """{"$root#65536":{"address":"$address","value":{"lovelace":1}}}""",
            """{"$root#0":{"address":"$address","value":{"lovelace":-1}}}""",
            """{"$root#0":{"address":"$address","value":{"lovelace":100000000,"$policy":{"01":9007199254740994}}}}"""
        )) {
            fixture.utxos = response
            assertThrows(IllegalArgumentException::class.java) { fixture.snapshot() }
        }
    }

    @Test fun `snapshot fences generation but permits a newer committed point`() {
        val fixture = NodeFixture()
        fixture.current = ready.copy(lastCompletePoint = ChainPoint("11", "bb".repeat(32)))
        assertEquals(BigInteger.valueOf(59_800_000), balance(fixture.snapshot()))
        fixture.calls.clear()
        fixture.current = ready.copy(generation = "5")
        val changed = assertThrows(IllegalStateException::class.java) { fixture.snapshot() }
        assertTrue(fixture.calls.any { "utxo" in it })
        assertFalse(changed is LedgerDao.WalletIndexUnavailableException)
        fixture.current = ready.copy(walletReady = false)
        assertThrows(LedgerDao.WalletIndexUnavailableException::class.java) { fixture.snapshot() }
        fixture.database = fixture.database.copy(readState = ready.copy(walletReady = false))
        val initial = assertThrows(LedgerDao.WalletIndexUnavailableException::class.java) { fixture.snapshot() }
        assertEquals(false, initial.readState.walletReady)
    }

    @Test fun `chained submissions and confirmation preserve exact available balance`() {
        for (asset in listOf(false, true)) {
            val a = operation("a", root, 59_800_000, asset)
            val b = operation("b", a.txId, 49_600_000, asset)
            val initial = utxo(root, 100_000_000, asset)
            val changeA = utxo(a.txId, 59_800_000, asset)
            val changeB = utxo(b.txId, 49_600_000, asset)
            assertEquals(BigInteger.valueOf(59_800_000), balance(project(listOf(a), listOf(initial), listOf(initial), mapOf("a" to true))))
            val pending = project(listOf(a, b), listOf(initial), listOf(initial), mapOf("a" to true, "b" to true))
            assertEquals(BigInteger.valueOf(49_600_000), balance(pending))
            if (asset) {
                val projected = pending.utxosByAddress.getValue(address).single().nativeAssets
                assertEquals(listOf("01", "02"), projected.map { it.name })
                assertEquals(large, projected.first().amount)
                val confirmed = project(listOf(a.copy(state = "confirmed"), b.copy(state = "confirmed")),
                    listOf(changeB), listOf(changeB), emptyMap()).utxosByAddress.getValue(address).single().nativeAssets
                assertEquals(projected.map { Triple(it.policy, it.name, it.amount) },
                    confirmed.map { Triple(it.policy, it.name, it.amount) })
            }
            val aConfirmed = a.copy(state = "confirmed")
            assertEquals(BigInteger.valueOf(49_600_000), balance(project(listOf(aConfirmed, b), listOf(changeA), listOf(changeA), mapOf("b" to true))))
            assertEquals(BigInteger.valueOf(49_600_000), balance(project(listOf(aConfirmed, b.copy(state = "confirmed")), listOf(changeB), listOf(changeB), emptyMap())))
            assertEquals(BigInteger.ZERO, balance(project(listOf(a, b), listOf(initial), listOf(initial), mapOf("a" to false, "b" to true))))
            assertEquals(BigInteger.ZERO, balance(project(listOf(a.copy(state = "rolled_back"), b), listOf(initial), listOf(initial), mapOf("b" to true))))
        }
    }

    @Test fun `uncertain expiry collateral self exclusion and rewards remain conservative`() {
        val collateral = "33".repeat(32)
        val op = operation("a", root, 59_800_000, withdrawal = 123, collateral = collateral, until = 20)
        val initial = utxo(root, 100_000_000)
        val other = utxo(collateral, 2_000_000)
        val node = listOf(initial, other)
        val uncertain = project(listOf(op.copy(state = "submission_uncertain")), node, node, mapOf("a" to true))
        assertEquals(BigInteger.ZERO, balance(uncertain))
        assertEquals(BigInteger.valueOf(123), uncertain.reservedRewardWithdrawals[stake])
        assertEquals(BigInteger.ZERO, balance(project(listOf(op), node, node, mapOf("a" to true), slot = 20)))
        assertEquals(BigInteger.valueOf(102_000_000), balance(project(listOf(op), node, node, mapOf("a" to true), excluded = "a")))
        assertEquals(BigInteger.valueOf(59_800_000), balance(project(listOf(op), node, node, mapOf("a" to true))))
    }

    @Test fun `confirmed node race deduplicates and rejects value conflicts`() {
        val op = operation("a", root, 59_800_000, asset = true)
        val change = utxo(op.txId, 59_800_000, true)
        val snapshot = project(listOf(op), listOf(change), listOf(change), mapOf("a" to false))
        assertEquals(BigInteger.valueOf(59_800_000), balance(snapshot))
        assertEquals(1, snapshot.utxosByAddress.getValue(address).size)
        assertThrows(IllegalArgumentException::class.java) {
            project(listOf(op), listOf(change), listOf(change.copy(nativeAssets = listOf(NativeAsset("01", policy, large + BigInteger.ONE)))), mapOf("a" to false))
        }
    }
    @Test fun `unusable ancestors block descendants unless exact output is confirmed`() {
        val a = operation("a", root, 59_800_000, until = 20)
        val b = operation("b", a.txId, 49_600_000)
        val initial = utxo(root, 100_000_000)
        val parentOutput = utxo(a.txId, 59_800_000)
        for (state in listOf("submission_uncertain", "rolled_back", "phase2_failed", "expired", "not_submitted")) {
            val expected = if (state in setOf("submission_uncertain", "rolled_back")) BigInteger.ZERO else BigInteger.valueOf(100_000_000)
            assertEquals(expected, balance(project(listOf(a.copy(state = state), b), listOf(initial), listOf(initial), mapOf("b" to true))))
            assertEquals(BigInteger.valueOf(49_600_000), balance(project(listOf(a.copy(state = state), b), emptyList(), listOf(parentOutput), mapOf("b" to true))))
        }
        assertEquals(BigInteger.ZERO, balance(project(listOf(a, b), listOf(initial), listOf(initial), mapOf("a" to true, "b" to true), slot = 20)))
        assertEquals(BigInteger.valueOf(100_000_000), balance(project(listOf(a, b), listOf(initial), listOf(initial), mapOf("a" to true, "b" to true), excluded = "a")))
    }

    @Test fun `unsigned reservation withholds without inventing outputs`() {
        val initial = utxo(root, 100_000_000)
        val unsigned = PendingWalletState.Operation("a", network, root, "babbage", "signing", null, null, null)
        val db = PendingWalletState.DatabaseSnapshot(ready, mapOf(address to listOf(initial)), listOf(unsigned),
            listOf(PendingWalletState.Reservation("a", root, 0, "signing")))
        val observed = mapOf(node(initial))
        assertEquals(BigInteger.ZERO, balance(PendingWalletState.merge(db, setOf(address), observed, emptyMap(), BigInteger.TEN)))
        assertEquals(BigInteger.valueOf(100_000_000), balance(PendingWalletState.merge(db, setOf(address), observed, emptyMap(), BigInteger.TEN, "a")))
        assertEquals(BigInteger.ZERO, balance(project(emptyList(), emptyList(), listOf(initial), emptyMap())))
    }


    @Test fun `stored data errors and network mismatches fail closed`() {
        val op = operation("a", root, 59_800_000)
        assertThrows(IllegalArgumentException::class.java) { project(listOf(op.copy(networkKey = "other")), emptyList(), emptyList(), emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { project(listOf(op.copy(signedBatch = "ffff")), emptyList(), emptyList(), emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { project(listOf(op.copy(txId = root)), emptyList(), emptyList(), emptyMap()) }
    }
    @Test fun `pending reference script retains the embedded script bytes`() {
        val script = array(uint(1), bytes(byteArrayOf(1, 2, 3)))
        val out = map(uint(0) to bytes(addressBytes), uint(1) to uint(10_000_000),
            uint(3) to (byteArrayOf(0xd8.toByte(), 0x18) + bytes(script)))
        val body = map(uint(0) to array(input(root)), uint(1) to array(out), uint(2) to uint(200000))
        val envelope = array(body, map(), byteArrayOf(0xf5.toByte()), byteArrayOf(0xf6.toByte())).toHexString()
        val decoded = ReviewedTransactionIdentity.decodeSubmitted(envelope, "babbage")
        val op = PendingWalletState.Operation("script", network, decoded.id, "babbage", "submitted", envelope, null, null)
        val snapshot = project(listOf(op), emptyList(), emptyList(), mapOf("script" to true))
        assertArrayEquals(script, snapshot.pendingOutputs.getValue(decoded.id to 0).referenceScript)
    }


    @Test fun `dijkstra member ids and internal spends use real decoder`() {
        val childBody = map(uint(0) to array(input(root)), uint(1) to array(output(70_000_000)))
        val childId = CborSpans.parse(childBody).hash256().toHexString()
        val child = array(childBody, map(), byteArrayOf(0xf6.toByte()))
        val body = map(uint(0) to array(input(childId)), uint(1) to array(output(69_800_000)),
            uint(2) to uint(200000), uint(23) to array(child))
        val envelope = array(body, map(), byteArrayOf(0xf6.toByte())).toHexString()
        val decoded = ReviewedTransactionIdentity.decodeSubmitted(envelope, "dijkstra")
        val op = PendingWalletState.Operation("batch", network, decoded.id, "dijkstra", "submitted", envelope, null, null)
        val snapshot = project(listOf(op), listOf(utxo(root, 100_000_000)), listOf(utxo(root, 100_000_000)), mapOf("batch" to true))
        assertEquals(BigInteger.valueOf(69_800_000), balance(snapshot))
        assertEquals(setOf(decoded.id to 0), snapshot.pendingOutputs.keys)
        assertTrue(decoded.outputs.containsKey(childId to 0))
    }
}
