package com.swiftmako.jormanager.services

import com.swiftmako.jormanager.model.ChainPoint
import com.swiftmako.jormanager.repositories.LedgerDao
import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.DecodedGenesisOutput
import com.swiftmako.jormanager.repositories.LedgerRepository
import com.swiftmako.jormanager.tables.*
import java.math.BigInteger
import java.net.URI
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import liquibase.command.CommandScope
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito.mock
import org.springframework.messaging.simp.SimpMessagingTemplate

/** Opt-in, real PostgreSQL; never run against an application database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GenesisRecoveryIntegrationTest {
    private val url = System.getenv("JORMANAGER_TEST_DATABASE_URL")
    private val databaseName = "jormanager_test_genesis_" + UUID.randomUUID().toString().replace("-", "")
    private var isolatedUrl: String? = null
    private var database: Database? = null
    private val coordinator = ChainIngestionCoordinator(mock(BatchEffectProjector::class.java), mock(SimpMessagingTemplate::class.java), MutableStateFlow(0L))
    private val genesisHash = "11".repeat(32)
    private val funds = listOf(
        DecodedGenesisOutput(ByteArray(32) { 1 }, byteArrayOf(0x60) + ByteArray(28) { 2 }, BigInteger.valueOf(900)),
        DecodedGenesisOutput(ByteArray(32) { 3 }, byteArrayOf(0x60) + ByteArray(28) { 4 }, BigInteger.valueOf(2700)),
    )

    @BeforeAll
    fun createIsolatedDatabase() {
        assumeTrue(!url.isNullOrBlank()) { "Set JORMANAGER_TEST_DATABASE_URL to a disposable local PostgreSQL database" }
        val target = URI(requireNotNull(url).removePrefix("jdbc:"))
        require(target.scheme == "postgresql" && target.host in setOf("127.0.0.1", "localhost") &&
            target.path.matches(Regex("/jormanager_test_[a-zA-Z0-9_]+"))) { "Only a dedicated local jormanager_test_* database is allowed" }
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE $databaseName") }
        }
        val testUrl = "jdbc:" + URI(target.scheme, target.userInfo, target.host, target.port, "/$databaseName", target.query, null)
        isolatedUrl = testUrl
        DriverManager.getConnection(testUrl).use { connection ->
            val migrationDatabase = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
            CommandScope("update")
                .addArgumentValue("database", migrationDatabase)
                .addArgumentValue("changelogFile", "db/liquibase-changelog.xml")
                .execute()
        }
        database = Database.connect({ DriverManager.getConnection(testUrl) })
    }

    @AfterAll
    fun removeIsolatedDatabase() {
        database?.let { TransactionManager.closeAndUnregister(it) }
        if (isolatedUrl != null) DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { it.execute("DROP DATABASE $databaseName WITH (FORCE)") }
        }
    }

    private fun outputs(network: String): List<Pair<String, String>> = transaction {
        LedgerUtxosTable.selectAll().where { LedgerUtxosTable.networkKey eq network }
            .orderBy(LedgerUtxosTable.txId).map { it[LedgerUtxosTable.txId] to it[LedgerUtxosTable.lovelace] }
    }

    private fun block(network: String, slot: Long, complete: Boolean = true, adopted: Boolean = true): Long = transaction {
        ChainInclusionsTable.insert {
            it[ChainInclusionsTable.network] = network; it[hash] = ByteArray(32) { slot.toByte() }
            it[ChainInclusionsTable.slot] = slot.toBigDecimal(); it[height] = slot.toBigDecimal()
            it[previousHash] = null; it[era] = 7; it[header] = byteArrayOf(); it[body] = byteArrayOf()
            it[ChainInclusionsTable.adopted] = adopted; it[ChainInclusionsTable.complete] = complete; it[sourceKind] = "direct"
        }[ChainInclusionsTable.id].value
    }

    @Test
    fun initializationIsIdempotentAndNetworkScoped() {
        coordinator.registerNetwork("fresh", genesisHash, funds)
        coordinator.registerNetwork("other", genesisHash, funds)
        val expected = funds.map { it.txId.toHexString() to it.coin.toString() }
        coordinator.registerNetwork("fresh", genesisHash, funds)
        assertThat(outputs("fresh")).containsExactlyElementsIn(expected).inOrder()
        assertThat(outputs("other")).containsExactlyElementsIn(expected).inOrder()
        assertThrows(IllegalStateException::class.java) { coordinator.registerNetwork("fresh", "22".repeat(32), funds) }
        assertThat(outputs("fresh")).containsExactlyElementsIn(expected).inOrder()
    }

    @Test
    fun recoversEmptyInitializationWithoutResettingBlocksAndNeverResurrectsSpentFunds() {
        val network = "empty-blocks"
        coordinator.registerNetwork(network, genesisHash)
        val first = block(network, 16)
        val second = block(network, 59)
        transaction {
            SyncStatesTable.update({ SyncStatesTable.network eq network }) {
                it[completeInclusion] = second; it[status] = "complete"
            }
        }
        val generation = coordinator.beginSession(network)
        coordinator.registerNetwork(network, genesisHash, funds)
        assertThat(outputs(network)).containsExactlyElementsIn(funds.map { it.txId.toHexString() to it.coin.toString() })
        assertThat(coordinator.status(network).lastCompletePoint!!.slot).isEqualTo("59")
        assertThat(coordinator.generation(network)).isEqualTo(generation)
        transaction {
            assertThat(ChainInclusionsTable.selectAll().where { ChainInclusionsTable.network eq network }
                .map { it[ChainInclusionsTable.id].value }).containsExactly(first, second)
            LedgerRepository.spend(network, second, funds[0].txId, 0)
        }
        coordinator.registerNetwork(network, genesisHash, funds)
        transaction {
            assertThat(LedgerRepository.currentOutput(network, funds[0].txId, 0)).isNull()
            assertThat(LedgerRepository.currentOutput(network, funds[1].txId, 0)!![LedgerUtxosTable.lovelace]).isEqualTo("2700")
            val spent = LedgerUtxosTable.selectAll().where { LedgerUtxosTable.networkKey eq network }
                .single { it[LedgerUtxosTable.txId] == funds[0].txId.toHexString() }
            assertThat(spent[LedgerUtxosTable.spendingInclusionId]).isEqualTo(second)
        }
    }

    @Test
    fun transactionHistoryIncludingRolledBackHistoryBlocksRestoration() {
        for (adopted in listOf(true, false)) {
            val network = "history-$adopted"
            coordinator.registerNetwork(network, genesisHash)
            val inclusion = block(network, 16, complete = adopted, adopted = adopted)
            transaction {
                TransactionBatchesTable.insert {
                    it[TransactionBatchesTable.inclusion] = inclusion; it[sourceKind] = "direct"
                    it[position] = 0; it[applicationOrdinal] = 0; it[valid] = true; it[raw] = byteArrayOf()
                }
                SyncStatesTable.update({ SyncStatesTable.network eq network }) { it[status] = "complete" }
            }
            val generation = coordinator.beginSession(network)
            val failure = assertThrows(IllegalStateException::class.java) { coordinator.registerNetwork(network, genesisHash, funds) }
            coordinator.boundaryFailure(network, requireNotNull(failure.message), generation)
            assertThat(outputs(network)).isEmpty()
            assertThat(coordinator.status(network).status).isEqualTo("incomplete")
            assertThat(coordinator.status(network).reason).contains("controlled index recovery")
        }
    }

    @Test
    fun incompleteBlockHistoryBlocksRestoration() {
        val network = "incomplete-block"
        coordinator.registerNetwork(network, genesisHash)
        block(network, 16, complete = false)
        val failure = assertThrows(IllegalStateException::class.java) { coordinator.registerNetwork(network, genesisHash, funds) }
        assertThat(failure.message).contains("incomplete block history")
        assertThat(outputs(network)).isEmpty()
    }

    @Test
    fun databaseFailureCannotCommitPartialGenesisRestoration() {
        val network = "atomic"
        coordinator.registerNetwork(network, genesisHash)
        assertThrows(SQLException::class.java) { coordinator.registerNetwork(network, genesisHash, listOf(funds[0], funds[0])) }
        assertThat(outputs(network)).isEmpty()
        coordinator.registerNetwork(network, genesisHash, funds)
        assertThat(outputs(network)).containsExactlyElementsIn(funds.map { it.txId.toHexString() to it.coin.toString() })
    }

    @Test
    fun simultaneousRegistrationsCannotDuplicateGenesisFunds() {
        val network = "concurrent"
        coordinator.registerNetwork(network, genesisHash)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val registrations = (1..2).map {
                workers.submit { start.await(); coordinator.registerNetwork(network, genesisHash, funds) }
            }
            start.countDown()
            registrations.forEach { it.get(15, TimeUnit.SECONDS) }
            assertThat(outputs(network)).containsExactlyElementsIn(funds.map { it.txId.toHexString() to it.coin.toString() })
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun committedCoverageEnablesWalletReadsAndFailureRevokesThem() {
        val network = "wallet-coverage"
        coordinator.registerNetwork(network, genesisHash, funds)
        val address = transaction {
            (LedgerUtxosTable innerJoin LedgerTable).selectAll().where { LedgerUtxosTable.networkKey eq network }
                .single { it[LedgerUtxosTable.txId] == funds[0].txId.toHexString() }[LedgerTable.address]
        }
        val wallet = LedgerDao()
        val generation = coordinator.beginSession(network)
        assertThrows(IllegalStateException::class.java) { wallet.queryUtxos(network, address) }
        val complete = block(network, 59)
        transaction {
            SyncStatesTable.update({ SyncStatesTable.network eq network }) { it[completeInclusion] = complete }
        }
        coordinator.observeTip(network, ChainPoint("59", ByteArray(32) { 59 }.toHexString()), generation)
        assertThat(wallet.queryUtxos(network, address).single().lovelace).isEqualTo(funds[0].coin)
        coordinator.boundaryFailure(network, "Requested block unavailable", generation)
        assertThrows(IllegalStateException::class.java) { wallet.queryUtxos(network, address) }
    }
}
