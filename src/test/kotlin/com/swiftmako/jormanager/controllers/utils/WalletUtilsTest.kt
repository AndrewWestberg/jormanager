package com.swiftmako.jormanager.controllers.utils

import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.model.NativeAsset
import com.swiftmako.jormanager.model.StakeAddressInfo
import com.swiftmako.jormanager.model.WalletItem
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.squareup.moshi.JsonAdapter
import io.mockk.Called
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.util.Optional
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.repositories.FileRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.math.BigInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.codec.Hex
import org.springframework.security.crypto.codec.Utf8
import org.springframework.security.crypto.encrypt.AesGcmBytesEncryptor

class WalletUtilsTest {
    private class ListingFixture {
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
            WalletEntry(101, "Imported payment", "payment", "payment-address",
                paymentSkey = File(name = "payment.skey", content = "synthetic-encrypted-key")),
            WalletEntry(102, "Imported stake", "stake", "stake-payment-address", stakingAddr = "stake-address"),
        )
        val wallets = mockk<WalletRepository> { every { findAllNotDeleted() } returns entries }
        val ledger = mockk<LedgerDao>()
        val staking = mockk<JsonAdapter<List<StakeAddressInfo>>>()
        val utils = WalletUtils(
            hostRepository = mockk<HostRepository> { every { findById(1L) } returns Optional.of(host) },
            nodeRepository = mockk<NodeRepository> { every { findDefault() } returns node },
            walletRepository = wallets, fileRepository = mockk(), argon2PasswordEncoder = mockk(),
            spendingPasswordHash = "", stakingInfoAdapter = staking, regCertAdapter = mockk(),
            genesisAdapter = mockk(), queryTipAdapter = mockk(), chainIngestionCoordinator = mockk(),
            ledgerDao = ledger,
            cardanoRepository = mockk<CardanoRepository> { every { networkKey(node) } returns "network" },
        )
        fun unavailable() = entries.map {
            WalletItem(it.id!!, it.name, it.type, it.paymentAddr, it.paymentSkey != null,
                null, null, it.stakingAddr, null, null, null)
        }
    }

    @Test
    fun `unavailable listing preserves identities and stops balance queries`() = runBlocking<Unit> {
        val f = ListingFixture()
        every { f.ledger.queryUtxos("network", "payment-address") } throws LedgerDao.WalletIndexUnavailableException()

        assertEquals(f.unavailable(), f.utils.getWalletItems("--mainnet", "conway"))
        verify(exactly = 1) { f.wallets.findAllNotDeleted() }
        verify(exactly = 1) { f.ledger.queryUtxos(any(), any()) }
        verify { f.staking wasNot Called }
        assertThrows<LedgerDao.WalletIndexUnavailableException> {
            f.utils.getUtxos(f.host, mockk(), "--mainnet", "--socket-path /node/default/db/socket", "conway", "payment-address")
        }
    }

    @Test
    fun `partial balances are discarded and later reads recover without saving`() = runBlocking {
        val f = ListingFixture()
        val amount = BigInteger("18446744073709551615")
        every { f.ledger.queryUtxos("network", "payment-address") } returns
            listOf(Utxo("hash", 0, amount, listOf(NativeAsset("asset", "policy", amount))))
        every { f.ledger.queryUtxos("network", "stake-payment-address") } throws LedgerDao.WalletIndexUnavailableException()
        assertEquals(f.unavailable(), f.utils.getWalletItems("--mainnet", "conway"))
        verify { f.staking wasNot Called }

        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().commandArgs(any()) } returns "[]"
            every { f.staking.fromJson("[]") } returns emptyList()
            every { f.ledger.queryUtxos("network", "stake-payment-address") } returns emptyList()
            val recovered = f.utils.getWalletItems("--mainnet", "conway")
            assertEquals(amount, recovered[0].paymentAddrLovelace)
            assertEquals(mapOf("policy.asset" to amount), recovered[0].nativeAssetMap)
            assertEquals(1L, recovered[0].paymentAddrUtxoCount)
            assertEquals(BigInteger.ZERO, recovered[1].paymentAddrLovelace)
            assertEquals(0L, recovered[1].paymentAddrUtxoCount)
            assertEquals(emptyMap<String, BigInteger>(), recovered[1].nativeAssetMap)
            assertEquals(false, recovered[1].stakingAddrRegistered)
            assertEquals(BigInteger.ZERO, recovered[1].stakingAddrLovelace)
            verify(exactly = 0) { f.wallets.save(any()) }
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test
    fun `listing propagates unrelated failures and cancellation`() = runBlocking {
        val f = ListingFixture()
        val failure = IOException("database failed")
        every { f.ledger.queryUtxos(any(), any()) } throws failure
        assertEquals(failure, assertThrows<IOException> { f.utils.getWalletItems("--mainnet", "conway") })
        val cancellation = CancellationException("cancelled")
        every { f.ledger.queryUtxos(any(), any()) } throws cancellation
        assertEquals(cancellation, assertThrows<CancellationException> { f.utils.getWalletItems("--mainnet", "conway") })
    }

    @Test
    fun `empty saved wallet needs no coverage`() = runBlocking {
        val f = ListingFixture()
        every { f.wallets.findAllNotDeleted() } returns emptyList()
        assertEquals(emptyList<WalletItem>(), f.utils.getWalletItems("--mainnet", "conway"))
        verify { f.ledger wasNot Called }
    }

    @Test
    fun `invalid reference script size fails closed`() {
        val host = mockk<Host> { every { cardanoCliPath } returns "cardano-cli" }
        val hostConnection = mockk<HostConnection> {
            every { command(any<String>()) } returns "Reference inputs scripts size is -1 bytes."
        }
        val walletUtils =
            WalletUtils(
                hostRepository = mockk(),
                nodeRepository = mockk(),
                walletRepository = mockk(),
                fileRepository = mockk(),
                argon2PasswordEncoder = mockk(),
                spendingPasswordHash = "",
                stakingInfoAdapter = mockk(),
                genesisAdapter = mockk(),
                queryTipAdapter = mockk(),
                chainIngestionCoordinator = mockk(),
                cardanoRepository = mockk(),
                regCertAdapter = mockk(),
                ledgerDao = mockk(),
            )

        assertThrows<IOException> {
            walletUtils.queryReferenceScriptSize(
                host,
                hostConnection,
                "--mainnet",
                "--socket-path /node.socket",
                "conway",
                listOf(Utxo("hash", 0L, BigInteger.ONE, emptyList())),
            )
        }
    }

    @Test
    fun `legacy wallet key is migrated to current encryption when read`() {
        val password = "compatibility-password"
        val cleartext = """{"type":"PaymentSigningKeyShelley_ed25519","cborHex":"deadbeef"}"""
        val legacyFile =
            File(
                name = "payment.skey",
                content =
                    "000102030405060708090a0b0c0d0e0f6a3c09e6bec9ee57bdf6ad1a34954615" +
                        "c56248020ec947b6ef5bff2b34f485d645c276912001b5dd2d14117b121773e2" +
                        "49b0562d6d20e412288c55dba35a612dfbd8b0c37d11ace443e5431c3944771a",
            )
        lateinit var migratedFile: File
        val fileRepository = mockk<FileRepository> {
            every { save(any()) } answers {
                migratedFile = firstArg()
                migratedFile
            }
        }
        val passwordEncoder = mockk<Argon2PasswordEncoder> {
            every { matches(password, "hash") } returns true
        }
        val walletUtils =
            WalletUtils(
                hostRepository = mockk(),
                nodeRepository = mockk(),
                walletRepository = mockk(),
                fileRepository = fileRepository,
                argon2PasswordEncoder = passwordEncoder,
                spendingPasswordHash = "hash",
                stakingInfoAdapter = mockk(),
                genesisAdapter = mockk(),
                queryTipAdapter = mockk(),
                chainIngestionCoordinator = mockk(),
                cardanoRepository = mockk(),
                regCertAdapter = mockk(),
                ledgerDao = mockk(),
            )

        assertEquals(cleartext, walletUtils.getSKeyContent(legacyFile, password))

        val currentEncryptor = AesGcmBytesEncryptor.withPassword(password, WalletUtils.S).build()
        assertEquals(cleartext, Utf8.decode(currentEncryptor.decrypt(Hex.decode(migratedFile.content))))
        assertEquals(cleartext, walletUtils.getSKeyContent(migratedFile, password))
        verify(exactly = 1) { fileRepository.save(any()) }
    }
}
