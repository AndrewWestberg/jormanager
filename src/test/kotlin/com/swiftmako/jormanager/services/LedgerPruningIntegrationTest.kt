package com.swiftmako.jormanager.services

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.ChainPoint
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.monitors.LedgerPruneMonitor
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.DecodedGenesisOutput
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.LedgerBlockDecoder
import com.swiftmako.jormanager.nodeclient.protocols.fixtures.CatchupFixtures
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.repositories.LedgerRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.spring.config.Configuration
import com.swiftmako.jormanager.tables.ChainInclusionsTable
import com.swiftmako.jormanager.tables.LedgerAssetsTable
import com.swiftmako.jormanager.tables.LedgerTable
import com.swiftmako.jormanager.tables.LedgerUtxoAssetsTable
import com.swiftmako.jormanager.tables.LedgerUtxosTable
import com.swiftmako.jormanager.tables.SyncStatesTable
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.mockk.every
import io.mockk.mockk
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import liquibase.command.CommandScope
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.messaging.simp.SimpMessagingTemplate
import tools.jackson.databind.json.JsonMapper

/** Opt-in real PostgreSQL verification using a disposable UUID sibling database. */
class LedgerPruningIntegrationTest {
    private val json = JsonMapper.builder().build()
    private val moshi = Configuration().getMoshi()
    private val shelleyGenesisAdapter = moshi.adapter(GenesisShelley::class.java)
    private val url = System.getenv("JORMANAGER_TEST_DATABASE_URL")

    private fun <T> withDatabase(body: (DataSource, ChainIngestionCoordinator) -> T): T {
        assumeTrue(!url.isNullOrBlank(), "Set JORMANAGER_TEST_DATABASE_URL to a disposable local PostgreSQL database")
        val target = URI(requireNotNull(url).removePrefix("jdbc:"))
        require(
            target.scheme == "postgresql" && target.host in setOf("localhost", "127.0.0.1") &&
                target.path.matches(Regex("/jormanager_test_[a-zA-Z0-9_]+"))
        ) { "Only a dedicated local jormanager_test_* database is allowed" }
        val name = "jormanager_test_prune_" + UUID.randomUUID().toString().replace("-", "")
        val sibling = "jdbc:" + URI(target.scheme, target.userInfo, target.host, target.port, "/$name", target.query, null)
        var created = false
        var pool: HikariDataSource? = null
        var db: Database? = null
        try {
            DriverManager.getConnection(url).use { conn ->
                conn.createStatement().use { stmt -> stmt.execute("CREATE DATABASE $name") }
            }
            created = true
            DriverManager.getConnection(sibling).use { conn ->
                val migration = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(conn))
                CommandScope("update")
                    .addArgumentValue("database", migration)
                    .addArgumentValue("changelogFile", "db/liquibase-changelog.xml")
                    .execute()
            }
            val config = HikariConfig().apply {
                jdbcUrl = sibling
                isAutoCommit = false
                connectionTimeout = 120_000L
                maximumPoolSize = 20
                minimumIdle = 2
                maxLifetime = 600_000L
                validationTimeout = 12_000L
                idleTimeout = 12_000L
            }
            pool = HikariDataSource(config)
            db = Database.connect(pool)
            val coordinator = ChainIngestionCoordinator(
                BatchEffectProjector(json),
                mock(SimpMessagingTemplate::class.java),
                Configuration().getRefreshWalletChannel(),
                mockk<LedgerDao>(relaxed = true),
            )
            return body(pool, coordinator)
        } finally {
            db?.let { TransactionManager.closeAndUnregister(it) }
            pool?.close()
            if (created) {
                DriverManager.getConnection(url).use { conn ->
                    conn.createStatement().use { stmt -> stmt.execute("DROP DATABASE $name WITH (FORCE)") }
                }
            }
        }
    }

    private fun createService(
        dataSource: DataSource,
        batchSize: Int = 250,
        vacuumEnabled: Boolean = true,
    ): LedgerPruningService {
        val nodeRepository = mockk<NodeRepository>()
        every { nodeRepository.findAll() } returns emptyList()
        return LedgerPruningService(
            dataSource = dataSource,
            nodeRepository = nodeRepository,
            fileRepository = mockk<FileRepository>(relaxed = true),
            cardanoRepository = mockk<CardanoRepository>(relaxed = true),
            shelleyGenesisAdapter = shelleyGenesisAdapter,
            inactivityMinutes = 30L,
            batchSize = batchSize,
            vacuumEnabled = vacuumEnabled,
        )
    }

    private fun addressBytes(index: Int): ByteArray {
        val bytes = ByteArray(57)
        bytes[0] = 0x00 // testnet base address with stake credential
        for (i in 1..28) {
            bytes[i] = ((index ushr ((i % 4) * 8)) xor i).toByte()
        }
        for (i in 29..56) {
            bytes[i] = (((index + 7) ushr ((i % 4) * 8)) xor i).toByte()
        }
        return bytes
    }

    private fun txHash(seed: Int): ByteArray {
        val bytes = ByteArray(32)
        bytes[0] = (seed ushr 24).toByte()
        bytes[1] = (seed ushr 16).toByte()
        bytes[2] = (seed ushr 8).toByte()
        bytes[3] = seed.toByte()
        for (i in 4 until 32) {
            bytes[i] = (seed * 31 + i).toByte()
        }
        return bytes
    }

    @Test
    fun prunesEligibleSpentUtxosAndAllOrphanedAddressesWhileProtectingRecentAndActiveState() =
        withDatabase { dataSource, coordinator ->
            val network = "testnet:42"
            val genesisHash = "aa".repeat(32)
            val activeAddrBytes = addressBytes(1)
            val oldSpentOnlyAddrBytes = addressBytes(2)
            val boundaryEligibleAddrBytes = addressBytes(3)
            val boundaryProtectedAddrBytes = addressBytes(4)
            val mixedActiveAndOldAddrBytes = addressBytes(5)
            val legacyAddrBytes = addressBytes(6)

            val genesisOutputs = listOf(
                DecodedGenesisOutput(txHash(1), activeAddrBytes, BigInteger.valueOf(10_000_000L)),
                DecodedGenesisOutput(txHash(2), oldSpentOnlyAddrBytes, BigInteger.valueOf(20_000_000L)),
                DecodedGenesisOutput(txHash(3), boundaryEligibleAddrBytes, BigInteger.valueOf(30_000_000L)),
                DecodedGenesisOutput(txHash(4), boundaryProtectedAddrBytes, BigInteger.valueOf(40_000_000L)),
                DecodedGenesisOutput(txHash(5), mixedActiveAndOldAddrBytes, BigInteger.valueOf(50_000_000L)),
                DecodedGenesisOutput(txHash(55), mixedActiveAndOldAddrBytes, BigInteger.valueOf(60_000_000L)),
            )
            coordinator.registerNetwork(network, genesisHash, genesisOutputs)

            val incOld = insertInclusion(network, slot = 200L, height = 10L)
            val incBoundaryEligible = insertInclusion(network, slot = 1_200L, height = 20L)
            val incBoundaryProtected = insertInclusion(network, slot = 1_201L, height = 21L)
            val incTip = insertInclusion(network, slot = 3_000L, height = 100L)

            transaction {
                // Spend oldSpentOnly at slot 200 (2800s ago >= 1800s) and attach an asset link to verify cascade
                val assetId = LedgerAssetsTable
                    .insert {
                        it[policy] = "bb".repeat(28)
                        it[name] = "746f6b656e"
                        it[image] = ""
                        it[description] = null
                    }[LedgerAssetsTable.id]
                    .value
                val oldUtxoId = LedgerUtxosTable
                    .selectAll()
                    .where { LedgerUtxosTable.txId eq txHash(2).toHexString() }
                    .single()[LedgerUtxosTable.id]
                    .value
                LedgerUtxoAssetsTable.insert {
                    it[ledgerUtxoId] = oldUtxoId
                    it[ledgerAssetId] = assetId
                    it[amount] = "100"
                }

                LedgerRepository.spend(
                    LedgerRepository.ProjectionContext(network, incOld, 10.toBigDecimal(), 200.toBigDecimal(), false),
                    txHash(2),
                    0,
                )
                // Spend boundaryEligible at slot 1200 (3000 - 1200 = 1800s == exactly 30m -> eligible)
                LedgerRepository.spend(
                    LedgerRepository.ProjectionContext(network, incBoundaryEligible, 20.toBigDecimal(), 1_200.toBigDecimal(), false),
                    txHash(3),
                    0,
                )
                // Spend boundaryProtected at slot 1201 (3000 - 1201 = 1799s < 30m -> MUST be retained!)
                LedgerRepository.spend(
                    LedgerRepository.ProjectionContext(network, incBoundaryProtected, 21.toBigDecimal(), 1_201.toBigDecimal(), false),
                    txHash(4),
                    0,
                )
                // Spend ONE of mixedActiveAndOldAddr's two UTxOs at slot 200; the second remains unspent
                LedgerRepository.spend(
                    LedgerRepository.ProjectionContext(network, incOld, 10.toBigDecimal(), 200.toBigDecimal(), false),
                    txHash(5),
                    0,
                )

                // Insert 600 pre-existing historical orphaned addresses across multiple batch windows
                repeat(600) { idx ->
                    LedgerTable.insert {
                        it[address] = LedgerRepository.addressText(addressBytes(1_000 + idx))
                        it[stakeAddress] = null
                    }
                }

                // Insert an unassigned legacy spent UTxO (network_key IS NULL) — must remain untouched
                val legacyLedgerId = LedgerTable
                    .insert {
                        it[address] = LedgerRepository.addressText(legacyAddrBytes)
                        it[stakeAddress] = null
                    }[LedgerTable.id]
                    .value
                LedgerUtxosTable.insert {
                    it[ledgerId] = legacyLedgerId
                    it[networkKey] = null
                    it[txId] = txHash(999).toHexString()
                    it[txIx] = 0
                    it[lovelace] = "1234567"
                    it[blockCreated] = BigDecimal.ONE
                    it[slotCreated] = BigDecimal.ONE
                    it[blockSpent] = BigDecimal.TEN
                    it[slotSpent] = BigDecimal.TEN
                    it[spendingInclusionId] = null
                    it[outputOccurrenceId] = null
                }

                SyncStatesTable.update({ SyncStatesTable.network eq network }) {
                    it[completeInclusion] = incTip
                    it[status] = "complete"
                }
            }

            val service = createService(dataSource, batchSize = 150)
            val sweep = service.pruneOnce()

            assertThat(sweep.skipped).isFalse()
            assertThat(sweep.networksEvaluated).isEqualTo(1)
            // txHash(2), txHash(3), and txHash(5) were spent >= 30 minutes ago (3 UTxOs)
            assertThat(sweep.spentUtxosDeleted).isEqualTo(3L)
            // 600 historical orphans + oldSpentOnlyAddr + boundaryEligibleAddr = 602 orphaned addresses purged in 1 sweep!
            assertThat(sweep.orphanedAddressesDeleted).isEqualTo(602L)
            assertThat(sweep.vacuumCompleted).isTrue()

            val activeAddr = LedgerRepository.addressText(activeAddrBytes)
            val oldSpentAddr = LedgerRepository.addressText(oldSpentOnlyAddrBytes)
            val boundaryEligibleAddr = LedgerRepository.addressText(boundaryEligibleAddrBytes)
            val boundaryProtectedAddr = LedgerRepository.addressText(boundaryProtectedAddrBytes)
            val mixedAddr = LedgerRepository.addressText(mixedActiveAndOldAddrBytes)
            val legacyAddr = LedgerRepository.addressText(legacyAddrBytes)

            transaction {
                val remainingAddresses = LedgerTable.selectAll().map { it[LedgerTable.address] }.toSet()
                assertThat(remainingAddresses).containsExactly(
                    activeAddr,
                    boundaryProtectedAddr,
                    mixedAddr,
                    legacyAddr,
                )
                assertThat(remainingAddresses).doesNotContain(oldSpentAddr)
                assertThat(remainingAddresses).doesNotContain(boundaryEligibleAddr)

                // Cascaded asset link for pruned UTxO is gone, while LedgerAssetsTable catalog stays intact
                assertThat(LedgerUtxoAssetsTable.selectAll().count()).isEqualTo(0L)
                assertThat(LedgerAssetsTable.selectAll().count()).isEqualTo(1L)

                // Unassigned legacy row is untouched
                assertThat(LedgerUtxosTable.selectAll().where { LedgerUtxosTable.networkKey.isNull() }.count()).isEqualTo(1L)

                // Short-range rollback of slot 1201 (< 30m old) still restores boundaryProtectedAddr's UTxO!
                LedgerRepository.rollbackProjection(network, incBoundaryProtected)
            }

            val restoredBalances = LedgerRepository.queryUtxos(
                network,
                setOf(activeAddr, boundaryProtectedAddr, mixedAddr, oldSpentAddr),
            )
            assertThat(restoredBalances.getValue(activeAddr).single().lovelace).isEqualTo(BigInteger.valueOf(10_000_000L))
            assertThat(restoredBalances.getValue(boundaryProtectedAddr).single().lovelace).isEqualTo(BigInteger.valueOf(40_000_000L))
            assertThat(restoredBalances.getValue(mixedAddr).single().lovelace).isEqualTo(BigInteger.valueOf(60_000_000L))
            assertThat(restoredBalances[oldSpentAddr]).isNull()
        }

    @Test
    fun reducesPhysicalDatabaseSizeUsesIndexesWithoutSeqScansAndKeepsWalletQueriesUnder200ms() =
        withDatabase { dataSource, coordinator ->
            val network = "mainnet-perf"
            val genesisHash = "cc".repeat(32)
            coordinator.registerNetwork(network, genesisHash)
            val spendInclusion = insertInclusion(network, slot = 500L, height = 50L)
            val tipInclusion = insertInclusion(network, slot = 10_000L, height = 500L)

            val walletAddresses = (1..25).map { idx -> LedgerRepository.addressText(addressBytes(idx)) }.toSet()

            // Bulk-populate 15,000 historical spent addresses/UTxOs + 500 active addresses (including 25 wallet addresses)
            transaction {
                val connection = TransactionManager.current().connection.connection as Connection
                val assetId = LedgerAssetsTable
                    .insert {
                        it[policy] = "dd".repeat(28)
                        it[name] = "636f696e"
                        it[image] = ""
                        it[description] = null
                    }[LedgerAssetsTable.id]
                    .value

                connection
                    .prepareStatement("INSERT INTO ledger (id, address, stake_address) VALUES (?, ?, ?)")
                    .use { ledgerStmt ->
                        connection
                            .prepareStatement(
                                """
                                INSERT INTO ledger_utxos
                                (id, ledger_id, tx_id, tx_ix, lovelace, block_created, slot_created, block_spent, slot_spent, network_key, spending_inclusion_id)
                                VALUES (?, ?, ?, 0, '5000000', 1, 10, ?, ?, ?, ?)
                                """.trimIndent(),
                            ).use { utxoStmt ->
                                connection
                                    .prepareStatement(
                                        "INSERT INTO ledger_utxo_assets (id, ledger_utxo_id, ledger_asset_id, amount) VALUES (?, ?, ?, '42')",
                                    ).use { assetStmt ->
                                        for (i in 1..3_000) {
                                            val id = 100_000L + i
                                            val addr = LedgerRepository.addressText(addressBytes(i))
                                            ledgerStmt.setLong(1, id)
                                            ledgerStmt.setString(2, addr)
                                            ledgerStmt.setString(3, "stake_test1_active_${i % 50}")
                                            ledgerStmt.addBatch()

                                            utxoStmt.setLong(1, id)
                                            utxoStmt.setLong(2, id)
                                            utxoStmt.setString(3, txHash(i).toHexString())
                                            utxoStmt.setBigDecimal(4, null)
                                            utxoStmt.setBigDecimal(5, null)
                                            utxoStmt.setString(6, network)
                                            utxoStmt.setObject(7, null)
                                            utxoStmt.addBatch()

                                            assetStmt.setLong(1, id)
                                            assetStmt.setLong(2, id)
                                            assetStmt.setLong(3, assetId)
                                            assetStmt.addBatch()
                                        }
                                        // Place bloated historical spent rows at higher IDs/pages (just like real append-only bloat!)
                                        for (i in 3_001..18_000) {
                                            val id = 100_000L + i
                                            val addr = "addr_test1_bloat_${i}_${"x".repeat(80)}"
                                            ledgerStmt.setLong(1, id)
                                            ledgerStmt.setString(2, addr)
                                            ledgerStmt.setString(3, "stake_test1_bloat_$i")
                                            ledgerStmt.addBatch()

                                            utxoStmt.setLong(1, id)
                                            utxoStmt.setLong(2, id)
                                            utxoStmt.setString(3, txHash(i).toHexString())
                                            utxoStmt.setBigDecimal(4, BigDecimal.valueOf(50L))
                                            utxoStmt.setBigDecimal(5, BigDecimal.valueOf(100L + (i % 1_000)))
                                            utxoStmt.setString(6, network)
                                            utxoStmt.setLong(7, spendInclusion)
                                            utxoStmt.addBatch()

                                            assetStmt.setLong(1, id)
                                            assetStmt.setLong(2, id)
                                            assetStmt.setLong(3, assetId)
                                            assetStmt.addBatch()
                                        }
                                        ledgerStmt.executeBatch()
                                        utxoStmt.executeBatch()
                                        assetStmt.executeBatch()
                                    }
                            }
                    }

                SyncStatesTable.update({ SyncStatesTable.network eq network }) {
                    it[completeInclusion] = tipInclusion
                    it[status] = "complete"
                }
            }

            val service = createService(dataSource, batchSize = 1_000)
            // Refresh planner statistics before inspecting EXPLAIN plans
            assertThat(service.vacuumLedgerTables()).isTrue()

            // Verify EXPLAIN plans BEFORE pruning (dense spent + dense orphan scenarios) have zero Seq Scans
            val spentPlanDense = explainPlan { conn ->
                conn
                    .prepareStatement(
                        """
                        EXPLAIN (ANALYZE, FORMAT TEXT)
                        SELECT u.id
                        FROM ledger_utxos u
                        WHERE u.slot_spent < ?
                          AND u.network_key = ?
                          AND u.spending_inclusion_id IS NOT NULL
                        ORDER BY u.slot_spent ASC
                        LIMIT ?
                        FOR UPDATE OF u SKIP LOCKED
                        """.trimIndent(),
                    ).apply {
                        setBigDecimal(1, BigDecimal.valueOf(8_201L))
                        setString(2, network)
                        setInt(3, 1_000)
                    }
            }
            assertThat(spentPlanDense).contains("idx_slot_spent")
            assertThat(spentPlanDense).doesNotContain("Seq Scan")

            val sizeBefore = measureStorageBytes(dataSource)
            val result = service.pruneOnce()
            val sizeAfter = measureStorageBytes(dataSource)

            assertThat(result.spentUtxosDeleted).isEqualTo(15_000L)
            assertThat(result.orphanedAddressesDeleted).isEqualTo(15_000L)
            assertThat(result.vacuumCompleted).isTrue()

            // Acceptance Criterion 1: Database and relation sizes strictly decrease after background sweep
            assertThat(sizeAfter.ledgerTablesBytes).isLessThan(sizeBefore.ledgerTablesBytes)
            assertThat(sizeAfter.databaseBytes).isLessThan(sizeBefore.databaseBytes)

            // Verify EXPLAIN plans AFTER pruning (0% spent + 0% orphan steady-state) ALSO have zero Seq Scans
            val windowBoundPlan = explainPlan { conn ->
                conn
                    .prepareStatement(
                        """
                        EXPLAIN (ANALYZE, FORMAT TEXT)
                        SELECT COALESCE(MAX(w.id), ?), COUNT(*)
                        FROM (SELECT id FROM ledger WHERE id > ? ORDER BY id ASC LIMIT ?) w
                        """.trimIndent(),
                    ).apply {
                        setLong(1, Long.MIN_VALUE)
                        setLong(2, Long.MIN_VALUE)
                        setInt(3, 1_000)
                    }
            }
            assertThat(windowBoundPlan).contains("ledger_pkey")
            assertThat(windowBoundPlan).doesNotContain("Seq Scan")

            val orphanAntiJoinPlan = explainPlan { conn ->
                conn
                    .prepareStatement(
                        """
                        EXPLAIN (ANALYZE, FORMAT TEXT)
                        SELECT l.id
                        FROM ledger l
                        WHERE l.id > ? AND l.id <= ?
                          AND NOT EXISTS (
                              SELECT 1 FROM ledger_utxos u
                              WHERE u.ledger_id = l.id AND u.ledger_id > ? AND u.ledger_id <= ?
                          )
                        ORDER BY l.id ASC
                        FOR UPDATE OF l SKIP LOCKED
                        """.trimIndent(),
                    ).apply {
                        setLong(1, 100_000L)
                        setLong(2, 100_500L)
                        setLong(3, 100_000L)
                        setLong(4, 100_500L)
                    }
            }
            assertThat(orphanAntiJoinPlan).contains("ledger_pkey")
            assertThat(orphanAntiJoinPlan).contains("idx_ledger_id")
            assertThat(orphanAntiJoinPlan).doesNotContain("Seq Scan")

            // Acceptance Criterion 3: Wallet balance queries execute in well under 200ms post-cleanup
            // Warm one query to measure steady-state wallet lookup latency, and assert BOTH first & warm < 200ms
            val firstStart = System.nanoTime()
            val firstWalletResult = LedgerRepository.queryUtxos(network, walletAddresses)
            val firstElapsedMs = (System.nanoTime() - firstStart) / 1_000_000L

            val secondStart = System.nanoTime()
            val secondWalletResult = LedgerRepository.queryUtxos(network, walletAddresses)
            val secondElapsedMs = (System.nanoTime() - secondStart) / 1_000_000L

            assertThat(firstWalletResult).hasSize(25)
            assertThat(secondWalletResult).hasSize(25)
            walletAddresses.forEach { addr ->
                val utxo = secondWalletResult.getValue(addr).single()
                assertThat(utxo.lovelace).isEqualTo(BigInteger.valueOf(5_000_000L))
                assertThat(utxo.nativeAssets).hasSize(1)
            }
            assertThat(firstElapsedMs).isLessThan(200L)
            assertThat(secondElapsedMs).isLessThan(200L)
            println(
                "METRICS: ledgerTablesBefore=${sizeBefore.ledgerTablesBytes}B, ledgerTablesAfter=${sizeAfter.ledgerTablesBytes}B, " +
                    "dbBefore=${sizeBefore.databaseBytes}B, dbAfter=${sizeAfter.databaseBytes}B, " +
                    "sweepDuration=${result.durationMillis}ms, walletQueryFirst=${firstElapsedMs}ms, walletQueryWarm=${secondElapsedMs}ms",
            )
        }

    @Test
    fun concurrentBlockAdoptionAndAutomaticBackgroundMonitorCompleteWithoutContentionOrDataLoss() =
        withDatabase { dataSource, coordinator ->
            val network = "concurrent-sync"
            val genesisHash = "ee".repeat(32)
            val seedCount = 20
            val genesisOutputs = (1..seedCount).map { idx ->
                DecodedGenesisOutput(txHash(idx), addressBytes(idx), BigInteger.valueOf(100_000_000L))
            }
            coordinator.registerNetwork(network, genesisHash, genesisOutputs)
            val generation = coordinator.beginSession(network)

            // Seed some historical orphaned addresses to exercise concurrent orphan deletion right away
            transaction {
                repeat(300) { idx ->
                    LedgerTable.insert {
                        it[address] = LedgerRepository.addressText(addressBytes(50_000 + idx))
                        it[stakeAddress] = null
                    }
                }
            }

            val baseService = createService(dataSource, batchSize = 50)
            val completedSweeps = AtomicInteger(0)
            val sweepErrors = CopyOnWriteArrayList<Throwable>()

            val monitor = LedgerPruneMonitor(
                ledgerPruningService = baseService,
                enabled = true,
                initialDelayMs = 5L,
                intervalMs = 15L,
            )

            // Track live unspent UTxO references (txHash, index, addressBytes, lovelace)
            val liveOutputs = ArrayList<Triple<ByteArray, ByteArray, BigInteger>>(seedCount)
            for (idx in 1..seedCount) {
                liveOutputs.add(Triple(txHash(idx), addressBytes(idx), BigInteger.valueOf(100_000_000L)))
            }

            var previousBlockHash: ByteArray? = null
            val blockDurationsMs = ArrayList<Long>(80)

            monitor.start()
            try {
                // Ingest 80 blocks advancing 100 slots per block (8,000 slots total > 1,800-slot cutoff!)
                // Every block spends 4 existing UTxOs and creates 4 new UTxOs across rotating & fresh addresses
                // while LedgerPruneMonitor concurrently sweeps spent UTxOs + orphaned addresses + VACUUM!
                for (blockNum in 1..80) {
                    val slot = blockNum * 100L
                    val txs = (0 until 4).map { offset ->
                        val slotIdx = ((blockNum * 4 + offset) % seedCount)
                        val (prevTxId, _, prevCoin) = liveOutputs[slotIdx]
                        // Alternate between reusing addresses (testing FOR KEY SHARE race safety!) and new addresses
                        val nextAddr = if ((blockNum + offset) % 2 == 0) {
                            addressBytes((slotIdx % 5) + 1)
                        } else {
                            addressBytes(2_000 + blockNum * 10 + offset)
                        }
                        val nextCoin = prevCoin.subtract(BigInteger.valueOf(1_000L))
                        val txBytes = CatchupFixtures.transaction(
                            inputs = listOf(CatchupFixtures.input(prevTxId, 0)),
                            outputs = listOf(
                                CatchupFixtures.output(
                                    address = nextAddr,
                                    coin = nextCoin.toLong(),
                                    assets = listOf(Triple(ByteArray(28) { 0x11 }, byteArrayOf(0x41, 0x42), 10L)),
                                ),
                            ),
                            fee = 1_000L,
                        )
                        val nextTxId = CatchupFixtures.transactionId(txBytes)
                        liveOutputs[slotIdx] = Triple(nextTxId, nextAddr, nextCoin)
                        txBytes
                    }

                    val rawBlock = CatchupFixtures.block(
                        era = 7,
                        height = slot,
                        previous = previousBlockHash,
                        transactions = txs,
                    )
                    val blockHashBytes = CatchupFixtures.blockHash(rawBlock)
                    val blockHashHex = blockHashBytes.toHexString()
                    val decodedBlock = LedgerBlockDecoder.decode(7, rawBlock)
                    val point = ChainPoint(slot.toString(), blockHashHex)
                    previousBlockHash = blockHashBytes

                    val t0 = System.nanoTime()
                    coordinator.observeTip(network, point, generation)
                    coordinator.adopt(network, generation, 7, decodedBlock, point)
                    blockDurationsMs.add((System.nanoTime() - t0) / 1_000_000L)

                    if (blockNum % 10 == 0) {
                        // Allow background monitor ticks to interleave with ongoing ingestion
                        Thread.sleep(20L)
                    }
                }

                // Wait briefly for at least one post-tip background sweep to finish
                Thread.sleep(60L)
            } catch (t: Throwable) {
                sweepErrors.add(t)
                throw t
            } finally {
                val stopped = CountDownLatch(1)
                monitor.stop { stopped.countDown() }
                assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue()
            }

            assertThat(sweepErrors).isEmpty()
            // Run one final deterministic check to verify monitor already swept or finish any tail window
            val finalSweep = baseService.pruneOnce { completedSweeps.incrementAndGet() }
            assertThat(finalSweep.skipped).isFalse()

            // Verify every single live output at chain tip is intact and matches exact expected coin!
            val expectedByAddress = liveOutputs.groupBy(
                keySelector = { LedgerRepository.addressText(it.second) },
                valueTransform = { it.first.toHexString() to it.third },
            )
            val queried = LedgerRepository.queryUtxos(network, expectedByAddress.keys)
            assertThat(queried.keys).containsExactlyElementsIn(expectedByAddress.keys)
            expectedByAddress.forEach { (addr, expectedUtxos) ->
                val actual = queried.getValue(addr).map { it.hash to it.lovelace }
                assertThat(actual).containsExactlyElementsIn(expectedUtxos)
            }

            // Verify historical orphaned addresses and spent UTxOs before slot (8000 - 1800 + 1 = 6201) are all gone!
            transaction {
                val oldSpentRemaining = LedgerUtxosTable
                    .selectAll()
                    .where { LedgerUtxosTable.networkKey eq network }
                    .count { row ->
                        val spent = row[LedgerUtxosTable.slotSpent]
                        spent != null && spent < BigDecimal.valueOf(6_201L)
                    }
                assertThat(oldSpentRemaining).isEqualTo(0)

                val orphanedCount = explainOrphanCount()
                assertThat(orphanedCount).isEqualTo(0L)
            }
        }

    private data class StorageSizes(
        val databaseBytes: Long,
        val ledgerTablesBytes: Long,
    )

    private fun measureStorageBytes(dataSource: DataSource): StorageSizes =
        dataSource.connection.use { conn ->
            conn
                .prepareStatement(
                    """
                    SELECT pg_database_size(current_database()),
                           pg_total_relation_size('ledger') +
                           pg_total_relation_size('ledger_utxos') +
                           pg_total_relation_size('ledger_utxo_assets')
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next())
                        StorageSizes(
                            databaseBytes = rs.getLong(1),
                            ledgerTablesBytes = rs.getLong(2),
                        )
                    }
                }
        }

    private fun explainPlan(statementBuilder: (Connection) -> java.sql.PreparedStatement): String =
        transaction {
            val conn = TransactionManager.current().connection.connection as Connection
            statementBuilder(conn).use { stmt ->
                stmt.executeQuery().use { rs ->
                    buildString {
                        while (rs.next()) {
                            appendLine(rs.getString(1))
                        }
                    }
                }
            }
        }

    private fun explainOrphanCount(): Long {
        val conn = TransactionManager.current().connection.connection as Connection
        return conn
            .prepareStatement(
                "SELECT COUNT(*) FROM ledger l WHERE NOT EXISTS (SELECT 1 FROM ledger_utxos u WHERE u.ledger_id = l.id)",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getLong(1)
                }
            }
    }

    private fun insertInclusion(
        network: String,
        slot: Long,
        height: Long,
    ): Long =
        transaction {
            ChainInclusionsTable
                .insert {
                    it[ChainInclusionsTable.network] = network
                    it[hash] = txHash(slot.toInt())
                    it[ChainInclusionsTable.slot] = slot.toBigDecimal()
                    it[ChainInclusionsTable.height] = height.toBigDecimal()
                    it[previousHash] = null
                    it[era] = 7
                    it[header] = byteArrayOf(1)
                    it[body] = byteArrayOf(2)
                    it[adopted] = true
                    it[complete] = true
                    it[sourceKind] = "direct"
                }[ChainInclusionsTable.id]
                .value
        }
}
