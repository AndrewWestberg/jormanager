package com.swiftmako.jormanager.services

import com.swiftmako.jormanager.entities.AuthorizedOperation
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.AuthorizedOperationInput
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.ChainPoint
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.DecodedGenesisOutput
import com.swiftmako.jormanager.model.NativeAsset
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.LedgerBlockDecoder
import com.swiftmako.jormanager.nodeclient.protocols.fixtures.CatchupFixtures as F
import com.swiftmako.jormanager.repositories.AuthorizedOperationRepository
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.repositories.LedgerRepository
import com.swiftmako.jormanager.spring.config.Configuration
import com.swiftmako.jormanager.utils.CborSpans
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.mockk.every
import io.mockk.mockk
import java.math.BigInteger
import java.net.URI
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import liquibase.command.CommandScope
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.SharedEntityManagerCreator
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import tools.jackson.databind.json.JsonMapper

/** All persistence and ingestion use a migrated UUID-named local PostgreSQL sibling. */
class ReviewedWalletOperationsIntegrationTest {
    private val json = JsonMapper.builder().build()
    private val url = System.getenv("JORMANAGER_TEST_DATABASE_URL")

    private fun database(rootCount: Int = 6, era: String = "conway", test: (Fixture) -> Unit) {
        assumeTrue(!url.isNullOrBlank(), "Set JORMANAGER_TEST_DATABASE_URL to a disposable local PostgreSQL database")
        val target = URI(requireNotNull(url).removePrefix("jdbc:"))
        require(target.scheme == "postgresql" && target.host in setOf("localhost", "127.0.0.1") &&
            target.path.matches(Regex("/jormanager_test_[a-zA-Z0-9_]+")))
        val name = "jormanager_test_operations_" + UUID.randomUUID().toString().replace("-", "")
        val sibling = "jdbc:" + URI(target.scheme, target.userInfo, target.host, target.port, "/$name", target.query, null)
        var created = false
        var pool: HikariDataSource? = null
        var database: Database? = null
        var factory: LocalContainerEntityManagerFactoryBean? = null
        try {
            DriverManager.getConnection(url).use { it.createStatement().use { s -> s.execute("CREATE DATABASE $name") } }
            created = true
            DriverManager.getConnection(sibling).use { connection ->
                val migration = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
                CommandScope("update").addArgumentValue("database", migration)
                    .addArgumentValue("changelogFile", "db/liquibase-changelog.xml").execute()
            }
            pool = HikariDataSource(HikariConfig().apply { jdbcUrl = sibling; maximumPoolSize = 12; isAutoCommit = false })
            pool.connection.use { c ->
                c.createStatement().use { s ->
                    s.execute("INSERT INTO hosts(id,type,cardano_cli_path,cardano_node_path,hostname,ssh_user,ssh_port,ssh_pem_path,node_home_path) VALUES (1,'local','cli','node','localhost','user',22,'','/node')")
                    s.execute("INSERT INTO nodes(id,host_id,color,type,processor_threads,name,listen,port,genesis_byron_file_id,genesis_shelley_file_id,config_file_id,is_default) VALUES (2,1,'blue','relay',1,'relay','localhost',3001,1,1,1,true)")
                }
                c.commit()
            }
            database = Database.connect(pool)
            factory = LocalContainerEntityManagerFactoryBean().apply {
                dataSource = pool
                jpaVendorAdapter = HibernateJpaVendorAdapter()
                setManagedTypes(PersistenceManagedTypes.of(AuthorizedOperation::class.java.name, AuthorizedOperationInput::class.java.name))
                setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "none"))
                afterPropertiesSet()
            }
            val emf = requireNotNull(factory.`object`)
            val em = SharedEntityManagerCreator.createSharedEntityManager(emf)
            val repository = JpaRepositoryFactory(em).getRepository(AuthorizedOperationRepository::class.java)
            val manager = JpaTransactionManager(emf)
            val coordinator = ChainIngestionCoordinator(BatchEffectProjector(json), mock(SimpMessagingTemplate::class.java), Configuration().getRefreshWalletChannel())
            val host = mockk<Host>()
            val node = mockk<Node>()
            val eras = mockk<CardanoRepository>()
            every { host.id } returns 1L
            every { host.cardanoCliPath } returns "cli"
            every { node.id } returns 2L
            every { eras.networkKey(node) } returns "operation-test"
            every { eras.evidence(node) } returns CardanoRepository.EraEvidence("operation-test", era, "current", "host:1/node:2", generation = 1)
            val service = ReviewedWalletOperations(repository, em, eras, json, coordinator, manager)
            test(Fixture(pool, coordinator, service, repository, host, node, rootCount, era))
        } finally {
            factory?.destroy()
            database?.let { TransactionManager.closeAndUnregister(it) }
            pool?.close()
            if (created) DriverManager.getConnection(url).use { it.createStatement().use { s -> s.execute("DROP DATABASE $name WITH (FORCE)") } }
        }
    }

    private inner class Fixture(
        val pool: HikariDataSource, val chain: ChainIngestionCoordinator, val service: ReviewedWalletOperations,
        val repository: AuthorizedOperationRepository, val host: Host, val node: Node, rootCount: Int, val era: String,
    ) {
        val network = "operation-test"
        val address = LedgerRepository.addressText(F.address)
        val eraNumber = if (era == "dijkstra") 7 else 6
        val roots = (0 until rootCount).map { index -> DecodedGenesisOutput(ByteArray(32) { (index + 1).toByte() }, F.address, BigInteger.valueOf(100_000_000)) }
        var generation: Long
        var block: ByteArray
        var height = 1L
        val evidence get() = ReviewedWalletOperations.Evidence("request", "params", "state-${generation}", "capability", generation.toString())
        init {
            chain.registerNetwork(network, F.genesisHash, roots)
            generation = chain.beginSession(network)
            block = F.block(eraNumber, height)
            adopt(block)
        }
        fun point(bytes: ByteArray): ChainPoint {
            val decoded = LedgerBlockDecoder.decode(eraNumber, bytes)
            return ChainPoint(requireNotNull(decoded.headerDetails).slot.toString(), decoded.header.hash256().toHexString())
        }
        fun adopt(bytes: ByteArray) {
            val p = point(bytes)
            chain.observeTip(network, p, generation)
            chain.adopt(network, generation, eraNumber, LedgerBlockDecoder.decode(eraNumber, bytes), p)
            block = bytes
        }
        fun confirm(vararg tx: ByteArray) {
            height++
            adopt(F.block(eraNumber, height, F.blockHash(block), tx.toList()))
        }
        fun submission(tx: ByteArray): String {
            val parts = CborSpans.parse(tx).array()
            return (if (era == "dijkstra") F.array(parts[0].bytes(), parts[1].bytes(), parts[2].bytes())
                else F.array(parts[0].bytes(), parts[1].bytes(), byteArrayOf(0xf5.toByte()), parts[2].bytes())).toHexString()
        }
        fun preview(id: String, tx: ByteArray, body: ReviewedWalletOperations.CliBody? = null, until: Long? = 10_000_000): AuthorizedOperation {
            val signed = submission(tx)
            val decoded = ReviewedTransactionIdentity.decodeSubmitted(signed, era)
            val envelope = json.writeValueAsString(mapOf("type" to "Tx ${era.replaceFirstChar { it.uppercase() }}Era", "cborHex" to signed))
            return service.preview(ReviewedWalletOperations.Preview(id, "preview-$id", host, node, "wallet.send", evidence,
                "{}", body ?: ReviewedWalletOperations.CliBody(envelope, decoded.id), BigInteger.valueOf(200_000),
                BigInteger.ZERO, BigInteger.ZERO, 0, until))
        }

        fun expiryTransaction(index: Int, until: Long? = 1): ByteArray {
            val tx = F.transaction(listOf(F.input(roots[index].txId)), listOf(F.output(coin = 99_800_000)))
            val parts = CborSpans.parse(tx).array()
            val entries = parts[0].entries().filter { it.first.integer().toInt() != 3 }
                .map { it.first.bytes() to it.second.bytes() }.toMutableList()
            if (until != null) entries.add(F.integer(3) to F.integer(until))
            return F.array(F.map(entries), parts[1].bytes(), parts[2].bytes(), parts[3].bytes())
        }
        fun expire(vararg ids: String, generation: Long = this.generation, point: ChainPoint = point(block)): Boolean =
            service.expirePending(network, generation, point.slot.toBigInteger(), point.hash, ids.toSet())
        fun submit(id: String, tx: ByteArray, callback: (String) -> Unit = {}): AuthorizedOperation =
            service.submit("preview-$id", host, node, { evidence }, { submission(tx) }, callback)
        fun state(id: String) = repository.findById(id).orElseThrow()
        fun reservations(id: String): Set<Pair<String, Int>> = pool.connection.use { c ->
            c.prepareStatement("SELECT input_tx_id,input_index FROM authorized_operation_inputs WHERE operation_id=?").use { s ->
                s.setString(1, id)
                s.executeQuery().use { r -> buildSet { while (r.next()) add(r.getString(1) to r.getInt(2)) } }
            }
        }
        fun snapshot(nodeOutputs: List<Utxo>, inMempool: Map<String, Boolean>, excluded: String? = null): PendingWalletState.Snapshot {
            val db = LedgerDao(chain).querySnapshot(network, setOf(address))
            val nodes = nodeOutputs.associate { (it.hash to it.ix.toInt()) to PendingWalletState.PendingOutput(address, it, null) }
            return PendingWalletState.merge(db, setOf(address), nodes, inMempool, BigInteger.valueOf(height), excluded)
        }
        fun balance(snapshot: PendingWalletState.Snapshot) = snapshot.utxosByAddress.getValue(address).sumOf { it.lovelace }
        fun root(index: Int) = Utxo(roots[index].txId.toHexString(), 0, roots[index].coin, emptyList())
        fun build(input: Utxo, amount: Long): Pair<ReviewedWalletOperations.CliBody, ByteArray> {
            val external = byteArrayOf(0x60) + ByteArray(28) { 99 }
            val recipient = LedgerRepository.addressText(external)
            val connection = mockk<HostConnection>()
            var tx = ByteArray(0)
            every { connection.commandArgs(any()) } answers {
                val args = firstArg<List<String>>()
                fun values(option: String) = args.indices.filter { args[it] == option }.map { args[it + 1] }
                when (args[3]) {
                    "calculate-min-required-utxo" -> "1000000 Lovelace"
                    "calculate-min-fee" -> "200000 Lovelace"
                    "build-raw" -> {
                        val inputs = values("--tx-in").map { ref ->
                            val (hash, index) = ref.split("#")
                            F.input(hash.hexToByteArray(), index.toInt())
                        }
                        val outputs = values("--tx-out").map { value ->
                            val parts = value.split("+")
                            val addr = when (parts[0]) { address -> F.address; recipient -> external; else -> error("unknown address") }
                            val assets = parts.drop(2).map { asset ->
                                val (quantity, unit) = asset.trim().split(" ")
                                val policy = unit.substringBefore(".").hexToByteArray()
                                val name = unit.substringAfter(".", "").hexToByteArray()
                                Triple(policy, name, quantity.toLong())
                            }
                            F.output(addr, parts[1].toLong(), assets)
                        }
                        tx = F.transaction(inputs, outputs, fee = values("--fee").single().toLong())
                        ""
                    }
                    "txid" -> ReviewedTransactionIdentity.decodeSubmitted(submission(tx), era).id
                    else -> error("unexpected CLI command: $args")
                }
            }
            every { connection.commandReadFile(any()) } answers {
                json.writeValueAsString(mapOf("type" to "Tx ConwayEra", "cborHex" to submission(tx)))
            }
            val body = WalletTransactionBuilder(json).build(host, connection, era, listOf(input),
                listOf(WalletTransactionBuilder.Output(recipient, BigInteger.valueOf(amount))), address,
                protocolParameters = """{"maxTxSize":16384,"maxValueSize":5000}""",
                protocolPath = "params", witnessCount = 1, bodyPath = "body")
            return body to tx
        }
    }

    @Test fun `durable reservations self exclusion and uncertain outcomes`() = database { f ->
        val tx = F.transaction(listOf(F.input(f.roots[0].txId)), listOf(F.output(coin = 99_800_000)))
        f.preview("uncertain", tx)
        var checks = 0
        assertThrows(IllegalStateException::class.java) {
            f.service.submit("preview-uncertain", f.host, f.node, { record ->
                checks++
                assertEquals("uncertain", record.id)
                if (checks == 3) {
                    assertEquals(1, f.reservations(record.id).size)
                    val nodes = (0..5).map(f::root)
                    assertFalse(f.snapshot(nodes, emptyMap()).utxosByAddress.getValue(f.address).any { it.hash == f.root(0).hash })
                    assertTrue(f.snapshot(nodes, emptyMap(), record.id).utxosByAddress.getValue(f.address).any { it.hash == f.root(0).hash })
                }
                f.evidence
            }, { f.submission(tx) }, { throw IllegalStateException("node outcome unknown") })
        }
        assertEquals(3, checks)
        assertEquals("submission_uncertain", f.state("uncertain").state)
        assertEquals(1, f.reservations("uncertain").size)
        val other = F.transaction(listOf(F.input(f.roots[1].txId)), listOf(F.output(coin = 99_800_000)))
        f.preview("failed-check", other)
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            f.service.submit("preview-failed-check", f.host, f.node, { if (++calls == 3) error("changed") else f.evidence },
                { f.submission(other) }, { fail<Unit>("must not broadcast") })
        }
        assertEquals("not_submitted", f.state("failed-check").state)
        assertTrue(f.reservations("failed-check").isEmpty())
        val mismatch = F.transaction(listOf(F.input(f.roots[2].txId)), listOf(F.output(coin = 99_800_000)))
        f.preview("identity", mismatch)
        assertThrows(IllegalStateException::class.java) {
            f.service.submit("preview-identity", f.host, f.node, { f.evidence }, { f.submission(other) }, { fail<Unit>("must not broadcast") })
        }
        assertEquals("not_submitted", f.state("identity").state)
    }

    @Test fun `reservation is fenced when wallet generation changes`() = database { f ->
        val tx = F.transaction(listOf(F.input(f.roots[0].txId)), listOf(F.output(coin = 99_800_000)))
        f.preview("generation", tx)
        val oldEvidence = f.evidence
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            f.service.submit("preview-generation", f.host, f.node, {
                if (++calls == 2) f.chain.boundaryFailure(f.network, "test failure", f.generation)
                oldEvidence
            }, { f.submission(tx) }, { fail<Unit>("must not broadcast") })
        }
        assertEquals("not_submitted", f.state("generation").state)
        assertTrue(f.reservations("generation").isEmpty())
    }

    @Test fun `concurrent reviews cannot reserve the same input`() = database { f ->
        val input = F.input(f.roots[0].txId)
        val a = F.transaction(listOf(input), listOf(F.output(coin = 99_800_000)))
        val b = F.transaction(listOf(input), listOf(F.output(coin = 99_700_000)))
        f.preview("a", a); f.preview("b", b)
        val barrier = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf("a" to a, "b" to b).map { (id, tx) -> executor.submit<Boolean> {
                try {
                    f.service.submit("preview-$id", f.host, f.node, { f.evidence }, {
                        barrier.countDown(); check(barrier.await(30, TimeUnit.SECONDS)); f.submission(tx)
                    }, {})
                    true
                } catch (_: Exception) { false }
            } }
            assertEquals(1, futures.count { it.get(60, TimeUnit.SECONDS) })
            assertEquals(1, f.reservations("a").size + f.reservations("b").size)
        } finally { executor.shutdownNow() }
    }

    @Test fun `pending change survives confirmation with one payer root`() = database(rootCount = 1) { f ->
        pendingScenario(f, false)
    }

    @Test fun `native asset pending change and descendants survive confirmation`() = database(rootCount = 1) { f ->
        pendingScenario(f, true)
    }

    private fun pendingScenario(f: Fixture, withAsset: Boolean) {
        val token = NativeAsset("01", ByteArray(28) { 7 }.toHexString(), BigInteger("9007199254740993"))
        val root = if (withAsset) {
            val funding = F.transaction(listOf(F.input(f.roots[0].txId)),
                listOf(F.output(coin = 100_000_000, assets = listOf(Triple(token.policy.hexToByteArray(), token.name.hexToByteArray(), token.amount.toLong())))), fee = 0)
            f.confirm(funding)
            Utxo(ReviewedTransactionIdentity.decodeSubmitted(f.submission(funding), f.era).id, 0, BigInteger.valueOf(100_000_000), listOf(token))
        } else f.root(0)
        val beforeA = f.point(f.block)
        val (aBody, a) = f.build(root, 40_000_000)
        f.preview("a", a, aBody); f.submit("a", a)
        val first = f.snapshot(listOf(root), mapOf("a" to true)).utxosByAddress.getValue(f.address).single()
        assertEquals(Utxo(aBody.txId, 1, BigInteger.valueOf(59_800_000), root.nativeAssets), first)
        val (bBody, b) = f.build(first, 10_000_000)
        assertEquals(setOf(aBody.txId to 1), ReviewedTransactionIdentity.decodeSubmitted(f.submission(b), f.era).normalInputs)
        f.preview("b", b, bBody); f.submit("b", b)
        val expected = Utxo(bBody.txId, 1, BigInteger.valueOf(49_600_000), root.nativeAssets)
        fun assertOnly(snapshot: PendingWalletState.Snapshot) =
            assertEquals(listOf(expected), snapshot.utxosByAddress.getValue(f.address))
        assertOnly(f.snapshot(listOf(root), mapOf("a" to true, "b" to true)))
        assertTrue(f.snapshot(listOf(root), mapOf("a" to false, "b" to true)).utxosByAddress.getValue(f.address).isEmpty())
        f.confirm(a)
        assertOnly(f.snapshot(listOf(first), mapOf("b" to true)))
        f.confirm(b)
        assertOnly(f.snapshot(listOf(expected), emptyMap()))
        f.generation = f.chain.rollback(f.network, beforeA, f.generation)
        f.chain.observeTip(f.network, beforeA, f.generation)
        assertEquals("rolled_back", f.state("a").state)
        assertEquals("rolled_back", f.state("b").state)
        assertTrue(f.snapshot(listOf(root), emptyMap()).utxosByAddress.getValue(f.address).isEmpty())
    }

    @Test fun `expiry releases only absent eligible operations at the matched point`() = database { f ->
        val submitted = f.expiryTransaction(0)
        val uncertain = f.expiryTransaction(1)
        val rolledBack = f.expiryTransaction(2)
        val future = f.expiryTransaction(3, 100)
        val noExpiry = f.expiryTransaction(4, null)
        listOf("submitted" to submitted, "uncertain" to uncertain, "rollback" to rolledBack).forEach { (id, tx) ->
            f.preview(id, tx, until = 1)
            if (id == "uncertain") assertThrows(IllegalStateException::class.java) {
                f.submit(id, tx) { error("unknown broadcast") }
            } else f.submit(id, tx)
        }
        f.preview("future", future, until = 100); f.submit("future", future)
        f.preview("legacy", noExpiry, until = null); f.submit("legacy", noExpiry)
        val priorBlock = f.block
        val prior = f.point(priorBlock)
        f.confirm(rolledBack)
        f.generation = f.chain.rollback(f.network, prior, f.generation)
        f.chain.observeTip(f.network, prior, f.generation)
        f.block = priorBlock
        assertEquals("rolled_back", f.state("rollback").state)
        assertFalse(f.expire("submitted", generation = f.generation + 1))
        assertFalse(f.expire("submitted", point = prior.copy(hash = "00".repeat(32))))
        assertFalse(f.expire("future", "legacy"))
        assertFalse(f.expire())
        assertEquals(1, f.reservations("submitted").size)
        assertTrue(f.expire("submitted", "uncertain", "rollback"))
        listOf("submitted", "uncertain", "rollback").forEach {
            assertEquals("expired", f.state(it).state)
            assertTrue(f.reservations(it).isEmpty())
        }
        assertFalse(f.expire("submitted", "uncertain", "rollback"))
        assertEquals(1, f.reservations("future").size)
        assertEquals(1, f.reservations("legacy").size)
        assertTrue(f.snapshot((0..5).map(f::root), emptyMap()).utxosByAddress.getValue(f.address).any { it.hash == f.root(0).hash })
    }

    @Test fun `expired signing is fenced but live signing continues`() = database { f ->
        val tx = f.expiryTransaction(0)
        f.preview("signing", tx, until = 1)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val signer = executor.submit<Boolean> {
                try {
                    f.service.submit("preview-signing", f.host, f.node, { f.evidence }, {
                        entered.countDown(); check(resume.await(30, TimeUnit.SECONDS)); f.submission(tx)
                    }, { fail<Unit>("expired signer must not broadcast") })
                    true
                } catch (_: Exception) { false }
            }
            assertTrue(entered.await(30, TimeUnit.SECONDS))
            assertFalse(f.expire("signing", point = f.point(f.block).copy(slot = "0")))
            assertTrue(f.expire("signing"))
            assertEquals("not_submitted", f.state("signing").state)
            resume.countDown()
            assertFalse(signer.get(30, TimeUnit.SECONDS))
            assertTrue(f.reservations("signing").isEmpty())
        } finally { resume.countDown(); executor.shutdownNow() }
    }

    @Test fun `unexpired signer is not fenced`() = database { f ->
        val tx = f.expiryTransaction(0, 100)
        f.preview("live", tx, until = 100)
        var broadcast = false
        val result = f.service.submit("preview-live", f.host, f.node, { f.evidence }, {
            assertFalse(f.expire("live"))
            f.submission(tx)
        }, { broadcast = true })
        assertTrue(broadcast)
        assertEquals("submitted", result.state)
    }

    @Test fun `concurrent adoption prevents expiry at the old point`() = database { f ->
        val tx = f.expiryTransaction(0)
        f.preview("adoption", tx, until = 1); f.submit("adoption", tx)
        val oldPoint = f.point(f.block)
        val executor = Executors.newFixedThreadPool(2)
        try {
            f.pool.connection.use { lock ->
                lock.prepareStatement("SELECT network_key FROM sync_states WHERE network_key=? FOR UPDATE").use {
                    it.setString(1, f.network); it.executeQuery().close()
                }
                val adoption = executor.submit { f.confirm(tx) }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                var waiting = false
                while (!waiting && System.nanoTime() < deadline) {
                    f.pool.connection.use { c ->
                        c.createStatement().use { s ->
                            s.executeQuery("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE '%sync_states%')").use {
                                it.next(); waiting = it.getBoolean(1)
                            }
                        }
                    }
                    if (!waiting) Thread.sleep(10)
                }
                assertTrue(waiting)
                val expiry = executor.submit<Boolean> { f.expire("adoption", point = oldPoint) }
                lock.commit()
                adoption.get(30, TimeUnit.SECONDS)
                assertFalse(expiry.get(30, TimeUnit.SECONDS))
            }
            assertEquals("confirmed", f.state("adoption").state)
            assertEquals(1, f.reservations("adoption").size)
        } finally { executor.shutdownNow() }
    }

    @Test fun `expiry preserves malformed and adopted conflicting identities`() = database { f ->
        val malformed = f.expiryTransaction(0)
        f.preview("malformed", malformed, until = 1); f.submit("malformed", malformed)
        f.pool.connection.use { connection ->
            connection.prepareStatement("UPDATE authorized_operations SET signed_batch=? WHERE id=?").use {
                it.setString(1, "00")
                it.setString(2, "malformed")
                it.executeUpdate()
            }
            connection.commit()
        }
        assertFalse(f.expire("malformed"))
        assertEquals(1, f.reservations("malformed").size)
        val tx = f.expiryTransaction(1)
        f.preview("conflict", tx, until = 1); f.submit("conflict", tx)
        val parts = CborSpans.parse(tx).array()
        val witness = F.map(listOf(F.integer(0) to (byteArrayOf(0xd9.toByte(), 1, 2) +
            F.array(F.array(F.bytes(ByteArray(32) { 1 }), F.bytes(ByteArray(64) { 2 }))))))
        f.confirm(F.array(parts[0].bytes(), witness, parts[2].bytes(), parts[3].bytes()))
        assertEquals("submitted", f.state("conflict").state)
        assertFalse(f.expire("conflict"))
        assertEquals(1, f.reservations("conflict").size)
    }

    @Test fun `reservation release follows effective branch`() = database { f ->
        val success = F.transaction(listOf(F.input(f.roots[1].txId)), listOf(F.output(coin = 99_800_000)), collateral = listOf(F.input(f.roots[2].txId)))
        f.preview("success", success); f.submit("success", success)
        f.confirm(success)
        assertEquals(setOf(f.roots[1].txId.toHexString() to 0), f.reservations("success"))
        val failed = F.transaction(listOf(F.input(f.roots[3].txId)), listOf(F.output(coin = 99_800_000)), collateral = listOf(F.input(f.roots[4].txId)), valid = false)
        f.preview("failure", failed); f.submit("failure", failed)
        f.confirm(failed)
        assertEquals(setOf(f.roots[4].txId.toHexString() to 0), f.reservations("failure"))
        assertEquals("phase2_failed", f.state("failure").state)
    }
    @Test fun `confirmation races submission and rollback preserves reservations`() = database { f ->
        val prior = f.point(f.block)
        val tx = F.transaction(listOf(F.input(f.roots[0].txId)), listOf(F.output(coin = 99_800_000)),
            collateral = listOf(F.input(f.roots[1].txId)))
        f.preview("race", tx)
        val result = f.submit("race", tx) { f.confirm(tx) }
        assertEquals("confirmed", result.state)
        assertEquals(setOf(f.root(0).hash to 0), f.reservations("race"))
        f.generation = f.chain.rollback(f.network, prior, f.generation)
        f.chain.observeTip(f.network, prior, f.generation)
        assertEquals("rolled_back", f.state("race").state)
        val restored = f.snapshot((0..5).map(f::root), emptyMap())
        assertFalse(restored.utxosByAddress.getValue(f.address).any { it.hash == f.root(0).hash || it.hash == f.root(1).hash })
    }
    @Test fun `dijkstra confirmation requires original parent and child bytes`() = database(era = "dijkstra") { f ->
        val child = F.transaction(listOf(F.input(f.roots[1].txId)), listOf(F.output(coin = 99_800_000)))
        val tx = F.transaction(listOf(F.input(f.roots[0].txId)), listOf(F.output(coin = 99_800_000)), children = listOf(child))
        val signed = f.submission(tx)
        val batch = ReviewedTransactionIdentity.decodeSubmitted(signed, "dijkstra").batch
        val members = (listOf(batch.parent) + batch.children).map {
            ReviewedTransactionIdentity.StoredMember(it.body.bytes(), it.witnesses.bytes(), it.auxiliaryData?.bytes())
        }
        assertTrue(ReviewedTransactionIdentity.matchesStored(7, signed, members))
        val changedWitness = F.map(listOf(F.integer(0) to (byteArrayOf(0xd9.toByte(), 1, 2) +
            F.array(F.array(F.bytes(ByteArray(32) { 1 }), F.bytes(ByteArray(64) { 2 }))))))
        val changedAuxiliary = F.map(emptyList())
        assertFalse(ReviewedTransactionIdentity.matchesStored(7, signed, listOf(members[0].copy(witnesses = changedWitness), members[1])))
        assertFalse(ReviewedTransactionIdentity.matchesStored(7, signed, listOf(members[0].copy(auxiliary = changedAuxiliary), members[1])))
        assertFalse(ReviewedTransactionIdentity.matchesStored(7, signed, listOf(members[0], members[1].copy(witnesses = changedWitness))))
        assertFalse(ReviewedTransactionIdentity.matchesStored(7, signed, listOf(members[0], members[1].copy(auxiliary = changedAuxiliary))))
        assertFalse(ReviewedTransactionIdentity.matchesStored(7, signed, listOf(members[0], members[1].copy(body = F.map(emptyList())))))
        assertFalse(ReviewedTransactionIdentity.matchesStored(7, signed, members.take(1)))
        f.preview("batch", tx)
        f.submit("batch", tx)
        val previousBlock = f.block
        val previousPoint = f.point(previousBlock)
        val parts = CborSpans.parse(tx).array()
        val altered = F.array(parts[0].bytes(), changedWitness, parts[2].bytes(), parts[3].bytes())
        f.confirm(altered)
        assertEquals("submitted", f.state("batch").state)
        f.generation = f.chain.rollback(f.network, previousPoint, f.generation)
        f.chain.observeTip(f.network, previousPoint, f.generation)
        f.block = previousBlock
        f.confirm(tx)
        assertEquals("confirmed", f.state("batch").state)
    }

}
