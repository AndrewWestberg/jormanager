package com.swiftmako.jormanager.controllers.utils

import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.model.StakeAddressInfo
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.moshi.adapters.QueryUtxoJsonAdapter
import com.swiftmako.jormanager.repositories.*
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
        val entries = listOf(WalletEntry(101, "funds", "payment", "payment-address",
            paymentSkey = File(name = "payment.skey", content = "encrypted")))
        val wallets = mockk<WalletRepository> { every { findAllNotDeleted() } returns entries }
        val cardano = mockk<CardanoRepository> { every { networkKey(node) } returns "network" }
        val ledger = LedgerDao(cardano, ObjectMapper(), mockk(relaxed = true), MutableStateFlow(null))
        val files = mockk<FileRepository>()
        val encoder = mockk<Argon2PasswordEncoder>()
        val staking = mockk<JsonAdapter<List<StakeAddressInfo>>>()
        val utils = WalletUtils(
            hostRepository = mockk { every { findById(1L) } returns Optional.of(host) },
            nodeRepository = mockk { every { findDefault() } returns node }, walletRepository = wallets,
            fileRepository = files, argon2PasswordEncoder = encoder, spendingPasswordHash = "hash",
            stakingInfoAdapter = staking, queryUtxoJsonAdapter = QueryUtxoJsonAdapter(), regCertAdapter = mockk(),
            genesisAdapter = mockk(), queryTipAdapter = mockk(), chainIngestionCoordinator = mockk(),
            ledgerDao = ledger, cardanoRepository = cardano,
        )
    }

    @Test
    fun `node balance is exact and independent of authorization records`() = runBlocking {
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
        } finally { unmockkConstructor(HostConnection::class) }
    }

    @Test
    fun `failed query is not presented as an empty account`() = runBlocking<Unit> {
        val f = Fixture()
        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().commandArgs(any()) } throws IOException("node offline")
            assertThrows<IOException> { f.utils.getWalletItems("--mainnet", "dijkstra") }
        } finally { unmockkConstructor(HostConnection::class) }
    }

    @Test
    fun `invalid reference script size fails closed`() {
        val f = Fixture()
        val connection = mockk<HostConnection> {
            every { commandArgs(any()) } returns "Reference inputs scripts size is -1 bytes."
        }
        assertThrows<IOException> {
            f.utils.queryReferenceScriptSize(f.host, connection, "--mainnet", "--socket-path /node/default/db/socket",
                "conway", listOf(Utxo("hash", 0, BigInteger.ONE, emptyList())))
        }
    }

    @Test
    fun `empty saved wallet needs no node query`() = runBlocking {
        val f = Fixture()
        every { f.wallets.findAllNotDeleted() } returns emptyList()
        assertEquals(emptyList<Any>(), f.utils.getWalletItems("--mainnet", "dijkstra"))
    }

    @Test
    fun `legacy wallet key is migrated to current encryption when read`() {
        val password = "compatibility-password"
        val cleartext = """{"type":"PaymentSigningKeyShelley_ed25519","cborHex":"deadbeef"}"""
        val legacyFile = File(name = "payment.skey", content =
            "000102030405060708090a0b0c0d0e0f6a3c09e6bec9ee57bdf6ad1a34954615" +
                "c56248020ec947b6ef5bff2b34f485d645c276912001b5dd2d14117b121773e2" +
                "49b0562d6d20e412288c55dba35a612dfbd8b0c37d11ace443e5431c3944771a")
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
}
