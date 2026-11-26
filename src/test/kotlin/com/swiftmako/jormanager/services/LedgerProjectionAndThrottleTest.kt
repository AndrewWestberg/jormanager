package com.swiftmako.jormanager.services

import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.ChainPoint
import com.swiftmako.jormanager.model.ChainReadState
import com.swiftmako.jormanager.model.DecodedLedgerBlock
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.LedgerBlockDecoder
import com.swiftmako.jormanager.nodeclient.protocols.fixtures.CatchupFixtures
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.repositories.TransactionMemberRepository
import com.swiftmako.jormanager.spring.config.Configuration
import io.mockk.mockk
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.util.Collections
import javax.sql.DataSource
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.messaging.simp.SimpMessagingTemplate
import tools.jackson.databind.json.JsonMapper

class LedgerProjectionAndThrottleTest {
    private val json = JsonMapper.builder().build()

    private data class SyncRow(
        var network: String,
        var generation: Long,
        var observedSlot: BigDecimal?,
        var observedHash: ByteArray?,
        var completeInclusion: Long?,
        var status: String,
        var reason: String?,
    )

    private data class InclusionRow(
        val id: Long,
        val network: String,
        val hash: ByteArray,
        val slot: BigDecimal,
        val height: BigDecimal,
        val previousHash: ByteArray?,
        val era: Int,
        val header: ByteArray,
        val body: ByteArray,
        var adopted: Boolean,
        var complete: Boolean,
        val source: String = "direct",
    )

    private data class HistoryRow(
        val network: String,
        val account: ByteArray,
        val txId: ByteArray,
        val inclusionHash: ByteArray,
        val slot: BigDecimal,
        val kind: String,
        val amount: BigDecimal?,
        val adopted: Boolean,
        val effective: Boolean,
    )

    private class FakeLedgerStore {
        val statements = Collections.synchronizedList(mutableListOf<String>())
        val syncStates = linkedMapOf<String, SyncRow>()
        val inclusions = linkedMapOf<Long, InclusionRow>()
        val genesisHashes = linkedMapOf<String, ByteArray>()
        val historyRows = mutableListOf<HistoryRow>()
        var nextInclusionId = 100L

        fun clearSql() {
            statements.clear()
        }

        fun dataSource(): DataSource =
            proxy(DataSource::class.java) { method, _ ->
                when (method.name) {
                    "getConnection" -> connection()
                    "unwrap" -> null
                    "isWrapperFor" -> false
                    else -> defaultValue(method.returnType)
                }
            }

        private fun connection(): Connection {
            var autoCommit = false
            var closed = false
            var isolation = Connection.TRANSACTION_READ_COMMITTED
            return proxy(Connection::class.java) { method, args ->
                when (method.name) {
                    "getMetaData" -> metaData()
                    "getAutoCommit" -> autoCommit
                    "setAutoCommit" -> {
                        autoCommit = args[0] as Boolean
                        null
                    }
                    "getTransactionIsolation" -> isolation
                    "setTransactionIsolation" -> {
                        isolation = args[0] as Int
                        null
                    }
                    "isClosed" -> closed
                    "close" -> {
                        closed = true
                        null
                    }
                    "commit", "rollback", "clearWarnings" -> null
                    "isReadOnly" -> false
                    "getCatalog" -> "jormanager"
                    "getSchema" -> "public"
                    "prepareStatement" -> preparedStatement(args[0] as String)
                    else -> defaultValue(method.returnType)
                }
            }
        }

        private fun metaData(): DatabaseMetaData =
            proxy(DatabaseMetaData::class.java) { method, _ ->
                when (method.name) {
                    "getURL" -> "jdbc:postgresql://localhost:5432/jormanager_test"
                    "getDatabaseProductName" -> "PostgreSQL"
                    "getDatabaseProductVersion" -> "16.4"
                    "getDatabaseMajorVersion" -> 16
                    "getDatabaseMinorVersion" -> 4
                    "getDriverName" -> "PostgreSQL JDBC Driver"
                    "getDriverVersion" -> "42.7.14"
                    "getJDBCMajorVersion" -> 4
                    "getJDBCMinorVersion" -> 2
                    "getIdentifierQuoteString" -> "\""
                    "getExtraNameCharacters" -> ""
                    "getSQLKeywords" -> ""
                    "getCatalogSeparator" -> "."
                    "getSearchStringEscape" -> "\\"
                    "storesLowerCaseIdentifiers" -> true
                    "storesLowerCaseQuotedIdentifiers" -> false
                    "storesMixedCaseIdentifiers" -> false
                    "storesMixedCaseQuotedIdentifiers" -> true
                    "storesUpperCaseIdentifiers" -> false
                    "storesUpperCaseQuotedIdentifiers" -> false
                    "supportsMixedCaseIdentifiers" -> false
                    "supportsMixedCaseQuotedIdentifiers" -> true
                    "supportsBatchUpdates" -> true
                    "supportsTransactions" -> true
                    "supportsSelectForUpdate" -> true
                    "supportsGetGeneratedKeys" -> true
                    "getDefaultTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED
                    else -> if (method.returnType == String::class.java) "" else defaultValue(method.returnType)
                }
            }

        private fun preparedStatement(sql: String): PreparedStatement {
            val params = linkedMapOf<Int, Any?>()
            var generatedKey: Long? = null
            return proxy(PreparedStatement::class.java) { method, args ->
                when (method.name) {
                    "setNull" -> {
                        params[args[0] as Int] = null
                        null
                    }
                    "setObject", "setString", "setLong", "setInt", "setBigDecimal", "setBoolean", "setBytes" -> {
                        params[args[0] as Int] = args[1]
                        null
                    }
                    "clearParameters" -> {
                        params.clear()
                        null
                    }
                    "executeQuery" -> {
                        statements += sql
                        resultSet(executeSelect(sql, params))
                    }
                    "executeUpdate" -> {
                        statements += sql
                        val count = executeMutation(sql, params) { generatedKey = it }
                        count
                    }
                    "getGeneratedKeys" -> {
                        val key = generatedKey
                        resultSet(if (key != null) listOf(listOf(key)) else emptyList())
                    }
                    "execute" -> {
                        statements += sql
                        if (sql.trimStart().startsWith("SELECT", ignoreCase = true)) {
                            true
                        } else {
                            executeMutation(sql, params) { generatedKey = it }
                            false
                        }
                    }
                    "close" -> null
                    else -> defaultValue(method.returnType)
                }
            }
        }

        private fun parseProjectedColumns(sql: String): List<String> {
            val selectBody = Regex("(?is)^\\s*SELECT\\s+(.*?)\\s+FROM\\s+").find(sql)?.groupValues?.get(1)
                ?: error("Unable to parse SELECT clause: $sql")
            return selectBody.split(',').map { it.trim().removeSurrounding("\"") }
        }

        private fun executeSelect(
            sql: String,
            params: Map<Int, Any?>,
        ): List<List<Any?>> {
            val normalized = sql.replace("\"", "")
            val columns = parseProjectedColumns(normalized)
            return when {
                normalized.contains("FROM account_effects", ignoreCase = true) -> {
                    val network = params[1] as String
                    val account = params[2] as ByteArray
                    historyRows
                        .filter { it.network == network && it.account.contentEquals(account) }
                        .sortedByDescending { it.slot }
                        .map { row ->
                            columns.map { col ->
                                when (col) {
                                    "transaction_members.tx_id" -> row.txId
                                    "chain_inclusions.header_hash" -> row.inclusionHash
                                    "chain_inclusions.slot" -> row.slot
                                    "account_effects.kind" -> row.kind
                                    "account_effects.amount" -> row.amount
                                    "chain_inclusions.adopted" -> row.adopted
                                    "account_effects.effective" -> row.effective
                                    else -> error("Unexpected projected column '$col' in accountHistory SQL: $sql")
                                }
                            }
                        }
                }
                normalized.contains("FROM sync_states", ignoreCase = true) -> {
                    val network = params[1] as String
                    val state = syncStates[network] ?: return emptyList()
                    listOf(
                        columns.map { col ->
                            when (col.substringAfterLast('.')) {
                                "network_key" -> state.network
                                "generation" -> state.generation
                                "observed_slot" -> state.observedSlot
                                "observed_hash" -> state.observedHash
                                "complete_inclusion_id" -> state.completeInclusion
                                "status" -> state.status
                                "reason" -> state.reason
                                else -> error("Unexpected sync_states column '$col' in SQL: $sql")
                            }
                        },
                    )
                }
                normalized.contains("FROM network_genesis", ignoreCase = true) -> {
                    val network = params[1] as String
                    val hash = genesisHashes[network] ?: return emptyList()
                    listOf(
                        columns.map { col ->
                            when (col.substringAfterLast('.')) {
                                "network_key" -> network
                                "genesis_hash" -> hash
                                "seeded" -> true
                                else -> error("Unexpected network_genesis column '$col' in SQL: $sql")
                            }
                        },
                    )
                }
                normalized.contains("FROM chain_inclusions", ignoreCase = true) -> {
                    val matched = when {
                        normalized.contains("WHERE chain_inclusions.id = ?", ignoreCase = true) -> {
                            val id = (params[1] as Number).toLong()
                            listOfNotNull(inclusions[id])
                        }
                        normalized.contains("chain_inclusions.slot = ?", ignoreCase = true) -> {
                            val network = params[1] as String
                            val hash = params[2] as ByteArray
                            val slot = params[3] as BigDecimal
                            val adoptedOnly = normalized.contains("chain_inclusions.adopted = TRUE", ignoreCase = true)
                            inclusions.values.filter {
                                it.network == network &&
                                    it.hash.contentEquals(hash) &&
                                    it.slot.compareTo(slot) == 0 &&
                                    (!adoptedOnly || it.adopted)
                            }
                        }
                        normalized.contains("chain_inclusions.header_hash = ?", ignoreCase = true) -> {
                            val network = params[1] as String
                            val hash = params[2] as ByteArray
                            val adoptedOnly = normalized.contains("chain_inclusions.adopted = TRUE", ignoreCase = true)
                            inclusions.values.filter {
                                it.network == network &&
                                    it.hash.contentEquals(hash) &&
                                    (!adoptedOnly || it.adopted)
                            }
                        }
                        else -> error("Unhandled chain_inclusions SELECT: $sql")
                    }
                    matched.map { row ->
                        columns.map { col ->
                            when (col.substringAfterLast('.')) {
                                "id" -> row.id
                                "network_key" -> row.network
                                "header_hash" -> row.hash
                                "slot" -> row.slot
                                "height" -> row.height
                                "previous_hash" -> row.previousHash
                                "era" -> row.era
                                "raw_header" -> row.header
                                "raw_body" -> row.body
                                "adopted" -> row.adopted
                                "complete" -> row.complete
                                "source" -> row.source
                                else -> error("Unexpected chain_inclusions column '$col' in SQL: $sql")
                            }
                        }
                    }
                }
                normalized.contains("FROM output_occurrences", ignoreCase = true) ||
                    normalized.contains("FROM reversible_changes", ignoreCase = true) ||
                    normalized.contains("FROM leios_announcements", ignoreCase = true) -> emptyList()
                normalized.contains("INSERT INTO chain_inclusions", ignoreCase = true) -> {
                    val id = ++nextInclusionId
                    insertInclusion(id, params)
                    listOf(listOf(id))
                }
                else -> error("Unhandled SELECT in FakeLedgerStore: $sql")
            }
        }

        private fun insertInclusion(
            id: Long,
            params: Map<Int, Any?>,
        ) {
            inclusions[id] = InclusionRow(
                id = id,
                network = params[1] as String,
                hash = params[2] as ByteArray,
                slot = params[3] as BigDecimal,
                height = params[4] as BigDecimal,
                previousHash = params[5] as ByteArray?,
                era = (params[6] as Number).toInt(),
                header = params[7] as ByteArray,
                body = params[8] as ByteArray,
                adopted = params[9] as Boolean,
                complete = params[10] as Boolean,
                source = params[11] as String,
            )
        }

        private fun executeMutation(
            sql: String,
            params: Map<Int, Any?>,
            onGeneratedKey: (Long) -> Unit = {},
        ): Int {
            val normalized = sql.replace("\"", "")
            return when {
                normalized.startsWith("INSERT INTO sync_states", ignoreCase = true) -> {
                    val network = params[1] as String
                    if (network in syncStates) {
                        0
                    } else {
                        syncStates[network] = SyncRow(
                            network = network,
                            generation = (params[2] as Number).toLong(),
                            observedSlot = params[5] as BigDecimal?,
                            observedHash = params[6] as ByteArray?,
                            completeInclusion = (params[7] as Number?)?.toLong(),
                            status = params[3] as String,
                            reason = params[4] as String?,
                        )
                        1
                    }
                }
                normalized.startsWith("UPDATE sync_states", ignoreCase = true) -> {
                    val setClause = Regex("(?is)UPDATE\\s+sync_states\\s+SET\\s+(.*?)\\s+WHERE").find(normalized)?.groupValues?.get(1)
                        ?: error("Unable to parse UPDATE sync_states: $sql")
                    val assignments = setClause.split(',').map { it.substringBefore('=').trim() }
                    val network = params[assignments.size + 1] as String
                    val row = requireNotNull(syncStates[network]) { "Missing sync_states row for $network" }
                    assignments.forEachIndexed { index, col ->
                        val value = params[index + 1]
                        when (col) {
                            "generation" -> row.generation = (value as Number).toLong()
                            "observed_slot" -> row.observedSlot = value as BigDecimal?
                            "observed_hash" -> row.observedHash = value as ByteArray?
                            "complete_inclusion_id" -> row.completeInclusion = (value as Number?)?.toLong()
                            "status" -> row.status = value as String
                            "reason" -> row.reason = value as String?
                            else -> error("Unexpected sync_states assignment '$col' in $sql")
                        }
                    }
                    1
                }
                normalized.startsWith("INSERT INTO chain_inclusions", ignoreCase = true) -> {
                    val id = ++nextInclusionId
                    insertInclusion(id, params)
                    onGeneratedKey(id)
                    1
                }
                normalized.startsWith("UPDATE chain_inclusions", ignoreCase = true) -> {
                    val setClause = Regex("(?is)UPDATE\\s+chain_inclusions\\s+SET\\s+(.*?)\\s+WHERE").find(normalized)?.groupValues?.get(1)
                        ?: error("Unable to parse UPDATE chain_inclusions: $sql")
                    val assignments = setClause.split(',').map { it.substringBefore('=').trim() }
                    val id = (params[assignments.size + 1] as Number).toLong()
                    val row = requireNotNull(inclusions[id]) { "Missing chain_inclusions row $id" }
                    assignments.forEachIndexed { index, col ->
                        val value = params[index + 1]
                        when (col) {
                            "complete" -> row.complete = value as Boolean
                            "adopted" -> row.adopted = value as Boolean
                            else -> error("Unexpected chain_inclusions assignment '$col' in $sql")
                        }
                    }
                    1
                }
                normalized.startsWith("UPDATE ledger_utxos", ignoreCase = true) ||
                    normalized.startsWith("DELETE FROM reversible_changes", ignoreCase = true) -> 0
                else -> error("Unhandled mutation in FakeLedgerStore: $sql")
            }
        }

        private fun resultSet(rows: List<List<Any?>>): ResultSet {
            var cursor = -1
            var lastNull = false
            val meta = proxy(ResultSetMetaData::class.java) { method, _ ->
                when (method.name) {
                    "getColumnCount" -> rows.firstOrNull()?.size ?: 0
                    "getColumnName", "getColumnLabel" -> "id"
                    else -> if (method.returnType == String::class.java) "" else defaultValue(method.returnType)
                }
            }
            return proxy(ResultSet::class.java) { method, args ->
                when (method.name) {
                    "next" -> {
                        cursor++
                        cursor < rows.size
                    }
                    "wasNull" -> lastNull
                    "close" -> null
                    "getMetaData" -> meta
                    "findColumn" -> 1
                    "getObject" -> {
                        val value = rows[cursor][(args[0] as Int) - 1]
                        lastNull = value == null
                        value
                    }
                    "getString" -> {
                        val value = rows[cursor][(args[0] as Int) - 1] as String?
                        lastNull = value == null
                        value
                    }
                    "getBytes" -> {
                        val value = rows[cursor][(args[0] as Int) - 1] as ByteArray?
                        lastNull = value == null
                        value
                    }
                    "getBigDecimal" -> {
                        val value = rows[cursor][(args[0] as Int) - 1] as BigDecimal?
                        lastNull = value == null
                        value
                    }
                    "getLong" -> {
                        val value = rows[cursor][(args[0] as Int) - 1] as Number?
                        lastNull = value == null
                        value?.toLong() ?: 0L
                    }
                    "getInt" -> {
                        val value = rows[cursor][(args[0] as Int) - 1] as Number?
                        lastNull = value == null
                        value?.toInt() ?: 0
                    }
                    "getBoolean" -> {
                        val value = rows[cursor][(args[0] as Int) - 1] as Boolean?
                        lastNull = value == null
                        value ?: false
                    }
                    else -> defaultValue(method.returnType)
                }
            }
        }

        private fun <T> proxy(
            type: Class<T>,
            handler: (java.lang.reflect.Method, Array<Any?>) -> Any?,
        ): T =
            type.cast(
                Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, rawArgs ->
                    when (method.name) {
                        "toString" -> "Fake${type.simpleName}"
                        "hashCode" -> System.identityHashCode(this)
                        "equals" -> false
                        else -> handler(method, rawArgs ?: emptyArray())
                    }
                },
            )

        private fun defaultValue(returnType: Class<*>): Any? =
            when (returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Double.TYPE -> 0.0
                java.lang.Float.TYPE -> 0f
                else -> null
            }
    }

    private fun <T> withFakeDatabase(block: (FakeLedgerStore, ChainIngestionCoordinator, SimpMessagingTemplate, kotlinx.coroutines.flow.MutableStateFlow<Long?>) -> T): T {
        val store = FakeLedgerStore()
        val db = Database.connect(store.dataSource())
        val messages = mock(SimpMessagingTemplate::class.java)
        val refresh = Configuration().getRefreshWalletChannel()
        val coordinator = ChainIngestionCoordinator(BatchEffectProjector(json), messages, refresh, mockk<LedgerDao>(relaxed = true))
        try {
            return block(store, coordinator, messages, refresh)
        } finally {
            TransactionManager.closeAndUnregister(db)
        }
    }

    @Test
    fun `accountHistory projects only scalar and hash columns with exact output parity`() =
        withFakeDatabase { store, coordinator, _, _ ->
            val network = "history-net"
            val accountHex = CatchupFixtures.rewardAddress.toHexString()
            val completeHash = ByteArray(32) { 0x11 }
            val rolledBackHash = ByteArray(32) { 0x22 }
            val earlierHash = ByteArray(32) { 0x33 }
            store.inclusions[1L] = InclusionRow(
                id = 1L,
                network = network,
                hash = completeHash,
                slot = BigDecimal.valueOf(250),
                height = BigDecimal.valueOf(25),
                previousHash = earlierHash,
                era = 7,
                header = ByteArray(512) { 0x7a },
                body = ByteArray(4096) { 0x7b },
                adopted = true,
                complete = true,
            )
            store.syncStates[network] = SyncRow(
                network = network,
                generation = 3L,
                observedSlot = BigDecimal.valueOf(250),
                observedHash = completeHash,
                completeInclusion = 1L,
                status = "complete",
                reason = null,
            )
            val tx1 = ByteArray(32) { 0xa1.toByte() }
            val tx2 = ByteArray(32) { 0xa2.toByte() }
            val tx3 = ByteArray(32) { 0xa3.toByte() }
            store.historyRows += listOf(
                HistoryRow(network, accountHex.hexToByteArray(), tx3, earlierHash, BigDecimal.valueOf(120), "STARTING_INTERVAL", null, adopted = true, effective = false),
                HistoryRow(network, accountHex.hexToByteArray(), tx1, completeHash, BigDecimal.valueOf(250), "DIRECT_DEPOSIT", BigDecimal.valueOf(1_500_000), adopted = true, effective = true),
                HistoryRow(network, accountHex.hexToByteArray(), tx2, rolledBackHash, BigDecimal.valueOf(180), "WITHDRAWAL", BigDecimal.valueOf(500_000), adopted = false, effective = true),
                HistoryRow("other-net", accountHex.hexToByteArray(), ByteArray(32) { 0xff.toByte() }, completeHash, BigDecimal.valueOf(999), "DIRECT_DEPOSIT", BigDecimal.TEN, adopted = true, effective = true),
            )

            val repository = TransactionMemberRepository(coordinator)
            store.clearSql()
            val response = repository.accountHistory("req-42", network, accountHex)

            assertEquals("req-42", response["requestId"])
            assertEquals(network, response["networkKey"])
            assertEquals(accountHex, response["accountAddressHex"])
            assertNull(response["balance"])
            assertEquals(
                "A point-matched node account snapshot is required; transaction credits are not reward balance",
                response["balanceReason"],
            )
            assertEquals(
                ChainReadState(
                    networkKey = network,
                    status = "complete",
                    reason = null,
                    observedTip = ChainPoint("250", completeHash.toHexString()),
                    lastCompletePoint = ChainPoint("250", completeHash.toHexString()),
                    walletReady = false,
                    generation = "3",
                ),
                response["readState"],
            )
            assertEquals(
                listOf(
                    mapOf(
                        "txId" to tx1.toHexString(),
                        "inclusionHash" to completeHash.toHexString(),
                        "slot" to "250",
                        "kind" to "DIRECT_DEPOSIT",
                        "amount" to "1500000",
                        "effective" to true,
                    ),
                    mapOf(
                        "txId" to tx2.toHexString(),
                        "inclusionHash" to rolledBackHash.toHexString(),
                        "slot" to "180",
                        "kind" to "WITHDRAWAL",
                        "amount" to "500000",
                        "effective" to false,
                    ),
                    mapOf(
                        "txId" to tx3.toHexString(),
                        "inclusionHash" to earlierHash.toHexString(),
                        "slot" to "120",
                        "kind" to "STARTING_INTERVAL",
                        "amount" to null,
                        "effective" to false,
                    ),
                ),
                response["history"],
            )

            val joinQuery = store.statements.single { it.contains("account_effects", ignoreCase = true) }
            assertTrue(joinQuery.contains("ORDER BY", ignoreCase = true) && joinQuery.contains("DESC", ignoreCase = true), joinQuery)
            val forbiddenBlobs = listOf("raw_header", "raw_body", "raw_included", "raw_witnesses", "raw_auxiliary")
            store.statements.forEach { sql ->
                forbiddenBlobs.forEach { blob ->
                    assertFalse(sql.contains(blob, ignoreCase = true), "Unexpected blob column '$blob' in SQL: $sql")
                }
            }

            assertThrows(IllegalArgumentException::class.java) {
                repository.accountHistory("req-bad", network, "invalid")
            }
            Unit
        }

    @Test
    fun `status walletIndexState observeTip and rollback project only metadata columns`() =
        withFakeDatabase { store, coordinator, _, _ ->
            val network = "meta-net"
            val hash1 = ByteArray(32) { 0x01 }
            val hash2 = ByteArray(32) { 0x02 }
            val hash3 = ByteArray(32) { 0x03 }
            store.inclusions[10L] = InclusionRow(10L, network, hash1, BigDecimal.valueOf(10), BigDecimal.valueOf(1), null, 7, ByteArray(256) { 1 }, ByteArray(512) { 2 }, adopted = true, complete = true)
            store.inclusions[11L] = InclusionRow(11L, network, hash2, BigDecimal.valueOf(20), BigDecimal.valueOf(2), hash1, 7, ByteArray(256) { 3 }, ByteArray(512) { 4 }, adopted = true, complete = true)
            store.inclusions[12L] = InclusionRow(12L, network, hash3, BigDecimal.valueOf(30), BigDecimal.valueOf(3), hash2, 7, ByteArray(256) { 5 }, ByteArray(512) { 6 }, adopted = true, complete = true)
            store.syncStates[network] = SyncRow(network, 5L, BigDecimal.valueOf(30), hash3, 12L, "complete", null)

            store.clearSql()
            val status = coordinator.status(network)
            assertEquals(ChainPoint("30", hash3.toHexString()), status.lastCompletePoint)

            val indexState = coordinator.walletIndexState(network)
            assertEquals(3L, indexState.lastCompleteHeight)
            assertEquals(ChainPoint("30", hash3.toHexString()), indexState.readState.lastCompletePoint)
            val indexInclusionQueries = store.statements.filter { it.contains("FROM chain_inclusions", ignoreCase = true) }
            // status() ran 1 chain_inclusions query and walletIndexState() ran 1 chain_inclusions query
            assertEquals(2, indexInclusionQueries.size, "Expected single chain_inclusions lookup in walletIndexState(): ${store.statements}")

            coordinator.observeTip(network, ChainPoint("30", hash3.toHexString()), 5L)
            assertTrue(coordinator.status(network).walletReady)

            store.clearSql()
            val nextGen = coordinator.rollback(network, ChainPoint("10", hash1.toHexString()), 5L)
            assertEquals(6L, nextGen)
            assertEquals(ChainPoint("10", hash1.toHexString()), coordinator.status(network).lastCompletePoint)
            assertFalse(store.inclusions.getValue(12L).adopted)
            assertFalse(store.inclusions.getValue(11L).adopted)
            assertTrue(store.inclusions.getValue(10L).adopted)

            store.statements.forEach { sql ->
                assertFalse(sql.contains("raw_header", ignoreCase = true), "Unexpected raw_header in SQL: $sql")
                assertFalse(sql.contains("raw_body", ignoreCase = true), "Unexpected raw_body in SQL: $sql")
            }
            val rollbackSelects = store.statements
                .filter { it.trimStart().startsWith("SELECT", ignoreCase = true) && it.contains("FROM chain_inclusions", ignoreCase = true) }
                .map { it.replace("\"", "") }
            assertTrue(rollbackSelects.any { it.startsWith("SELECT chain_inclusions.id FROM chain_inclusions") && it.contains("chain_inclusions.slot = ?") }, rollbackSelects.toString())
            assertTrue(
                rollbackSelects.any {
                    it.startsWith("SELECT chain_inclusions.id, chain_inclusions.previous_hash FROM chain_inclusions WHERE chain_inclusions.id = ?")
                },
                rollbackSelects.toString(),
            )
            assertTrue(
                rollbackSelects.any {
                    it.startsWith("SELECT chain_inclusions.id, chain_inclusions.previous_hash FROM chain_inclusions WHERE (chain_inclusions.network_key = ?) AND (chain_inclusions.header_hash = ?)")
                },
                rollbackSelects.toString(),
            )
        }

    @Test
    fun `publish checks 500ms throttle before status on unready syncing and bypasses when ready`() =
        withFakeDatabase { store, coordinator, messages, refresh ->
            val network = "throttle-net"
            store.genesisHashes[network] = CatchupFixtures.genesisHash.hexToByteArray()
            var nowNs = 1_000_000_000L
            coordinator.clock = { nowNs }

            val firstBytes = CatchupFixtures.block(7, 1)
            val secondBytes = CatchupFixtures.block(7, 2, previous = CatchupFixtures.blockHash(firstBytes))
            val thirdBytes = CatchupFixtures.block(7, 3, previous = CatchupFixtures.blockHash(secondBytes))
            val fourthBytes = CatchupFixtures.block(7, 4, previous = CatchupFixtures.blockHash(thirdBytes))
            val first = LedgerBlockDecoder.decode(7, firstBytes)
            val second = LedgerBlockDecoder.decode(7, secondBytes)
            val third = LedgerBlockDecoder.decode(7, thirdBytes)
            val fourth = LedgerBlockDecoder.decode(7, fourthBytes)

            fun point(block: DecodedLedgerBlock) = ChainPoint(requireNotNull(block.headerDetails).slot.toString(), block.header.hash256().toHexString())

            // beginSession forces initial publish at t = 1.000s
            val gen = coordinator.beginSession(network)
            verify(messages, times(1)).convertAndSend(org.mockito.ArgumentMatchers.eq("/topic/messages"), org.mockito.ArgumentMatchers.any(Any::class.java))
            clearInvocations(messages)

            // 100ms later: unready "syncing" observeTip is throttled BEFORE calling status()
            nowNs += 100_000_000L
            store.clearSql()
            coordinator.observeTip(network, point(second), gen)
            verifyNoInteractions(messages)
            // Only 1 SyncStatesTable FOR UPDATE lock (from observeTip's own lock()), zero post-commit status() locks
            assertEquals(1, store.statements.count { it.contains("FOR UPDATE", ignoreCase = true) }, "Expected status() to be skipped when throttled: ${store.statements}")

            // 200ms after beginSession: unready "syncing" adopt(first) is throttled BEFORE calling status()
            nowNs += 100_000_000L
            store.clearSql()
            coordinator.adopt(network, gen, 7, first, point(first))
            verifyNoInteractions(messages)
            assertNull(refresh.value)
            assertEquals(1, store.statements.count { it.contains("FOR UPDATE", ignoreCase = true) }, "Expected status() to be skipped on throttled adopt(): ${store.statements}")

            // 500ms after beginSession: unready "syncing" observeTip elapses throttle window and publishes
            nowNs += 300_000_000L
            store.clearSql()
            coordinator.observeTip(network, point(second), gen)
            verify(messages, times(1)).convertAndSend(org.mockito.ArgumentMatchers.eq("/topic/messages"), org.mockito.ArgumentMatchers.any(Any::class.java))
            assertEquals(2, store.statements.count { it.contains("FOR UPDATE", ignoreCase = true) })
            clearInvocations(messages)

            // Only 5ms later: adopt(second) catches up to tip -> marks walletReady, bypasses 500ms throttle, refreshes wallet
            nowNs += 5_000_000L
            coordinator.adopt(network, gen, 7, second, point(second))
            verify(messages, times(1)).convertAndSend(org.mockito.ArgumentMatchers.eq("/topic/messages"), org.mockito.ArgumentMatchers.any(Any::class.java))
            assertEquals(1L, refresh.value)
            clearInvocations(messages)

            // 5ms later: tip advances 2 blocks ahead (status becomes "syncing", but walletReady remains true)
            nowNs += 5_000_000L
            coordinator.observeTip(network, point(fourth), gen)
            verify(messages, times(1)).convertAndSend(org.mockito.ArgumentMatchers.eq("/topic/messages"), org.mockito.ArgumentMatchers.any(Any::class.java))
            clearInvocations(messages)

            // 5ms later: adopt(third) commits ready "syncing" -> MUST bypass 500ms throttle and trigger refreshWallet() immediately
            nowNs += 5_000_000L
            coordinator.adopt(network, gen, 7, third, point(third))
            val captor = ArgumentCaptor.forClass(Any::class.java)
            verify(messages, times(1)).convertAndSend(org.mockito.ArgumentMatchers.eq("/topic/messages"), captor.capture())
            val payload = (captor.value as SocketResponse.Success<*>).data as ChainReadState
            assertEquals("syncing", payload.status)
            assertTrue(payload.walletReady)
            assertEquals(2L, refresh.value)
        }

    @Test
    fun `adopt still loads header and body bytes to reject equivocating block content`() =
        withFakeDatabase { store, coordinator, _, _ ->
            val network = "equivocation-net"
            store.genesisHashes[network] = CatchupFixtures.genesisHash.hexToByteArray()
            val gen = coordinator.beginSession(network)

            val blockBytes = CatchupFixtures.block(7, 1)
            val decoded = LedgerBlockDecoder.decode(7, blockBytes)
            val blockPoint = ChainPoint(requireNotNull(decoded.headerDetails).slot.toString(), decoded.header.hash256().toHexString())

            // Pre-populate an inclusion with the same (network, header_hash) but conflicting body bytes
            store.inclusions[1L] = InclusionRow(
                id = 1L,
                network = network,
                hash = decoded.header.hash256(),
                slot = requireNotNull(decoded.headerDetails).slot.toBigDecimal(),
                height = requireNotNull(decoded.headerDetails).blockNumber.toBigDecimal(),
                previousHash = null,
                era = 7,
                header = decoded.header.bytes(),
                body = decoded.encoded.copyOf().also { it[0] = (it[0].toInt() xor 0xff).toByte() },
                adopted = false,
                complete = false,
            )

            store.clearSql()
            val error = assertThrows(IllegalStateException::class.java) {
                coordinator.adopt(network, gen, 7, decoded, blockPoint)
            }
            assertEquals("Conflicting bytes for existing block identity", error.message)
            val equivocationSelect = store.statements.single { it.contains("FROM chain_inclusions", ignoreCase = true) }
            assertTrue(equivocationSelect.contains("raw_header", ignoreCase = true), equivocationSelect)
            assertTrue(equivocationSelect.contains("raw_body", ignoreCase = true), equivocationSelect)
        }
}
