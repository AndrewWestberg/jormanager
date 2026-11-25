package com.swiftmako.jormanager.controllers.utils

import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.model.ChainPoint
import com.swiftmako.jormanager.model.ChainReadState
import com.swiftmako.jormanager.model.QueryTip
import com.swiftmako.jormanager.model.StakeAddressInfo
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.moshi.adapters.QueryUtxoJsonAdapter
import com.swiftmako.jormanager.repositories.*
import com.swiftmako.jormanager.services.ChainIngestionCoordinator
import io.mockk.*
import java.io.IOException
import java.math.BigInteger
import java.util.Optional
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.codec.Hex
import org.springframework.security.crypto.codec.Utf8
import org.springframework.security.crypto.encrypt.AesGcmBytesEncryptor
import tools.jackson.databind.ObjectMapper

class WalletUtilsTest {
    private class Fixture {
        val host = mockk<Host> {
            every { id } returns 1L
            every { nodeHomePath } returns "/node"
            every { cardanoCliPath } returns "cardano-cli"
        }
        val node = mockk<Node> {
            every { hostId } returns 1L
            every { name } returns "default"
        }
        val entries = listOf(
            WalletEntry(
                101,
                "funds",
                "payment",
                "payment-address",
                paymentSkey = File(name = "payment.skey", content = "encrypted")
            )
        )
        val wallets = mockk<WalletRepository> { every { findAllNotDeleted() } returns entries }
        val cardano = mockk<CardanoRepository> { every { networkKey(node) } returns "network" }
        val ledger = LedgerDao(cardano, ObjectMapper(), mockk(relaxed = true), MutableStateFlow(null))
        val files = mockk<FileRepository>()
        val encoder = mockk<Argon2PasswordEncoder>()
        val staking = mockk<JsonAdapter<List<StakeAddressInfo>>>()
        val tipAdapter = mockk<JsonAdapter<QueryTip>>()
        val coordinator = mockk<ChainIngestionCoordinator> {
            val syncing = ChainReadState("network", "syncing", null, null, null, false, "1")
            every { status(any()) } returns syncing
            every { walletIndexState(any()) } returns ChainIngestionCoordinator.WalletIndexState(syncing, null)
        }
        val utils = WalletUtils(
            hostRepository = mockk { every { findById(1L) } returns Optional.of(host) },
            nodeRepository = mockk { every { findDefault() } returns node },
            walletRepository = wallets,
            fileRepository = files,
            argon2PasswordEncoder = encoder,
            spendingPasswordHash = "hash",
            stakingInfoAdapter = staking,
            queryUtxoJsonAdapter = QueryUtxoJsonAdapter(),
            regCertAdapter = mockk(),
            genesisAdapter = mockk(),
            queryTipAdapter = tipAdapter,
            chainIngestionCoordinator = coordinator,
            ledgerDao = ledger,
            cardanoRepository = cardano,
        )
    }

    @Test
    fun `node balance is exact and independent of authorization records`() =
        runBlocking {
            val f = Fixture()
            val amount = BigInteger("18446744073709551615")
            mockkConstructor(HostConnection::class)
            try {
                every { anyConstructed<HostConnection>().commandArgs(any()) } returns
                    """{"${"ab".repeat(32)}#0":{"value":{"lovelace":$amount}},"${"cd".repeat(32)}#1":{"value":{"lovelace":7}}}"""
                val item = f.utils.getWalletItems("--mainnet", "dijkstra").single()
                assertEquals(amount + BigInteger.valueOf(7), item.paymentAddrLovelace)
                assertEquals(2L, item.paymentAddrUtxoCount)
                assertEquals("funds", item.name)
            } finally {
                unmockkConstructor(HostConnection::class)
            }
        }

    @Test
    fun `failed query is not presented as an empty account`() =
        runBlocking<Unit> {
            val f = Fixture()
            mockkConstructor(HostConnection::class)
            try {
                every { anyConstructed<HostConnection>().commandArgs(any()) } throws IOException("node offline")
                assertThrows<IOException> { f.utils.getWalletItems("--mainnet", "dijkstra") }
            } finally {
                unmockkConstructor(HostConnection::class)
            }
        }

    @Test
    fun `invalid reference script size fails closed`() {
        val f = Fixture()
        val connection = mockk<HostConnection> {
            every { commandArgs(any()) } returns "Reference inputs scripts size is -1 bytes."
        }
        assertThrows<IOException> {
            f.utils.queryReferenceScriptSize(
                f.host,
                connection,
                "--mainnet",
                "--socket-path /node/default/db/socket",
                "conway",
                listOf(Utxo("hash", 0, BigInteger.ONE, emptyList()))
            )
        }
    }

    @Test
    fun `empty saved wallet needs no node query`() =
        runBlocking {
            val f = Fixture()
            every { f.wallets.findAllNotDeleted() } returns emptyList()
            assertEquals(emptyList<Any>(), f.utils.getWalletItems("--mainnet", "dijkstra"))
        }

    @Test
    fun `legacy wallet key is migrated to current encryption when read`() {
        val password = "compatibility-password"
        val cleartext = """{"type":"PaymentSigningKeyShelley_ed25519","cborHex":"deadbeef"}"""
        val legacyFile = File(
            name = "payment.skey",
            content =
                "000102030405060708090a0b0c0d0e0f6a3c09e6bec9ee57bdf6ad1a34954615" +
                    "c56248020ec947b6ef5bff2b34f485d645c276912001b5dd2d14117b121773e2" +
                    "49b0562d6d20e412288c55dba35a612dfbd8b0c37d11ace443e5431c3944771a"
        )
        val f = Fixture()
        lateinit var migrated: File
        every { f.files.save(any()) } answers { firstArg<File>().also { migrated = it } }
        every { f.encoder.matches(password, "hash") } returns true
        assertEquals(cleartext, f.utils.getSKeyContent(legacyFile, password))
        val current = AesGcmBytesEncryptor.withPassword(password, WalletUtils.S).build()
        assertEquals(cleartext, Utf8.decode(current.decrypt(Hex.decode(migrated.content))))
        assertEquals(cleartext, f.utils.getSKeyContent(migrated, password))
        verify(exactly = 1) { f.files.save(any()) }
    }

    @Test
    fun `indexed UTxO fast-path skips CLI UTxO scan when index matches live node tip`() {
        val f = Fixture()
        val tipPoint = ChainPoint("12345", "ab".repeat(32))
        val readyState = ChainReadState("network", "complete", null, tipPoint, tipPoint, true, "2")
        every { f.coordinator.walletIndexState("network") } returns ChainIngestionCoordinator.WalletIndexState(readyState, 500L)
        every { f.tipAdapter.fromJson(any<String>()) } returns QueryTip("dijkstra", block = 500L, hash = tipPoint.hash, slot = 12345L)
        val indexedUtxo = Utxo("11".repeat(32), 0, BigInteger.valueOf(5_000_000L), emptyList())
        val connection = mockk<HostConnection> {
            every { commandArgs(match { "tip" in it }) } returns """{"era":"Dijkstra","block":500,"hash":"${tipPoint.hash}","slot":12345}"""
        }
        mockkObject(LedgerRepository)
        try {
            every { LedgerRepository.queryUtxos("network", setOf("payment-address")) } returns mapOf("payment-address" to listOf(indexedUtxo))
            val result = f.utils.getUtxos(f.host, connection, "--mainnet", "--socket-path /node/default/db/socket", "dijkstra", "payment-address")
            assertEquals(listOf(indexedUtxo), result)
            verify(exactly = 0) { connection.commandArgs(match { "utxo" in it }) }
        } finally {
            unmockkObject(LedgerRepository)
        }
    }

    @Test
    fun `indexed UTxO fast-path tolerates being 1 block behind tip and falls back at 2 blocks behind`() {
        val f = Fixture()
        val completePoint = ChainPoint("12345", "ab".repeat(32))
        val observedPoint = ChainPoint("12365", "cd".repeat(32))
        val followingState = ChainReadState("network", "syncing", "Following adopted chain", observedPoint, completePoint, true, "2")
        every { f.coordinator.walletIndexState("network") } returns ChainIngestionCoordinator.WalletIndexState(followingState, 500L)
        val indexedUtxo = Utxo("11".repeat(32), 0, BigInteger.valueOf(5_000_000L), emptyList())
        val cliUtxoHash = "22".repeat(32)
        val connection = mockk<HostConnection> {
            every { commandArgs(match { "tip" in it }) } returns """{"era":"Dijkstra"}"""
            every { commandArgs(match { "utxo" in it }) } returns """{"$cliUtxoHash#0":{"value":{"lovelace":9000000}}}"""
        }
        mockkObject(LedgerRepository)
        try {
            every { LedgerRepository.queryUtxos("network", setOf("payment-address")) } returns mapOf("payment-address" to listOf(indexedUtxo))

            // 1 block behind tip -> uses indexed PostgreSQL UTxOs without calling CLI query utxo
            every { f.tipAdapter.fromJson(any<String>()) } returns QueryTip("dijkstra", block = 501L, hash = observedPoint.hash, slot = 12365L)
            val oneBehind = f.utils.getUtxos(f.host, connection, "--mainnet", "--socket-path /node/default/db/socket", "dijkstra", "payment-address")
            assertEquals(listOf(indexedUtxo), oneBehind)
            verify(exactly = 0) { connection.commandArgs(match { "utxo" in it }) }

            // 2 blocks behind tip -> falls back to CLI query utxo
            every { f.tipAdapter.fromJson(any<String>()) } returns QueryTip("dijkstra", block = 502L, hash = "ef".repeat(32), slot = 12385L)
            val twoBehind = f.utils.getUtxos(f.host, connection, "--mainnet", "--socket-path /node/default/db/socket", "dijkstra", "payment-address")
            assertEquals(listOf(Utxo(cliUtxoHash, 0, BigInteger.valueOf(9_000_000L), emptyList())), twoBehind)
            verify(exactly = 1) { connection.commandArgs(match { "utxo" in it }) }
        } finally {
            unmockkObject(LedgerRepository)
        }
    }
}
