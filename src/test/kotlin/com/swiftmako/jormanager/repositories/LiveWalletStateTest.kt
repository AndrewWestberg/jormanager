package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.nodeclient.protocols.fixtures.CatchupFixtures as F
import com.swiftmako.jormanager.services.SubmittedTransactionDecoder
import com.swiftmako.jormanager.utils.Bech32
import io.mockk.*
import java.math.BigInteger
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

class LiveWalletStateTest {
    private val address = Bech32.encode("addr_test", F.address)
    private val root = Utxo("11".repeat(32), 0, BigInteger.valueOf(10_000_000), emptyList())
    private class Fixture {
        val host = mockk<Host> {
            every { cardanoCliPath } returns "cli"
            every { nodeHomePath } returns "/node"
        }
        val node = mockk<Node> { every { name } returns "relay" }
        val eras = mockk<CardanoRepository> {
            every { networkKey(node) } returns "test-network"
            every { networkArguments(node) } returns listOf("--testnet-magic", "164")
        }
        val jdbc = mockk<org.springframework.jdbc.core.JdbcTemplate>(relaxed = true)
        val ledger = LedgerDao(eras, ObjectMapper(), jdbc, MutableStateFlow(null))
    }
    private fun transaction(input: Utxo, coin: Long, era: String): Pair<String, String> {
        val body = F.map(listOf(F.integer(0) to F.array(F.input(input.hash.hexToByteArray(), input.ix.toInt())),
            F.integer(1) to F.array(F.output(coin = coin)), F.integer(2) to F.integer(200_000)))
        val hex = (if (era == "dijkstra") F.array(body, F.map(emptyList()), byteArrayOf(0xf6.toByte()))
            else F.array(body, F.map(emptyList()), byteArrayOf(0xf5.toByte()), byteArrayOf(0xf6.toByte()))).toHexString()
        return SubmittedTransactionDecoder.decodeSubmitted(hex, era).id to """{"cborHex":"$hex"}"""
    }

    @Test
    fun `accepted change replaces inputs until confirmation in both transaction encodings`() {
        for (era in listOf("conway", "dijkstra")) {
            val f = Fixture()
            val (id, envelope) = transaction(root, 9_800_000, era)
            val change = root.copy(hash = id, lovelace = BigInteger.valueOf(9_800_000))
            assertEquals(listOf(root), f.ledger.liveUtxos("test-network", address, listOf(root)))
            f.ledger.recordSubmitted(f.host, f.node, era, id, envelope)
            assertEquals(listOf(change), f.ledger.liveUtxos("test-network", address, listOf(root)))
            assertEquals(listOf(change), f.ledger.liveUtxos("test-network", address, listOf(change)))
            assertEquals(listOf(root), f.ledger.liveUtxos("another-network", address, listOf(root)))
            f.ledger.confirmed("test-network", BigInteger.TEN, listOf(id))
            assertEquals(listOf(change), f.ledger.liveUtxos("test-network", address, listOf(change)))
            assertEquals(emptyList<Utxo>(), f.ledger.liveUtxos("test-network", address, emptyList()))
            val restarted = LedgerDao(f.eras, ObjectMapper(), f.jdbc, MutableStateFlow(null))
            assertEquals(listOf(root), restarted.liveUtxos("test-network", address, listOf(root)))
        }
    }

    @Test
    fun `chained accepted change is not counted twice and rollback replays only acknowledged transactions`() {
        val f = Fixture()
        val (first, firstEnvelope) = transaction(root, 9_800_000, "dijkstra")
        val firstChange = root.copy(hash = first, lovelace = BigInteger.valueOf(9_800_000))
        val (second, secondEnvelope) = transaction(firstChange, 9_600_000, "dijkstra")
        val secondChange = root.copy(hash = second, lovelace = BigInteger.valueOf(9_600_000))
        f.ledger.recordSubmitted(f.host, f.node, "dijkstra", first, firstEnvelope)
        f.ledger.recordSubmitted(f.host, f.node, "dijkstra", second, secondEnvelope)
        assertEquals(listOf(secondChange), f.ledger.liveUtxos("test-network", address, listOf(root)))
        f.ledger.confirmed("test-network", BigInteger.TEN, listOf(first, second))
        val replayed = mutableListOf<String>()
        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().commandWriteFile(any(), any()) } answers {
                replayed += secondArg<String>(); ""
            }
            every { anyConstructed<HostConnection>().commandArgs(any()) } returns ""
            f.ledger.rollback("another-network", BigInteger.ZERO)
            assertTrue(replayed.isEmpty())
            f.ledger.rollback("test-network", BigInteger.ONE)
            assertEquals(listOf(firstEnvelope, secondEnvelope), replayed)
            assertEquals(listOf(secondChange), f.ledger.liveUtxos("test-network", address, listOf(root)))
        } finally { unmockkConstructor(HostConnection::class) }
    }
}
