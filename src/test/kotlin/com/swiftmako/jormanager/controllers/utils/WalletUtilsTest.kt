package com.swiftmako.jormanager.controllers.utils

import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.model.ChainReadState
import com.swiftmako.jormanager.model.NativeAsset
import com.swiftmako.jormanager.model.StakeAddressInfo
import com.swiftmako.jormanager.model.WalletItem
import com.swiftmako.jormanager.model.WalletResult
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.services.PendingWalletState
import com.swiftmako.jormanager.services.ChainIngestionCoordinator
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
        val pending = mockk<PendingWalletState>()
        val staking = mockk<JsonAdapter<List<StakeAddressInfo>>>()
        var readState = ChainReadState("network", "complete", null, null, null, true, "1")
        val unavailableState = readState.copy(status = "incomplete", walletReady = false)
        val chain = mockk<ChainIngestionCoordinator> { every { status("network") } answers { readState } }
        val utils = WalletUtils(
            hostRepository = mockk<HostRepository> { every { findById(1L) } returns Optional.of(host) },
            nodeRepository = mockk<NodeRepository> { every { findDefault() } returns node },
            walletRepository = wallets, fileRepository = mockk(), argon2PasswordEncoder = mockk(),
            spendingPasswordHash = "", stakingInfoAdapter = staking, regCertAdapter = mockk(),
            genesisAdapter = mockk(), queryTipAdapter = mockk(), chainIngestionCoordinator = chain,
            pendingWalletState = pending,
            cardanoRepository = mockk<CardanoRepository> { every { networkKey(node) } returns "network" },
        )
        fun unavailable(state: ChainReadState = unavailableState) = WalletResult(entries.map {
            WalletItem(it.id!!, it.name, it.type, it.paymentAddr, it.paymentSkey != null,
                null, null, it.stakingAddr, null, null, null)
        }, "network", state)
    }

    private fun snapshot(
        utxos: Map<String, List<Utxo>> = emptyMap(),
        rewards: Map<String, BigInteger> = emptyMap(),
        pendingOutputs: Map<Pair<String, Int>, PendingWalletState.PendingOutput> = emptyMap(),
    ) = PendingWalletState.Snapshot(
        ChainReadState("network", "complete", null, null, null, true, "1"),
        utxos, pendingOutputs, rewards,
    )

    @Test
    fun `unavailable listing preserves identities and stops balance queries`() = runBlocking<Unit> {
        val f = ListingFixture()
        every { f.pending.snapshot(any(), any(), any(), any(), any(), any(), any(), any()) } throws LedgerDao.WalletIndexUnavailableException(f.unavailableState)

        assertEquals(f.unavailable(), f.utils.getWalletItems("--mainnet", "conway"))
        verify(exactly = 1) { f.wallets.findAllNotDeleted() }
        verify(exactly = 1) { f.pending.snapshot(any(), any(), any(), any(), any(), any(), any(), any()) }
        verify { f.staking wasNot Called }
        assertThrows<LedgerDao.WalletIndexUnavailableException> {
            f.utils.getUtxoSnapshot(f.host, f.node, mockk(), "--mainnet", "--socket-path /node/default/db/socket", "conway", setOf("payment-address"))
        }
    }

    @Test
    fun `snapshot balances recover together and withhold reserved rewards`() = runBlocking {
        val f = ListingFixture()
        val amount = BigInteger("18446744073709551615")
        every { f.pending.snapshot(any(), any(), any(), any(), any(), any(), any(), any()) } throws LedgerDao.WalletIndexUnavailableException(f.unavailableState)
        assertEquals(f.unavailable(), f.utils.getWalletItems("--mainnet", "conway"))
        verify { f.staking wasNot Called }

        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().commandArgs(any()) } returns "[]"
            every { f.staking.fromJson("[]") } returns listOf(StakeAddressInfo("stake-address", rewardAccountBalance = BigInteger.valueOf(20)))
            every { f.pending.snapshot(any(), any(), any(), any(), any(), any(), any(), any()) } returns snapshot(
                mapOf(
                    "payment-address" to listOf(Utxo("hash", 0, amount, listOf(NativeAsset("asset", "policy", amount)))),
                    "stake-payment-address" to listOf(Utxo("change", 1, BigInteger.TEN, emptyList())),
                ),
                mapOf("stake-address" to BigInteger.valueOf(7)),
            )
            val recoveredResult = f.utils.getWalletItems("--mainnet", "conway")
            val recovered = recoveredResult.items
            assertEquals(f.readState, recoveredResult.readState)
            assertEquals(amount, recovered[0].paymentAddrLovelace)
            assertEquals(mapOf("policy.asset" to amount), recovered[0].nativeAssetMap)
            assertEquals(1L, recovered[0].paymentAddrUtxoCount)
            assertEquals(BigInteger.TEN, recovered[1].paymentAddrLovelace)
            assertEquals(1L, recovered[1].paymentAddrUtxoCount)
            assertEquals(emptyMap<String, BigInteger>(), recovered[1].nativeAssetMap)
            assertEquals(true, recovered[1].stakingAddrRegistered)
            assertEquals(BigInteger.valueOf(13), recovered[1].stakingAddrLovelace)
            verify(exactly = 2) {
                f.pending.snapshot(f.host, f.node, any(), "--mainnet", "--socket-path /node/default/db/socket", "conway",
                    setOf("payment-address", "stake-payment-address"), null)
            }
            verify(exactly = 0) { f.wallets.save(any()) }
            every { f.pending.snapshot(any(), any(), any(), any(), any(), any(), any(), any()) } returns snapshot(
                rewards = mapOf("stake-address" to BigInteger.valueOf(30)),
            )
            assertEquals(BigInteger.ZERO, f.utils.getWalletItems("--mainnet", "conway").items[1].stakingAddrLovelace)
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test
    fun `listing propagates unrelated failures and cancellation`() = runBlocking {
        val f = ListingFixture()
        val failure = IOException("database failed")
        every { f.pending.snapshot(any(), any(), any(), any(), any(), any(), any(), any()) } throws failure
        assertEquals(failure, assertThrows<IOException> { f.utils.getWalletItems("--mainnet", "conway") })
        val cancellation = CancellationException("cancelled")
        every { f.pending.snapshot(any(), any(), any(), any(), any(), any(), any(), any()) } throws cancellation
        assertEquals(cancellation, assertThrows<CancellationException> { f.utils.getWalletItems("--mainnet", "conway") })
    }

    @Test
    fun `empty saved wallet needs no coverage`() = runBlocking {
        val f = ListingFixture()
        every { f.wallets.findAllNotDeleted() } returns emptyList()
        assertEquals(WalletResult(emptyList(), "network", f.readState), f.utils.getWalletItems("--mainnet", "conway"))
        verify { f.pending wasNot Called }
    }

    @Test
    fun `listing never pairs old amounts with a newer generation`() = runBlocking<Unit> {
        val f = ListingFixture()
        mockkConstructor(HostConnection::class)
        try {
            every { f.pending.snapshot(any(), any(), any(), any(), any(), any(), any(), any()) } returns snapshot()
            every { anyConstructed<HostConnection>().commandArgs(any()) } returns "[]"
            every { f.staking.fromJson("[]") } answers {
                f.readState = f.unavailableState.copy(generation = "2")
                emptyList()
            }
            assertEquals(f.unavailable(f.unavailableState.copy(generation = "2")), f.utils.getWalletItems("--mainnet", "conway"))
            every { f.staking.fromJson("[]") } answers {
                f.readState = f.readState.copy(status = "syncing", walletReady = true)
                emptyList()
            }
            assertThrows<IllegalStateException> { f.utils.getWalletItems("--mainnet", "conway") }
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test
    fun `invalid reference script size fails closed`() {
        val host = mockk<Host> { every { cardanoCliPath } returns "cardano-cli" }
        val hostConnection = mockk<HostConnection> {
            every { commandArgs(any()) } returns "Reference inputs scripts size is -1 bytes."
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
                pendingWalletState = mockk(),
            )

        assertThrows<IOException> {
            walletUtils.queryReferenceScriptSize(
                host,
                hostConnection,
                "--mainnet",
                "--socket-path /node.socket",
                "conway",
                listOf(Utxo("hash", 0L, BigInteger.ONE, emptyList())),
                snapshot(),
            )
        }
    }

    @Test
    fun `reference script size combines pending bytes with confirmed query`() {
        val host = mockk<Host> { every { cardanoCliPath } returns "cardano-cli" }
        val connection = mockk<HostConnection> {
            every { commandArgs(any()) } returns "Reference inputs scripts size is 7 bytes."
        }
        val pending = Utxo("pending", 1, BigInteger.ONE, emptyList())
        val confirmed = Utxo("confirmed", 0, BigInteger.ONE, emptyList())
        val state = snapshot(pendingOutputs = mapOf(
            ("pending" to 1) to PendingWalletState.PendingOutput("address", pending, byteArrayOf(0x82.toByte(), 1, 0x43, 1, 2, 3))
        ))
        val f = ListingFixture()
        assertEquals(10L, f.utils.queryReferenceScriptSize(
            host, connection, "--mainnet", "--socket-path /node.socket", "conway", listOf(pending, confirmed), state,
        ))
        verify(exactly = 1) {
            connection.commandArgs(listOf("cardano-cli", "conway", "query", "ref-script-size", "--tx-in", "confirmed#0",
                "--mainnet", "--socket-path", "/node.socket", "--output-text"))
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
                pendingWalletState = mockk(),
            )

        assertEquals(cleartext, walletUtils.getSKeyContent(legacyFile, password))

        val currentEncryptor = AesGcmBytesEncryptor.withPassword(password, WalletUtils.S).build()
        assertEquals(cleartext, Utf8.decode(currentEncryptor.decrypt(Hex.decode(migratedFile.content))))
        assertEquals(cleartext, walletUtils.getSKeyContent(migratedFile, password))
        verify(exactly = 1) { fileRepository.save(any()) }
    }
}
