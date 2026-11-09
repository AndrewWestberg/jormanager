package com.swiftmako.jormanager.services

import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.ChainPoint
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.LedgerBlockDecoder
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.LeiosContentResolver
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.nodeclient.protocols.NetworkChainSession
import com.swiftmako.jormanager.nodeclient.protocols.chainsync.ChainSyncProtocol
import com.swiftmako.jormanager.nodeclient.protocols.mux.muxByteBufferPool
import com.swiftmako.jormanager.repositories.ChainRepository
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.repositories.LedgerRepository
import com.swiftmako.jormanager.services.PooltoolService
import com.swiftmako.jormanager.nodeclient.protocols.fixtures.CatchupFixtures
import com.swiftmako.jormanager.utils.Blake2b
import com.swiftmako.jormanager.spring.config.Configuration
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URI
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource
import liquibase.command.CommandScope
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.messaging.simp.SimpMessagingTemplate
import tools.jackson.databind.json.JsonMapper

/** Uses only a UUID-named sibling of an explicitly guarded local test database. */
class ChainCatchupIntegrationTest {
    private val json = JsonMapper.builder().build()
    private val url = System.getenv("JORMANAGER_TEST_DATABASE_URL")

    private fun <T> database(body: (DataSource, ChainIngestionCoordinator, Counters) -> T): T {
        assumeTrue(!url.isNullOrBlank(), "Set JORMANAGER_TEST_DATABASE_URL to a disposable local PostgreSQL database")
        val target = URI(requireNotNull(url).removePrefix("jdbc:"))
        require(target.scheme == "postgresql" && target.host in setOf("localhost", "127.0.0.1") &&
            target.path.matches(Regex("/jormanager_test_[a-zA-Z0-9_]+"))) { "Only a dedicated local jormanager_test_* database is allowed" }
        val name = "jormanager_test_catchup_" + UUID.randomUUID().toString().replace("-", "")
        val sibling = "jdbc:" + URI(target.scheme, target.userInfo, target.host, target.port, "/$name", target.query, null)
        var created = false
        var pool: HikariDataSource? = null
        var db: Database? = null
        try {
            DriverManager.getConnection(url).use { it.createStatement().use { statement -> statement.execute("CREATE DATABASE $name") } }
            created = true
            DriverManager.getConnection(sibling).use { connection ->
                val migration = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
                CommandScope("update").addArgumentValue("database", migration)
                    .addArgumentValue("changelogFile", "db/liquibase-changelog.xml").execute()
            }
            val config = HikariConfig().apply {
                jdbcUrl = sibling
                isAutoCommit = false
                connectionTimeout = 120_000L
                maximumPoolSize = 30
                minimumIdle = 5
                maxLifetime = 600_000L
                validationTimeout = 12_000L
                idleTimeout = 12_000L
                leakDetectionThreshold = 120_000L
            }
            pool = HikariDataSource(config)
            val counts = Counters()
            val source = counts.wrap(pool)
            db = Database.connect(source)
            val coordinator = ChainIngestionCoordinator(BatchEffectProjector(json), mock(SimpMessagingTemplate::class.java), Configuration().getRefreshWalletChannel(), mockk<LedgerDao>(relaxed = true))
            return body(source, coordinator, counts)
        } finally {
            db?.let { TransactionManager.closeAndUnregister(it) }
            pool?.close()
            if (created) DriverManager.getConnection(url).use { it.createStatement().use { statement -> statement.execute("DROP DATABASE $name WITH (FORCE)") } }
        }
    }

    private class Counters {
        val executions = AtomicLong()
        val batches = AtomicLong()
        val batchRows = AtomicLong()
        fun reset() { executions.set(0); batches.set(0); batchRows.set(0) }
        fun wrap(source: DataSource): DataSource = proxy(DataSource::class.java, source) { result ->
            if (result is Connection) connection(result) else result
        }
        private fun connection(connection: Connection): Connection = proxy(Connection::class.java, connection) { result ->
            when (result) {
                is PreparedStatement -> statement(PreparedStatement::class.java, result)
                is Statement -> statement(Statement::class.java, result)
                else -> result
            }
        }
        private fun <T : Statement> statement(type: Class<T>, statement: T): T {
            var pending = 0L
            return proxy(type, statement, before = { method ->
                when (method) {
                    "addBatch" -> pending++
                    "clearBatch" -> pending = 0
                    "executeBatch", "executeLargeBatch" -> {
                        executions.incrementAndGet(); batches.incrementAndGet(); batchRows.addAndGet(pending); pending = 0
                    }
                    "execute", "executeQuery", "executeUpdate", "executeLargeUpdate" -> executions.incrementAndGet()
                }
            }) { it }
        }
        private fun <T> proxy(type: Class<T>, delegate: T, before: (String) -> Unit = {}, transform: (Any?) -> Any?): T {
            val handler = java.lang.reflect.InvocationHandler { _, method, args ->
                before(method.name)
                try { transform(method.invoke(delegate, *(args ?: emptyArray()))) }
                catch (e: InvocationTargetException) { throw e.targetException }
            }
            return type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler))
        }
    }

    private fun point(block: com.swiftmako.jormanager.model.DecodedLedgerBlock): ChainPoint =
        ChainPoint(requireNotNull(block.headerDetails).slot.toString(), block.header.hash256().toHexString())

    private fun closure(replay: CatchupFixtures.Replay): LeiosContentResolver.Closure? {
        val endorsement = replay.endorsement ?: return null
        return LeiosContentResolver.resolve(Blake2b.hash256(endorsement), endorsement.size.toLong(), endorsement,
            replay.transactions.associateBy { Blake2b.hash256(it).toHexString() })
    }

    private fun prepare(coordinator: ChainIngestionCoordinator, replay: CatchupFixtures.Replay): Long {
        coordinator.registerNetwork(replay.network, CatchupFixtures.genesisHash, replay.seeds)
        return coordinator.beginSession(replay.network)
    }

    private fun replay(coordinator: ChainIngestionCoordinator, fixture: CatchupFixtures.Replay, generation: Long) {
        var resolved: LeiosContentResolver.Closure? = null
        fixture.blocks.forEach { bytes ->
            val block = LedgerBlockDecoder.decode(fixture.era, bytes)
            if (block.leiosCertificate != null) resolved = closure(fixture)
            coordinator.adopt(fixture.network, generation, fixture.era, block, point(block), if (block.leiosCertificate != null) resolved else null)
        }
    }

    private fun query(source: DataSource, sql: String, network: String): List<List<String?>> = source.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, network)
            statement.executeQuery().use { rows ->
                val result = mutableListOf<List<String?>>()
                while (rows.next()) result.add((1..rows.metaData.columnCount).map { rows.getString(it) })
                result
            }
        }
    }

    // All relations are identified by their immutable chain/transaction keys, never generated IDs.
    private fun semantic(source: DataSource, network: String): String {
        val queries = listOf(
            "SELECT encode(header_hash,'hex'),slot::text,height::text,encode(previous_hash,'hex'),era::text,adopted::text,complete::text,encode(raw_header,'hex'),encode(raw_body,'hex') FROM chain_inclusions WHERE network_key=? ORDER BY slot,header_hash",
            "SELECT encode(c.header_hash,'hex'),b.source,b.source_position::text,b.application_ordinal::text,b.valid::text,encode(b.raw_included,'hex'),encode(b.full_hash,'hex'),encode(m.tx_id,'hex'),encode(p.tx_id,'hex'),m.child_position::text,m.processing_ordinal::text,encode(m.raw_body,'hex'),encode(m.raw_witnesses,'hex'),encode(m.raw_auxiliary,'hex'),m.auxiliary_presence,m.fee::text,m.effective::text FROM transaction_batches b JOIN chain_inclusions c ON c.id=b.inclusion_id JOIN transaction_members m ON m.batch_id=b.id LEFT JOIN transaction_members p ON p.id=m.parent_id WHERE c.network_key=? ORDER BY c.slot,b.application_ordinal,m.processing_ordinal",
            "SELECT encode(c.header_hash,'hex'),b.source,b.source_position::text,encode(m.tx_id,'hex'),i.role,i.position::text,encode(i.tx_id,'hex'),i.output_index::text,encode(rm.tx_id,'hex'),r.output_index::text,i.effective::text FROM input_occurrences i JOIN transaction_members m ON m.id=i.member_id JOIN transaction_batches b ON b.id=m.batch_id JOIN chain_inclusions c ON c.id=b.inclusion_id LEFT JOIN output_occurrences r ON r.id=i.resolved_output_id LEFT JOIN transaction_members rm ON rm.id=r.member_id WHERE c.network_key=? ORDER BY c.slot,b.application_ordinal,m.processing_ordinal,i.role,i.position",
            "SELECT encode(c.header_hash,'hex'),b.source,b.source_position::text,encode(m.tx_id,'hex'),o.output_index::text,encode(o.raw_output,'hex'),encode(o.address,'hex'),o.coin::text,o.collateral_return::text,o.effective::text FROM output_occurrences o JOIN transaction_members m ON m.id=o.member_id JOIN transaction_batches b ON b.id=m.batch_id JOIN chain_inclusions c ON c.id=b.inclusion_id WHERE c.network_key=? ORDER BY c.slot,b.application_ordinal,m.processing_ordinal,o.output_index",
            "SELECT encode(c.header_hash,'hex'),b.source,b.source_position::text,encode(m.tx_id,'hex'),d.family,d.position::text,encode(d.raw_data,'hex'),d.typed_data,d.effective::text FROM member_data d JOIN transaction_members m ON m.id=d.member_id JOIN transaction_batches b ON b.id=m.batch_id JOIN chain_inclusions c ON c.id=b.inclusion_id WHERE c.network_key=? ORDER BY c.slot,b.application_ordinal,m.processing_ordinal,d.family,d.position",
            "SELECT encode(c.header_hash,'hex'),r.position::text,encode(r.full_hash,'hex'),r.byte_size::text,b.source,b.source_position::text,encode(a.header_hash,'hex') FROM leios_content_references r JOIN leios_certifications cert ON cert.id=r.certification_id JOIN chain_inclusions c ON c.id=cert.inclusion_id LEFT JOIN transaction_batches b ON b.id=r.batch_id LEFT JOIN chain_inclusions a ON a.network_key=c.network_key AND a.header_hash=c.previous_hash WHERE c.network_key=? ORDER BY c.slot,r.position",
            "SELECT tx_id,tx_ix::text,lovelace,block_created::text,slot_created::text,block_spent::text,slot_spent::text FROM ledger_utxos WHERE network_key=? ORDER BY tx_id,tx_ix"
        )
        val data = queries.map { query(source, it, network) }
        return java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(data)).toHexString()
    }

    private fun assertExpected(source: DataSource, fixture: CatchupFixtures.Replay) {
        val expected = if (fixture.endorsement != null) fixture.transactions.size else fixture.blocks.sumOf { LedgerBlockDecoder.decode(fixture.era, it).transactions.size }
        fun count(table: String, join: String): Long = query(source, "SELECT count(*)::text FROM $table $join WHERE c.network_key=?", fixture.network).single().single()!!.toLong()
        val chain = "JOIN chain_inclusions c ON c.id=b.inclusion_id"
        assertEquals(expected.toLong(), count("transaction_batches b", chain))
        assertEquals(expected.toLong(), count("transaction_members m", "JOIN transaction_batches b ON b.id=m.batch_id $chain"))
        assertEquals(expected.toLong(), count("input_occurrences i", "JOIN transaction_members m ON m.id=i.member_id JOIN transaction_batches b ON b.id=m.batch_id $chain"))
        assertEquals(expected.toLong(), count("output_occurrences o", "JOIN transaction_members m ON m.id=o.member_id JOIN transaction_batches b ON b.id=m.batch_id $chain"))
        assertEquals(expected * 2L, count("member_data d", "JOIN transaction_members m ON m.id=d.member_id JOIN transaction_batches b ON b.id=m.batch_id $chain"))
        val live = query(source, "SELECT tx_id,tx_ix::text,lovelace FROM ledger_utxos WHERE network_key=? AND block_spent IS NULL ORDER BY tx_id,tx_ix", fixture.network)
        assertEquals(expected, live.size)
        assertTrue(live.all { it[1] == "0" && it[2] == "9800000" })
        assertEquals(expected.toLong() * 9_800_000L, live.sumOf { it[2]!!.toLong() })
        val members = if (fixture.endorsement != null) requireNotNull(closure(fixture)).entries.map { it.batch.parent }
            else fixture.blocks.flatMap { LedgerBlockDecoder.decode(fixture.era, it).transactions.map { batch -> batch.parent } }
        val expectedRows = members.sortedBy { it.bodyId.toHexString() }.map { member ->
            val output = member.outputs.single()
            listOf(member.bodyId.toHexString(), member.inputs.single().transactionId.toHexString(), "0",
                output.raw.bytes().toHexString(), output.address.toHexString(), output.coin.toString())
        }
        val actualRows = query(source, "SELECT encode(m.tx_id,'hex'),encode(i.tx_id,'hex'),i.output_index::text,encode(o.raw_output,'hex'),encode(o.address,'hex'),o.coin::text FROM transaction_members m JOIN transaction_batches b ON b.id=m.batch_id JOIN chain_inclusions c ON c.id=b.inclusion_id JOIN input_occurrences i ON i.member_id=m.id JOIN output_occurrences o ON o.member_id=m.id WHERE c.network_key=? ORDER BY m.tx_id", fixture.network)
        assertEquals(expectedRows, actualRows)
        assertEquals(members.sortedBy { it.bodyId.toHexString() }.map {
            listOf(it.bodyId.toHexString(), "0", it.outputs.single().coin.toString())
        }, live)
        assertEquals(fixture.seeds.size.toLong(), query(source,
            "SELECT count(*)::text FROM ledger_utxos WHERE network_key=? AND output_occurrence_id IS NULL AND spending_inclusion_id IS NOT NULL",
            fixture.network).single().single()!!.toLong())
        val projector = BatchEffectProjector(json)
        val expectedData = members.flatMap { member ->
            listOf(3 to "validityEnd", 8 to "validityStart").map { (key, family) ->
                val raw = member.fields.getValue(key)
                listOf(member.bodyId.toHexString(), family, raw.bytes().toHexString(), json.writeValueAsString(projector.cborValue(raw)))
            }
        }.sortedWith(compareBy({ it[0] }, { it[1] }))
        val actualData = query(source, "SELECT encode(m.tx_id,'hex'),d.family,encode(d.raw_data,'hex'),d.typed_data FROM member_data d JOIN transaction_members m ON m.id=d.member_id JOIN transaction_batches b ON b.id=m.batch_id JOIN chain_inclusions c ON c.id=b.inclusion_id WHERE c.network_key=? ORDER BY m.tx_id,d.family", fixture.network)
        assertEquals(expectedData, actualData)
        if (fixture.endorsement != null) {
            assertEquals(listOf(listOf("1")), query(source,
                "SELECT count(*)::text FROM leios_certifications cert JOIN chain_inclusions c ON c.id=cert.inclusion_id JOIN chain_inclusions p ON p.network_key=c.network_key AND p.header_hash=c.previous_hash JOIN leios_announcements a ON a.inclusion_id=p.id AND a.eb_hash=cert.announcement_hash WHERE c.network_key=? AND cert.complete",
                fixture.network))
            val references = query(source, "SELECT r.position::text,encode(r.full_hash,'hex'),r.byte_size::text,b.source,b.source_position::text,encode(c.previous_hash,'hex') FROM leios_content_references r JOIN leios_certifications cert ON cert.id=r.certification_id JOIN chain_inclusions c ON c.id=cert.inclusion_id JOIN transaction_batches b ON b.id=r.batch_id WHERE c.network_key=? ORDER BY r.position", fixture.network)
            val expectedReferences = fixture.transactions.mapIndexed { index, bytes ->
                listOf(index.toString(), Blake2b.hash256(bytes).toHexString(), bytes.size.toString(), "endorsed", index.toString(), CatchupFixtures.blockHash(fixture.blocks.first()).toHexString())
            }
            assertEquals(expectedReferences, references)
        }
    }

    private data class Sample(val milliseconds: Double, val transactionsPerSecond: Double, val executions: Long, val batches: Long, val batchRows: Long, val semanticDigest: String)
    private fun sample(fixture: CatchupFixtures.Replay): Sample = database { source, coordinator, counts ->
        val generation = prepare(coordinator, fixture)
        val expectedTip = point(LedgerBlockDecoder.decode(fixture.era, fixture.blocks.last()))
        coordinator.observeTip(fixture.network, expectedTip, generation)
        counts.reset()
        val start = System.nanoTime()
        replay(coordinator, fixture, generation)
        val elapsed = (System.nanoTime() - start) / 1_000_000.0
        val executions = counts.executions.get(); val batches = counts.batches.get(); val rows = counts.batchRows.get()
        assertExpected(source, fixture)
        assertEquals(expectedTip, coordinator.status(fixture.network).lastCompletePoint)
        assertEquals("complete", coordinator.status(fixture.network).status)
        val transactions = if (fixture.endorsement != null) fixture.transactions.size else fixture.blocks.sumOf { LedgerBlockDecoder.decode(fixture.era, it).transactions.size }
        Sample(elapsed, transactions * 1000.0 / elapsed, executions, batches, rows, semantic(source, fixture.network))
    }

    private fun tipWrites(): Long = database { _, coordinator, counts ->
        com.muquit.libsodiumjna.SodiumLibrary.setLibraryPath("/usr/local/lib/libsodium.so")
        val fixture = CatchupFixtures.empty()
        val generation = prepare(coordinator, fixture)
        val session = NetworkChainSession(fixture.network, coordinator, false, generation, 21600)
        val sync = ChainSyncProtocol(mock(Host::class.java), ByteArray(32), emptyList(),
            mock(ChainRepository::class.java), false, mock(PooltoolService::class.java), "", "", session)
        runBlocking {
            fun receive(bytes: ByteArray) = sync.receiveData(ByteBuffer.wrap(bytes))
            suspend fun send() { muxByteBufferPool.recycle(sync.sendData()) }
            try {
                val tip = fixture.blocks.last()
                val tipHash = CatchupFixtures.blockHash(tip)
                send()
                receive(CatchupFixtures.intersectFound(CatchupFixtures.array(), CatchupFixtures.tip(100, tipHash, 100)))
                send()
                counts.reset()
                fixture.blocks.forEach { block ->
                    receive(CatchupFixtures.rollForward(6, block, 100, tipHash, 100))
                    send()
                    session.next()
                    session.release()
                }
                counts.executions.get()
            } finally {
                sync.shutdown()
                session.release()
                session.close()
            }
        }
    }


    @Test
    fun replayBenchmark() {
        val mode = System.getenv("JORMANAGER_CATCHUP_BENCHMARK")
        assumeTrue(mode == "baseline" || mode == "optimized", "Set JORMANAGER_CATCHUP_BENCHMARK to baseline or optimized")
        val fixtures = linkedMapOf("leios" to CatchupFixtures.leios(), "conway" to CatchupFixtures.conway(), "empty" to CatchupFixtures.empty())
        val workloads = fixtures.mapValues { (_, fixture) ->
            sample(fixture)
            val runs = List(5) { sample(fixture) }
            assertEquals(1, runs.map { it.semanticDigest }.distinct().size)
            runs
        }
        val revision = ProcessBuilder("git", "rev-parse", "HEAD").start().let { process -> process.inputStream.bufferedReader().readText().trim().also { check(process.waitFor() == 0) } }
        val postgres = database { source, _, _ -> source.connection.use { it.metaData.databaseProductVersion } }
        val tipExecutions = tipWrites()
        val report = mapOf("fixtureDigest" to CatchupFixtures.digest(), "revision" to revision,
            "jvm" to System.getProperty("java.runtime.version"), "postgresql" to postgres,
            "pool" to mapOf("maximumPoolSize" to 30, "minimumIdle" to 5, "autoCommit" to false), "workloads" to workloads,
            "tipWrites" to mapOf("responses" to 100, "executions" to tipExecutions))
        if (mode == "optimized") {
            val baselinePath = Path.of("/tmp/jormanager-transaction-sql-baseline.json")
            assertTrue(Files.isRegularFile(baselinePath), "Baseline report is required")
            val baseline = json.readTree(Files.readString(baselinePath))
            assertEquals(CatchupFixtures.digest(), baseline["fixtureDigest"].asString())
            assertEquals(json.writeValueAsString(report["pool"]), json.writeValueAsString(baseline["pool"]))
            assertEquals(System.getProperty("java.runtime.version"), baseline["jvm"].asString())
            assertEquals(postgres, baseline["postgresql"].asString())
            assertEquals(100, baseline["tipWrites"]["responses"].asInt())
            workloads.forEach { (name, runs) ->
                val prior = baseline["workloads"][name]
                assertEquals(5, prior.size())
                assertEquals(runs.first().semanticDigest, prior[0]["semanticDigest"].asString())
            }
            Files.writeString(Path.of("/tmp/jormanager-transaction-sql-optimized.json"), json.writeValueAsString(report))
            assertTrue(tipExecutions <= baseline["tipWrites"]["executions"].asLong(), "Repeated-tip JDBC execution regression")
            workloads.forEach { (name, runs) ->
                val prior = baseline["workloads"][name]
                assertEquals(5, prior.size())
                assertEquals(runs.first().semanticDigest, prior[0]["semanticDigest"].asString())
                val before = (0 until 5).map { prior[it]["milliseconds"].asDouble() }.sorted()
                val after = runs.map { it.milliseconds }.sorted()
                assertTrue(before.last() - before.first() <= before[2] * 0.2, "$name baseline timing inconclusive")
                assertTrue(after.last() - after.first() <= after[2] * 0.2, "$name optimized timing inconclusive")
                assertTrue(after[2] <= before[2] * if (name == "leios") 0.8 else 1.1, "$name timing gate failed")
                if (name == "leios" || name == "conway") {
                    val old = (0 until 5).map { prior[it]["executions"].asLong() }.sorted()[2]
                    val current = runs.map { it.executions }.sorted()[2]
                    assertTrue(current <= old * 0.65, "JDBC execution gate failed")
                }
            }
        }
        if (mode == "baseline") Files.writeString(Path.of("/tmp/jormanager-transaction-sql-baseline.json"), json.writeValueAsString(report))
    }

    @Test
    fun duplicateRollbackAndReadoptionPreserveIdentities() = database { source, coordinator, _ ->
        val fixture = CatchupFixtures.leios(8)
        val generation = prepare(coordinator, fixture)
        replay(coordinator, fixture, generation)
        assertExpected(source, fixture)
        val original = semantic(source, fixture.network)
        fun identities() = query(source,
            "SELECT b.id::text,m.id::text,i.id::text,o.id::text,d.id::text,r.id::text FROM transaction_batches b JOIN chain_inclusions c ON c.id=b.inclusion_id JOIN transaction_members m ON m.batch_id=b.id JOIN input_occurrences i ON i.member_id=m.id JOIN output_occurrences o ON o.member_id=m.id JOIN member_data d ON d.member_id=m.id JOIN leios_content_references r ON r.batch_id=b.id WHERE c.network_key=? ORDER BY b.source_position,d.family",
            fixture.network)
        val retainedIds = identities()
        val last = LedgerBlockDecoder.decode(fixture.era, fixture.blocks.last())
        coordinator.adopt(fixture.network, generation, fixture.era, last, point(last), closure(fixture))
        assertEquals(original, semantic(source, fixture.network))
        val predecessor = point(LedgerBlockDecoder.decode(fixture.era, fixture.blocks.first()))
        val next = coordinator.rollback(fixture.network, predecessor, generation)
        assertEquals(generation + 1, next)
        assertEquals(retainedIds, identities())
        assertEquals(fixture.seeds.map { listOf(it.txId.toHexString(), it.coin.toString()) }.sortedBy { it[0] },
            query(source, "SELECT tx_id,lovelace FROM ledger_utxos WHERE network_key=? AND spending_inclusion_id IS NULL ORDER BY tx_id", fixture.network))
        val rolledBack = semantic(source, fixture.network)
        assertThrows(IllegalStateException::class.java) { coordinator.adopt(fixture.network, generation, fixture.era, last, point(last), closure(fixture)) }
        assertEquals(rolledBack, semantic(source, fixture.network))
        coordinator.adopt(fixture.network, next, fixture.era, last, point(last), closure(fixture))
        assertEquals(original, semantic(source, fixture.network))
        assertEquals(retainedIds, identities())
    }

    @Test
    fun bufferedHistoryPreservesValuesAndIdentitiesAcrossRollback() = database { source, coordinator, _ ->
        val seed = CatchupFixtures.seed(1)
        val transactions = mutableListOf<ByteArray>()
        var input = seed.txId
        repeat(259) {
            val tx = CatchupFixtures.transaction(listOf(CatchupFixtures.input(input)),
                outputs = listOf(CatchupFixtures.output(coin = 10_000_000)), fee = 0)
            transactions += tx
            input = CatchupFixtures.transactionId(tx)
        }
        val first = CatchupFixtures.block(7, 1)
        val second = CatchupFixtures.block(7, 2, previous = CatchupFixtures.blockHash(first), transactions = transactions)
        val fixture = CatchupFixtures.Replay("buffered-history", 7, listOf(first, second), listOf(seed))
        val generation = prepare(coordinator, fixture)
        replay(coordinator, fixture, generation)
        val members = LedgerBlockDecoder.decode(7, second).transactions.map { it.parent }
        fun inputs() = query(source, "SELECT b.source_position::text,encode(m.tx_id,'hex'),i.role,i.position::text,encode(i.tx_id,'hex'),i.output_index::text,encode(rm.tx_id,'hex'),r.output_index::text,i.effective::text FROM input_occurrences i JOIN transaction_members m ON m.id=i.member_id JOIN transaction_batches b ON b.id=m.batch_id JOIN chain_inclusions c ON c.id=b.inclusion_id LEFT JOIN output_occurrences r ON r.id=i.resolved_output_id LEFT JOIN transaction_members rm ON rm.id=r.member_id WHERE c.network_key=? ORDER BY b.source_position", fixture.network)
        val expectedInputs = members.mapIndexed { index, member ->
            val previous = if (index == 0) null else members[index - 1].bodyId.toHexString()
            listOf(index.toString(), member.bodyId.toHexString(), "spending", "0",
                member.inputs.single().transactionId.toHexString(), "0", previous, if (previous == null) null else "0", "true")
        }
        fun data() = query(source, "SELECT b.source_position::text,encode(m.tx_id,'hex'),d.family,d.position::text,encode(d.raw_data,'hex'),d.typed_data,d.effective::text FROM member_data d JOIN transaction_members m ON m.id=d.member_id JOIN transaction_batches b ON b.id=m.batch_id JOIN chain_inclusions c ON c.id=b.inclusion_id WHERE c.network_key=? ORDER BY b.source_position,d.family", fixture.network)
        val projector = BatchEffectProjector(json)
        val expectedData = members.flatMapIndexed { index, member ->
            listOf(3 to "validityEnd", 8 to "validityStart").map { (key, family) ->
                val raw = member.fields.getValue(key)
                listOf(index.toString(), member.bodyId.toHexString(), family, "0",
                    raw.bytes().toHexString(), json.writeValueAsString(projector.cborValue(raw)), "true")
            }
        }
        fun identities() = query(source, "SELECT b.id::text,m.id::text,i.id::text,o.id::text,d.id::text FROM transaction_batches b JOIN chain_inclusions c ON c.id=b.inclusion_id JOIN transaction_members m ON m.batch_id=b.id JOIN input_occurrences i ON i.member_id=m.id JOIN output_occurrences o ON o.member_id=m.id JOIN member_data d ON d.member_id=m.id WHERE c.network_key=? ORDER BY b.source_position,d.family", fixture.network)
        assertEquals(expectedInputs, inputs())
        assertEquals(expectedData, data())
        assertEquals(listOf(listOf(members.last().bodyId.toHexString(), "0", "10000000")),
            query(source, "SELECT tx_id,tx_ix::text,lovelace FROM ledger_utxos WHERE network_key=? AND block_spent IS NULL", fixture.network))
        val original = semantic(source, fixture.network)
        val ids = identities()
        val next = coordinator.rollback(fixture.network, point(LedgerBlockDecoder.decode(7, first)), generation)
        assertEquals(ids, identities())
        val decoded = LedgerBlockDecoder.decode(7, second)
        coordinator.adopt(fixture.network, next, 7, decoded, point(decoded))
        assertEquals(expectedInputs, inputs())
        assertEquals(expectedData, data())
        assertEquals(original, semantic(source, fixture.network))
        assertEquals(ids, identities())
    }

    @Test
    fun missingLateEndorsedInputRollsBackEntireBlock() = database { source, coordinator, _ ->
        val fixture = CatchupFixtures.leios()
        val resolved = requireNotNull(closure(fixture))
        val missing = resolved.entries.last().batch.parent.inputs.single().transactionId
        val incomplete = fixture.copy(seeds = fixture.seeds.filterNot { it.txId.contentEquals(missing) })
        val generation = prepare(coordinator, incomplete)
        val first = LedgerBlockDecoder.decode(7, fixture.blocks.first())
        val second = LedgerBlockDecoder.decode(7, fixture.blocks.last())
        coordinator.adopt(fixture.network, generation, 7, first, point(first))
        val before = semantic(source, fixture.network)
        assertThrows(IllegalStateException::class.java) {
            coordinator.adopt(fixture.network, generation, 7, second, point(second), resolved)
        }
        assertEquals(before, semantic(source, fixture.network))
        assertEquals(point(first), coordinator.status(fixture.network).lastCompletePoint)
        assertEquals(0L, query(source, "SELECT count(*)::text FROM leios_certifications cert JOIN chain_inclusions c ON c.id=cert.inclusion_id WHERE c.network_key=?", fixture.network).single().single()!!.toLong())
        val retryTx = CatchupFixtures.transaction(listOf(CatchupFixtures.input(incomplete.seeds.first().txId)))
        val retryBytes = CatchupFixtures.block(7, 2, previous = CatchupFixtures.blockHash(fixture.blocks.first()), transactions = listOf(retryTx))
        val retry = LedgerBlockDecoder.decode(7, retryBytes)
        coordinator.adopt(fixture.network, generation, 7, retry, point(retry))
        val nextTx = CatchupFixtures.transaction(listOf(CatchupFixtures.input(CatchupFixtures.transactionId(retryTx))),
            outputs = listOf(CatchupFixtures.output(coin = 9_600_000)))
        val next = LedgerBlockDecoder.decode(7, CatchupFixtures.block(7, 3,
            previous = CatchupFixtures.blockHash(retryBytes), transactions = listOf(nextTx)))
        coordinator.adopt(fixture.network, generation, 7, next, point(next))
        val retryId = CatchupFixtures.transactionId(retryTx).toHexString()
        val nextId = CatchupFixtures.transactionId(nextTx).toHexString()
        assertEquals(listOf(
            listOf("2", retryId, "spending", "0", incomplete.seeds.first().txId.toHexString(), "0", null, "true"),
            listOf("3", nextId, "spending", "0", retryId, "0", retryId, "true")),
            query(source, "SELECT c.height::text,encode(m.tx_id,'hex'),i.role,i.position::text,encode(i.tx_id,'hex'),i.output_index::text,encode(rm.tx_id,'hex'),i.effective::text FROM input_occurrences i JOIN transaction_members m ON m.id=i.member_id JOIN transaction_batches b ON b.id=m.batch_id JOIN chain_inclusions c ON c.id=b.inclusion_id LEFT JOIN output_occurrences r ON r.id=i.resolved_output_id LEFT JOIN transaction_members rm ON rm.id=r.member_id WHERE c.network_key=? ORDER BY c.height", fixture.network))
        val projector = BatchEffectProjector(json)
        val expectedData = listOf(retry, next).flatMap { block ->
            val member = block.transactions.single().parent
            listOf(3 to "validityEnd", 8 to "validityStart").map { (key, family) ->
                val raw = member.fields.getValue(key)
                listOf(member.bodyId.toHexString(), family, "0", raw.bytes().toHexString(),
                    json.writeValueAsString(projector.cborValue(raw)), "true")
            }
        }
        assertEquals(expectedData, query(source, "SELECT encode(m.tx_id,'hex'),d.family,d.position::text,encode(d.raw_data,'hex'),d.typed_data,d.effective::text FROM member_data d JOIN transaction_members m ON m.id=d.member_id JOIN transaction_batches b ON b.id=m.batch_id JOIN chain_inclusions c ON c.id=b.inclusion_id WHERE c.network_key=? ORDER BY c.height,d.family", fixture.network))
        assertEquals(point(next), coordinator.status(fixture.network).lastCompletePoint)
    }

    @Test
    fun readinessRequiresObservedCompleteTip() = database { source, _, _ ->
        val refresh = Configuration().getRefreshWalletChannel()
        val coordinator = ChainIngestionCoordinator(BatchEffectProjector(json), mock(SimpMessagingTemplate::class.java), refresh, mockk<LedgerDao>(relaxed = true))
        val fixture = CatchupFixtures.leios(2)
        val generation = prepare(coordinator, fixture)
        val address = requireNotNull(query(source, "SELECT l.address FROM ledger l JOIN ledger_utxos u ON u.ledger_id=l.id WHERE u.network_key=? LIMIT 1", fixture.network).single().single())
        val first = LedgerBlockDecoder.decode(7, fixture.blocks.first())
        val second = LedgerBlockDecoder.decode(7, fixture.blocks.last())
        val nextTx = CatchupFixtures.transaction(
            listOf(CatchupFixtures.input(CatchupFixtures.transactionId(fixture.transactions.first()))),
            outputs = listOf(CatchupFixtures.output(coin = 9_700_000)), fee = 100_000,
        )
        val thirdBytes = CatchupFixtures.block(7, 3, previous = CatchupFixtures.blockHash(fixture.blocks.last()), transactions = listOf(nextTx))
        val fourthBytes = CatchupFixtures.block(7, 4, previous = CatchupFixtures.blockHash(thirdBytes))
        val third = LedgerBlockDecoder.decode(7, thirdBytes)
        val fourth = LedgerBlockDecoder.decode(7, fourthBytes)
        fun unavailable() {
            assertFalse(coordinator.status(fixture.network).walletReady)
        }
        coordinator.observeTip(fixture.network, point(second), generation)
        coordinator.adopt(fixture.network, generation, 7, first, point(first))
        assertEquals("syncing", coordinator.status(fixture.network).status)
        unavailable()
        assertNull(refresh.value)
        coordinator.adopt(fixture.network, generation, 7, second, point(second), closure(fixture))
        assertEquals("complete", coordinator.status(fixture.network).status)
        assertTrue(coordinator.status(fixture.network).walletReady)
        val balance = LedgerRepository.queryUtxos(fixture.network, setOf(address)).getValue(address).sumOf { it.lovelace }
        assertTrue(balance.signum() > 0)
        val followedBalance = balance - java.math.BigInteger.valueOf(100_000)
        var lastRefresh = requireNotNull(refresh.value)

        coordinator.observeTip(fixture.network, point(fourth), generation)
        assertEquals("syncing", coordinator.status(fixture.network).status)
        assertTrue(coordinator.status(fixture.network).walletReady)
        assertEquals(balance, LedgerRepository.queryUtxos(fixture.network, setOf(address)).getValue(address).sumOf { it.lovelace })
        assertEquals(lastRefresh, refresh.value)
        coordinator.adopt(fixture.network, generation, 7, third, point(third))
        assertEquals("syncing", coordinator.status(fixture.network).status)
        assertTrue(coordinator.status(fixture.network).walletReady)
        assertEquals(followedBalance, LedgerRepository.queryUtxos(fixture.network, setOf(address)).getValue(address).sumOf { it.lovelace })
        assertEquals(lastRefresh + 1, refresh.value)
        lastRefresh = requireNotNull(refresh.value)
        coordinator.adopt(fixture.network, generation, 7, fourth, point(fourth))
        assertEquals("complete", coordinator.status(fixture.network).status)
        assertTrue(coordinator.status(fixture.network).walletReady)
        assertEquals(followedBalance, LedgerRepository.queryUtxos(fixture.network, setOf(address)).getValue(address).sumOf { it.lovelace })
        assertEquals(lastRefresh + 1, refresh.value)

        val restarted = ChainIngestionCoordinator(BatchEffectProjector(json), mock(SimpMessagingTemplate::class.java), Configuration().getRefreshWalletChannel(), mockk<LedgerDao>(relaxed = true))
        assertEquals("complete", restarted.status(fixture.network).status)
        assertFalse(restarted.status(fixture.network).walletReady)
        restarted.observeTip(fixture.network, point(fourth), generation)
        assertTrue(restarted.status(fixture.network).walletReady)
        assertEquals(followedBalance, LedgerRepository.queryUtxos(fixture.network, setOf(address)).getValue(address).sumOf { it.lovelace })

        val rolledBack = coordinator.rollback(fixture.network, point(third), generation)
        assertEquals("syncing", coordinator.status(fixture.network).status)
        unavailable()
        coordinator.observeTip(fixture.network, point(third), rolledBack)
        assertTrue(coordinator.status(fixture.network).walletReady)
        assertEquals(followedBalance, LedgerRepository.queryUtxos(fixture.network, setOf(address)).getValue(address).sumOf { it.lovelace })
        assertThrows(ChainIngestionCoordinator.ReplayRequiredException::class.java) {
            coordinator.rollback(fixture.network, ChainPoint("999", "ff".repeat(32)), rolledBack)
        }
        assertEquals("unavailable", coordinator.status(fixture.network).status)
        unavailable()
        val afterReplay = coordinator.beginSession(fixture.network)
        coordinator.observeTip(fixture.network, point(third), afterReplay)
        assertTrue(coordinator.status(fixture.network).walletReady)

        coordinator.boundaryFailure(fixture.network, "fetch failed", afterReplay)
        assertEquals(afterReplay + 1, coordinator.generation(fixture.network))
        unavailable()
        assertThrows(IllegalStateException::class.java) { coordinator.adopt(fixture.network, afterReplay, 7, fourth, point(fourth)) }
        assertThrows(IllegalStateException::class.java) { coordinator.observeTip(fixture.network, point(third), afterReplay) }
        assertEquals("incomplete", coordinator.status(fixture.network).status)
        val replacement = coordinator.beginSession(fixture.network)
        coordinator.observeTip(fixture.network, point(third), replacement)
        assertTrue(coordinator.status(fixture.network).walletReady)
        val session = NetworkChainSession(fixture.network, coordinator, false, replacement, 21600)
        val newer = coordinator.beginSession(fixture.network)
        coordinator.observeTip(fixture.network, point(third), newer)
        session.close()
        assertTrue(coordinator.status(fixture.network).walletReady)
        assertEquals(newer, coordinator.generation(fixture.network))
        NetworkChainSession(fixture.network, coordinator, false, newer, 21600).close()
        assertEquals("stale", coordinator.status(fixture.network).status)
        assertEquals(newer + 1, coordinator.generation(fixture.network))
        unavailable()
    }

    @Test
    fun earlierErasDecodeAndAdopt() = database { source, coordinator, _ ->
        for (era in 1..5) {
            val fixture = CatchupFixtures.historical(era, 2, 2).copy(network = "earlier-era-$era")
            val generation = prepare(coordinator, fixture)
            replay(coordinator, fixture, generation)
            assertExpected(source, fixture)
            assertEquals(point(LedgerBlockDecoder.decode(era, fixture.blocks.last())), coordinator.status(fixture.network).lastCompletePoint)
        }
    }

    @Test
    fun laterBatchCanSpendEarlierOutput() = database { source, coordinator, _ ->
        val seed = CatchupFixtures.seed(1)
        val firstTx = CatchupFixtures.transaction(listOf(CatchupFixtures.input(seed.txId)))
        val secondTx = CatchupFixtures.transaction(listOf(CatchupFixtures.input(CatchupFixtures.transactionId(firstTx))), outputs = listOf(CatchupFixtures.output(coin = 9_600_000)))
        val block = CatchupFixtures.block(7, 1, transactions = listOf(firstTx, secondTx))
        val fixture = CatchupFixtures.Replay("later-batch", 7, listOf(block), listOf(seed))
        replay(coordinator, fixture, prepare(coordinator, fixture))
        val live = query(source, "SELECT tx_id,tx_ix::text,lovelace FROM ledger_utxos WHERE network_key=? AND block_spent IS NULL", fixture.network)
        assertEquals(listOf(listOf(CatchupFixtures.transactionId(secondTx).toHexString(), "0", "9600000")), live)
        val resolved = query(source, "SELECT encode(i.tx_id,'hex'),encode(m.tx_id,'hex') FROM input_occurrences i JOIN output_occurrences o ON o.id=i.resolved_output_id JOIN transaction_members m ON m.id=o.member_id JOIN transaction_batches b ON b.id=m.batch_id JOIN chain_inclusions c ON c.id=b.inclusion_id WHERE c.network_key=?", fixture.network)
        assertEquals(listOf(listOf(CatchupFixtures.transactionId(firstTx).toHexString(), CatchupFixtures.transactionId(firstTx).toHexString())), resolved)
    }

    @Test
    fun duplicateSpendAcrossBatchesRollsBackWholeBlock() = database { source, coordinator, _ ->
        val seed = CatchupFixtures.seed(1)
        val predecessor = CatchupFixtures.block(7, 1)
        val fixture = CatchupFixtures.Replay("duplicate-spend", 7, listOf(predecessor), listOf(seed))
        val generation = prepare(coordinator, fixture)
        replay(coordinator, fixture, generation)
        val before = semantic(source, fixture.network)
        val firstTx = CatchupFixtures.transaction(listOf(CatchupFixtures.input(seed.txId)),
            outputs = listOf(CatchupFixtures.output(coin = 9_800_000)), fee = 200_000)
        val secondTx = CatchupFixtures.transaction(listOf(CatchupFixtures.input(seed.txId)),
            outputs = listOf(CatchupFixtures.output(coin = 9_700_000)), fee = 300_000)
        val successor = LedgerBlockDecoder.decode(7, CatchupFixtures.block(7, 2,
            previous = CatchupFixtures.blockHash(predecessor), transactions = listOf(firstTx, secondTx)))
        assertThrows(IllegalStateException::class.java) {
            coordinator.adopt(fixture.network, generation, 7, successor, point(successor))
        }
        assertEquals(before, semantic(source, fixture.network))
        assertEquals(point(LedgerBlockDecoder.decode(7, predecessor)), coordinator.status(fixture.network).lastCompletePoint)
        assertEquals(listOf(listOf(seed.txId.toHexString(), "0", "10000000")),
            query(source, "SELECT tx_id,tx_ix::text,lovelace FROM ledger_utxos WHERE network_key=? AND spending_inclusion_id IS NULL", fixture.network))
    }

    @Test
    fun spendingOneNetworkDoesNotSpendAnother() = database { source, coordinator, _ ->
        val seed = CatchupFixtures.seed(1)
        val tx = CatchupFixtures.transaction(listOf(CatchupFixtures.input(seed.txId)))
        val block = CatchupFixtures.block(7, 1, transactions = listOf(tx))
        val first = CatchupFixtures.Replay("spend-one", 7, listOf(block), listOf(seed))
        val second = first.copy(network = "spend-two")
        val firstGeneration = prepare(coordinator, first)
        val secondGeneration = prepare(coordinator, second)
        fun state(network: String) = query(source,
            "SELECT tx_id,tx_ix::text,lovelace,(spending_inclusion_id IS NOT NULL)::text FROM ledger_utxos WHERE network_key=? ORDER BY tx_id,tx_ix", network)
        val expected = listOf(listOf(seed.txId.toHexString(), "0", "10000000", "true"),
            listOf(CatchupFixtures.transactionId(tx).toHexString(), "0", "9800000", "false")).sortedBy { it[0] }
        replay(coordinator, first, firstGeneration)
        assertEquals(expected, state(first.network))
        assertEquals(listOf(listOf(seed.txId.toHexString(), "0", "10000000", "false")), state(second.network))
        replay(coordinator, second, secondGeneration)
        assertEquals(expected, state(second.network))
        assertEquals(expected, state(first.network))
    }

    @Test
    fun invalidTransactionOnlySpendsCollateral() = database { source, coordinator, _ ->
        val seeds = (1..3).map(CatchupFixtures::seed)
        val tx = CatchupFixtures.transaction(listOf(CatchupFixtures.input(seeds[0].txId)),
            collateral = listOf(CatchupFixtures.input(seeds[1].txId)),
            references = listOf(CatchupFixtures.input(seeds[2].txId)),
            collateralReturn = CatchupFixtures.output(coin = 9_000_000), valid = false)
        val fixture = CatchupFixtures.Replay("invalid", 7, listOf(CatchupFixtures.block(7, 1, transactions = listOf(tx))), seeds)
        replay(coordinator, fixture, prepare(coordinator, fixture))
        val live = query(source, "SELECT tx_id,tx_ix::text,lovelace FROM ledger_utxos WHERE network_key=? AND block_spent IS NULL ORDER BY tx_id", fixture.network)
        val expected = listOf(listOf(seeds[0].txId.toHexString(), "0", "10000000"),
            listOf(seeds[2].txId.toHexString(), "0", "10000000"),
            listOf(CatchupFixtures.transactionId(tx).toHexString(), "1", "9000000")).sortedBy { it[0] }
        assertEquals(expected, live)
        val roles = query(source, "SELECT i.role,i.effective::text FROM input_occurrences i JOIN transaction_members m ON m.id=i.member_id JOIN transaction_batches b ON b.id=m.batch_id JOIN chain_inclusions c ON c.id=b.inclusion_id WHERE c.network_key=? ORDER BY i.role", fixture.network)
        assertEquals(listOf(listOf("collateral", "true"), listOf("reference", "false"), listOf("spending", "false")), roles)
    }

    @Test
    fun depositAndReferenceSurviveRollbackAndReadoption() = database { source, coordinator, _ ->
        val seeds = (1..2).map(CatchupFixtures::seed)
        val tx = CatchupFixtures.transaction(listOf(CatchupFixtures.input(seeds[0].txId)),
            outputs = listOf(CatchupFixtures.output(coin = 9_799_000)),
            references = listOf(CatchupFixtures.input(seeds[1].txId)),
            directDeposits = mapOf(CatchupFixtures.rewardAddress to 1_000L))
        val first = CatchupFixtures.block(7, 1)
        val second = CatchupFixtures.block(7, 2, previous = CatchupFixtures.blockHash(first), transactions = listOf(tx))
        val fixture = CatchupFixtures.Replay("deposit", 7, listOf(first, second), seeds)
        val generation = prepare(coordinator, fixture)
        replay(coordinator, fixture, generation)
        fun amounts() = query(source, "SELECT family,state_value FROM derived_account_governance_states WHERE network_key=? ORDER BY family", fixture.network)
        assertEquals(listOf(listOf("DIRECT_DEPOSIT", "1000")), amounts())
        val reference = query(source, "SELECT i.effective::text FROM input_occurrences i JOIN transaction_members m ON m.id=i.member_id JOIN transaction_batches b ON b.id=m.batch_id JOIN chain_inclusions c ON c.id=b.inclusion_id WHERE c.network_key=? AND i.role='reference'", fixture.network)
        assertEquals(listOf(listOf("false")), reference)
        val original = semantic(source, fixture.network)
        val next = coordinator.rollback(fixture.network, point(LedgerBlockDecoder.decode(7, first)), generation)
        assertTrue(amounts().isEmpty())
        val decoded = LedgerBlockDecoder.decode(7, second)
        coordinator.adopt(fixture.network, next, 7, decoded, point(decoded))
        assertEquals(listOf(listOf("DIRECT_DEPOSIT", "1000")), amounts())
        assertEquals(original, semantic(source, fixture.network))
    }

    @Test
    fun childBeforeParentAndSameBatchSpendRejected() = database { source, coordinator, _ ->
        val seeds = (1..2).map(CatchupFixtures::seed)
        val child = CatchupFixtures.transaction(listOf(CatchupFixtures.input(seeds[0].txId)))
        val parent = CatchupFixtures.transaction(listOf(CatchupFixtures.input(seeds[1].txId)), children = listOf(child))
        val block = CatchupFixtures.block(7, 1, transactions = listOf(parent))
        val fixture = CatchupFixtures.Replay("children", 7, listOf(block), seeds)
        replay(coordinator, fixture, prepare(coordinator, fixture))
        val order = query(source, "SELECT (m.parent_id IS NULL)::text,m.processing_ordinal::text FROM transaction_members m JOIN transaction_batches b ON b.id=m.batch_id JOIN chain_inclusions c ON c.id=b.inclusion_id WHERE c.network_key=? ORDER BY m.processing_ordinal", fixture.network)
        assertEquals(listOf(listOf("false", "0"), listOf("true", "1")), order)
        val childId = com.swiftmako.jormanager.nodeclient.protocols.blockfetch.DijkstraBlockDecoder.decodeIncludedTransaction(parent).children.single().bodyId
        val invalidParent = CatchupFixtures.transaction(listOf(CatchupFixtures.input(childId)), children = listOf(child))
        val bad = CatchupFixtures.Replay("same-batch", 7, listOf(CatchupFixtures.block(7, 1, transactions = listOf(invalidParent))), seeds)
        val generation = prepare(coordinator, bad)
        val before = semantic(source, bad.network)
        assertThrows(IllegalArgumentException::class.java) { replay(coordinator, bad, generation) }
        assertEquals(before, semantic(source, bad.network))
    }

    @Test
    fun outputAddressesAndAssetsRetainAttributionAcrossNetworks() = database { source, coordinator, _ ->
        val seed = CatchupFixtures.seed(42)
        val address = byteArrayOf(0x60) + ByteArray(28) { 0x33 }
        val policy = ByteArray(28) { 0x44 }
        val asset = byteArrayOf(0x55)
        val otherAsset = byteArrayOf(0x56)
        val outputs = listOf(
            CatchupFixtures.output(coin = 4_000_000, assets = listOf(Triple(policy, asset, 2L), Triple(policy, otherAsset, 5L))),
            CatchupFixtures.output(address, 3_000_000, listOf(Triple(policy, asset, 3L))),
            CatchupFixtures.output(address, 2_800_000, listOf(Triple(policy, otherAsset, 4L))))
        val tx = CatchupFixtures.transaction(listOf(CatchupFixtures.input(seed.txId)), outputs = outputs)
        val block = CatchupFixtures.block(7, 1, transactions = listOf(tx))
        for (network in listOf("asset-one", "asset-two")) {
            val fixture = CatchupFixtures.Replay(network, 7, listOf(block), listOf(seed))
            replay(coordinator, fixture, prepare(coordinator, fixture))
            val rows = query(source, "SELECT u.tx_ix::text,u.lovelace,u.ledger_id::text,a.ledger_asset_id::text,a.amount,encode(o.address,'hex') FROM ledger_utxos u JOIN ledger_utxo_assets a ON a.ledger_utxo_id=u.id JOIN ledger_assets p ON p.id=a.ledger_asset_id JOIN output_occurrences o ON o.id=u.output_occurrence_id WHERE u.network_key=? ORDER BY u.tx_ix,p.name", network)
            assertEquals(listOf("0", "0", "1", "2"), rows.map { it[0] })
            assertEquals(listOf("4000000", "4000000", "3000000", "2800000"), rows.map { it[1] })
            assertEquals(listOf("2", "5", "3", "4"), rows.map { it[4] })
            assertEquals(listOf(CatchupFixtures.address.toHexString(), CatchupFixtures.address.toHexString(), address.toHexString(), address.toHexString()), rows.map { it[5] })
            assertNotEquals(rows[0][2], rows[2][2])
            assertEquals(rows[2][2], rows[3][2])
            assertEquals(rows[0][3], rows[2][3])
            assertEquals(rows[1][3], rows[3][3])
            assertNotEquals(rows[0][3], rows[1][3])
        }
    }

    @Test
    fun failedAdoptionCannotRetainRolledBackIdentityCacheEntries() = database { source, coordinator, _ ->
        val seeds = (1..2).map(CatchupFixtures::seed)
        val address = byteArrayOf(0x60) + ByteArray(28) { 0x66 }
        val policy = ByteArray(28) { 0x77 }
        val firstTx = CatchupFixtures.transaction(listOf(CatchupFixtures.input(seeds[0].txId)),
            outputs = listOf(CatchupFixtures.output(address, assets = listOf(Triple(policy, byteArrayOf(1), 3L)))))
        val missingTx = CatchupFixtures.transaction(listOf(CatchupFixtures.input(CatchupFixtures.seed(999).txId)))
        val validTx = CatchupFixtures.transaction(listOf(CatchupFixtures.input(seeds[1].txId)))
        val predecessor = CatchupFixtures.block(7, 1)
        val previous = CatchupFixtures.blockHash(predecessor)
        val bad = CatchupFixtures.block(7, 2, previous, listOf(firstTx, missingTx))
        val good = CatchupFixtures.block(7, 2, previous, listOf(firstTx, validTx))
        val fixture = CatchupFixtures.Replay("rolled-back-identities", 7, listOf(predecessor), seeds)
        val generation = prepare(coordinator, fixture)
        replay(coordinator, fixture, generation)
        val before = semantic(source, fixture.network)
        val rejected = LedgerBlockDecoder.decode(7, bad)
        assertThrows(IllegalStateException::class.java) {
            coordinator.adopt(fixture.network, generation, 7, rejected, point(rejected))
        }
        assertEquals(before, semantic(source, fixture.network))
        val accepted = LedgerBlockDecoder.decode(7, good)
        coordinator.adopt(fixture.network, generation, 7, accepted, point(accepted))
        assertEquals(listOf(listOf("3", policy.toHexString(), "01", address.toHexString())),
            query(source, "SELECT a.amount,p.policy,p.name,encode(o.address,'hex') FROM ledger_utxos u JOIN ledger_utxo_assets a ON a.ledger_utxo_id=u.id JOIN ledger_assets p ON p.id=a.ledger_asset_id JOIN output_occurrences o ON o.id=u.output_occurrence_id WHERE u.network_key=?", fixture.network))
        assertEquals(point(accepted), coordinator.status(fixture.network).lastCompletePoint)
    }

    @Test
    fun protocolTipsRemainDurableAcrossRepeatsChangesAndRollback() = database { _, coordinator, _ ->
        com.muquit.libsodiumjna.SodiumLibrary.setLibraryPath("/usr/local/lib/libsodium.so")
        val fixture = CatchupFixtures.conway(3, 0)
        val generation = prepare(coordinator, fixture)
        replay(coordinator, fixture.copy(blocks = fixture.blocks.take(2)), generation)
        val decoded = fixture.blocks.map { LedgerBlockDecoder.decode(6, it) }
        val second = point(decoded[1]); val third = point(decoded[2])
        val session = NetworkChainSession(fixture.network, coordinator, false, generation, 21600)
        val sync = ChainSyncProtocol(mock(Host::class.java), ByteArray(32), emptyList(),
            mock(ChainRepository::class.java), false, mock(PooltoolService::class.java), "", "", session,
            intersectionPoints = listOf(second))
        runBlocking {
            suspend fun send() { muxByteBufferPool.recycle(sync.sendData()) }
            fun receive(bytes: ByteArray) = sync.receiveData(ByteBuffer.wrap(bytes))
            suspend fun forward(index: Int) {
                val block = fixture.blocks[index]
                receive(CatchupFixtures.rollForward(6, block, index + 1L, CatchupFixtures.blockHash(block), index + 1L))
                send()
                assertEquals(point(decoded[index]), session.next().point)
                session.release()
            }
            try {
                send()
                receive(CatchupFixtures.intersectFound(
                    CatchupFixtures.point(2, CatchupFixtures.blockHash(fixture.blocks[1])),
                    CatchupFixtures.tip(2, CatchupFixtures.blockHash(fixture.blocks[1]), 2)))
                send()
                assertEquals("complete", coordinator.status(fixture.network).status)
                repeat(2) { forward(1) }
                assertEquals(second, coordinator.status(fixture.network).observedTip)
                forward(2)
                assertEquals(third, coordinator.status(fixture.network).observedTip)
                assertEquals("syncing", coordinator.status(fixture.network).status)
                coordinator.adopt(fixture.network, session.generation, 6, decoded[2], third)
                assertEquals("complete", coordinator.status(fixture.network).status)
                receive(CatchupFixtures.array(CatchupFixtures.integer(3),
                    CatchupFixtures.point(2, CatchupFixtures.blockHash(fixture.blocks[1])),
                    CatchupFixtures.tip(3, CatchupFixtures.blockHash(fixture.blocks[2]), 3)))
                send()
                assertEquals(generation + 1, session.generation)
                assertEquals(second, coordinator.status(fixture.network).lastCompletePoint)
                assertEquals(third, coordinator.status(fixture.network).observedTip)
                assertEquals("syncing", coordinator.status(fixture.network).status)
                forward(2)
                coordinator.adopt(fixture.network, session.generation, 6, decoded[2], third)
                assertEquals("complete", coordinator.status(fixture.network).status)
                coordinator.boundaryFailure(fixture.network, "test boundary failure", session.generation)
                assertThrows(IllegalStateException::class.java) { coordinator.observeTip(fixture.network, third, session.generation) }
                assertEquals("incomplete", coordinator.status(fixture.network).status)
                assertEquals("test boundary failure", coordinator.status(fixture.network).reason)
            } finally {
                sync.shutdown(); session.release(); session.close()
            }
        }
    }
}
