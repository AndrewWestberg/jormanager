package com.swiftmako.jormanager.services

import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.LedgerRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.tables.ChainInclusionsTable
import com.swiftmako.jormanager.tables.SyncStatesTable
import io.github.oshai.kotlinlogging.KotlinLogging
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

@Service
class LedgerPruningService(
    private val dataSource: DataSource,
    private val nodeRepository: NodeRepository,
    private val fileRepository: FileRepository,
    private val cardanoRepository: CardanoRepository,
    private val shelleyGenesisAdapter: JsonAdapter<GenesisShelley>,
    @param:Value("\${jormanager.ledger.prune.inactivity-minutes:$DEFAULT_INACTIVITY_MINUTES}")
    private val inactivityMinutes: Long = DEFAULT_INACTIVITY_MINUTES,
    @param:Value("\${jormanager.ledger.prune.batch-size:${LedgerRepository.DEFAULT_PRUNE_BATCH_SIZE}}")
    private val batchSize: Int = LedgerRepository.DEFAULT_PRUNE_BATCH_SIZE,
    @param:Value("\${jormanager.ledger.prune.vacuum-enabled:true}")
    private val vacuumEnabled: Boolean = true,
) {
    private val log = KotlinLogging.logger("LedgerPruningService")
    private val running = AtomicBoolean(false)

    data class SweepResult(
        val skipped: Boolean,
        val networksEvaluated: Int,
        val spentUtxosDeleted: Long,
        val orphanedAddressesDeleted: Long,
        val vacuumCompleted: Boolean,
        val durationMillis: Long,
    )

    init {
        require(inactivityMinutes >= MIN_INACTIVITY_MINUTES) {
            "inactivityMinutes ($inactivityMinutes) must be at least $MIN_INACTIVITY_MINUTES minutes"
        }
        require(batchSize > 0) {
            "batchSize ($batchSize) must be positive"
        }
    }

    fun pruneOnce(onBatchCompleted: () -> Unit = {}): SweepResult {
        if (!running.compareAndSet(false, true)) {
            log.debug { "Skipping ledger pruning sweep because another sweep is already in progress" }
            return SweepResult(
                skipped = true,
                networksEvaluated = 0,
                spentUtxosDeleted = 0L,
                orphanedAddressesDeleted = 0L,
                vacuumCompleted = false,
                durationMillis = 0L,
            )
        }
        val startedNanos = System.nanoTime()
        return try {
            val slotLengthsByNetwork = resolveNetworkSlotLengths()
            val syncedNetworks = readSyncedNetworkSlots()
            var spentDeleted = 0L
            for ((network, completeSlot) in syncedNetworks) {
                onBatchCompleted()
                val slotLengthSeconds = slotLengthsByNetwork[network] ?: DEFAULT_SLOT_LENGTH_SECONDS
                val beforeSlot = beforeSlotFor(completeSlot, inactivityMinutes, slotLengthSeconds) ?: continue
                spentDeleted += LedgerRepository.pruneSpentUtxos(
                    network = network,
                    beforeSlot = beforeSlot,
                    batchSize = batchSize,
                    onBatchCompleted = onBatchCompleted,
                )
            }
            onBatchCompleted()
            val orphansDeleted = LedgerRepository.pruneOrphanedAddresses(
                batchSize = batchSize,
                onBatchCompleted = onBatchCompleted,
            )
            val shouldVacuum = vacuumEnabled && (spentDeleted > 0L || orphansDeleted > 0L)
            val vacuumed = if (shouldVacuum) {
                onBatchCompleted()
                vacuumLedgerTables()
            } else {
                false
            }
            val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L
            if (spentDeleted > 0L || orphansDeleted > 0L) {
                log.info {
                    "Ledger pruning sweep deleted $spentDeleted spent UTxOs and $orphansDeleted orphaned addresses across ${syncedNetworks.size} network(s) in ${elapsedMillis}ms (vacuum=$vacuumed)"
                }
            } else {
                log.debug {
                    "Ledger pruning sweep found no eligible records across ${syncedNetworks.size} network(s) in ${elapsedMillis}ms"
                }
            }
            SweepResult(
                skipped = false,
                networksEvaluated = syncedNetworks.size,
                spentUtxosDeleted = spentDeleted,
                orphanedAddressesDeleted = orphansDeleted,
                vacuumCompleted = vacuumed,
                durationMillis = elapsedMillis,
            )
        } finally {
            running.set(false)
        }
    }

    private fun resolveNetworkSlotLengths(): Map<String, Long> {
        val result = LinkedHashMap<String, Long>()
        val nodes = try {
            nodeRepository.findAll().filterNot { it.isDeleted }.sortedByDescending { it.isDefault }
        } catch (e: Exception) {
            log.warn(e) { "Unable to enumerate nodes for slot length resolution; falling back to ${DEFAULT_SLOT_LENGTH_SECONDS}s" }
            return emptyMap()
        }
        for (node in nodes) {
            try {
                val key = cardanoRepository.networkKey(node)
                if (key in result) continue
                val genesisFile = fileRepository.findByIdOrNull(node.genesisShelleyFileId) ?: continue
                val genesis = shelleyGenesisAdapter.fromJson(genesisFile.content) ?: continue
                if (genesis.slotLength > 0L) {
                    result[key] = genesis.slotLength
                }
            } catch (e: Exception) {
                log.warn(e) { "Unable to resolve Shelley slotLength for node ${node.id}; using default if needed" }
            }
        }
        return result
    }

    private fun readSyncedNetworkSlots(): List<Pair<String, BigDecimal>> =
        transaction {
            SyncStatesTable
                .join(
                    otherTable = ChainInclusionsTable,
                    joinType = JoinType.INNER,
                    onColumn = SyncStatesTable.completeInclusion,
                    otherColumn = ChainInclusionsTable.id,
                ).select(SyncStatesTable.network, ChainInclusionsTable.slot)
                .map { it[SyncStatesTable.network] to it[ChainInclusionsTable.slot] }
        }

    internal fun vacuumLedgerTables(): Boolean =
        try {
            dataSource.connection.use { connection ->
                val previousAutoCommit = connection.autoCommit
                try {
                    if (!previousAutoCommit) {
                        connection.rollback()
                        connection.autoCommit = true
                    }
                    connection.createStatement().use { statement ->
                        statement.execute("VACUUM (ANALYZE) ledger_utxo_assets")
                        statement.execute("VACUUM (ANALYZE) ledger_utxos")
                        statement.execute("VACUUM (ANALYZE) ledger")
                    }
                    true
                } finally {
                    if (connection.autoCommit != previousAutoCommit) {
                        connection.autoCommit = previousAutoCommit
                    }
                }
            }
        } catch (e: Exception) {
            log.warn(e) { "Non-blocking VACUUM (ANALYZE) after ledger pruning did not complete" }
            false
        }

    companion object {
        const val MIN_INACTIVITY_MINUTES = 30L
        const val DEFAULT_INACTIVITY_MINUTES = 30L
        const val DEFAULT_SLOT_LENGTH_SECONDS = 1L

        fun beforeSlotFor(
            completeSlot: BigDecimal,
            inactivityMinutes: Long = DEFAULT_INACTIVITY_MINUTES,
            slotLengthSeconds: Long = DEFAULT_SLOT_LENGTH_SECONDS,
        ): BigDecimal? {
            require(inactivityMinutes >= MIN_INACTIVITY_MINUTES) {
                "inactivityMinutes ($inactivityMinutes) must be at least $MIN_INACTIVITY_MINUTES minutes"
            }
            val effectiveSlotLength = slotLengthSeconds.coerceAtLeast(1L)
            val inactivitySeconds = Math.multiplyExact(inactivityMinutes, 60L)
            val inactivitySlots = BigDecimal.valueOf((inactivitySeconds + effectiveSlotLength - 1L) / effectiveSlotLength)
            if (completeSlot < inactivitySlots) {
                return null
            }
            return completeSlot.subtract(inactivitySlots).add(BigDecimal.ONE)
        }
    }
}
