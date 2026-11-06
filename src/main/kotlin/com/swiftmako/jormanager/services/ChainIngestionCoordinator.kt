package com.swiftmako.jormanager.services

import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.ChainPoint
import com.swiftmako.jormanager.model.ChainReadState
import com.swiftmako.jormanager.model.DecodedLedgerBlock
import com.swiftmako.jormanager.model.DecodedTransactionBatch
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.DecodedGenesisOutput
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.LeiosContentResolver
import com.swiftmako.jormanager.repositories.LedgerRepository
import com.swiftmako.jormanager.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Service
import org.springframework.beans.factory.annotation.Qualifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/** The only chain-effect writer. Row locks serialize each network, including across application processes. */
@Service
class ChainIngestionCoordinator(
    private val projector: BatchEffectProjector,
    private val messages: SimpMessagingTemplate,
    @param:Qualifier("refreshWalletChannel") private val walletRefresh: MutableStateFlow<Long>,
) {
    private val lastProgressNotification = ConcurrentHashMap<String, Long>()
    private val log = LoggerFactory.getLogger(ChainIngestionCoordinator::class.java)
    private val nextReferenceId = org.jetbrains.exposed.v1.core.Sequence("hibernate_sequence").nextLongVal()
    fun registerNetwork(networkKey: String, genesisHash: String, genesisOutputs: List<DecodedGenesisOutput> = emptyList()) {
        require(networkKey.isNotBlank() && genesisHash.matches(Regex("[0-9a-f]{64}")))
        val restored = transaction {
            lock(networkKey)
            NetworkGenesisTable.insertIgnore { it[network] = networkKey; it[hash] = genesisHash.hexToByteArray(); it[seeded] = false }
            val row = NetworkGenesisTable.selectAll().where { NetworkGenesisTable.network eq networkKey }.forUpdate().single()
            check(row[NetworkGenesisTable.hash].contentEquals(genesisHash.hexToByteArray())) { "Network genesis identity changed" }
            if (!row[NetworkGenesisTable.seeded]) {
                check(ChainInclusionsTable.selectAll().where { ChainInclusionsTable.network eq networkKey }.empty()) { "Genesis cannot be replaced after ingestion" }
                check(LedgerUtxosTable.selectAll().where { LedgerUtxosTable.networkKey eq networkKey }.empty()) { "Unseeded network already has indexed outputs" }
                LedgerRepository.seedGenesis(networkKey, genesisOutputs)
                NetworkGenesisTable.update({ NetworkGenesisTable.network eq networkKey }) { it[seeded] = true }
                0
            } else if (genesisOutputs.isNotEmpty() &&
                LedgerUtxosTable.selectAll().where { LedgerUtxosTable.networkKey eq networkKey }.empty()
            ) {
                check((TransactionBatchesTable innerJoin ChainInclusionsTable).selectAll()
                    .where { ChainInclusionsTable.network eq networkKey }.empty()) {
                    "Genesis outputs are missing after transaction ingestion; controlled index recovery is required"
                }
                check(ChainInclusionsTable.selectAll().where {
                    (ChainInclusionsTable.network eq networkKey) and (ChainInclusionsTable.complete eq false)
                }.empty()) { "Genesis recovery is unsafe with incomplete block history" }
                LedgerRepository.seedGenesis(networkKey, genesisOutputs)
                genesisOutputs.size
            } else 0
        }
        if (restored > 0) log.info("Restored {} genesis outputs for network {} before transaction ingestion", restored, networkKey)
    }

    private fun lock(network: String): ResultRow {
        require(network.isNotBlank())
        SyncStatesTable.insertIgnore {
            it[SyncStatesTable.network] = network; it[generation] = 0; it[status] = "unavailable"
            it[reason] = "Awaiting verified replay from genesis"; it[completeInclusion] = null
            it[observedSlot] = null; it[observedHash] = null
        }
        return SyncStatesTable.selectAll().where { SyncStatesTable.network eq network }.forUpdate().single()
    }

    fun beginSession(networkKey: String): Long {
        val next = transaction {
            val row = lock(networkKey)
            val next = Math.addExact(row[SyncStatesTable.generation], 1L)
            SyncStatesTable.update({ SyncStatesTable.network eq networkKey }) {
                it[generation] = next; it[status] = "syncing"; it[reason] = "Re-establishing chain intersection"
            }
            next
        }
        publish(networkKey, force = true)
        return next
    }

    fun generation(networkKey: String): Long = transaction { lock(networkKey)[SyncStatesTable.generation] }

    fun observeTip(networkKey: String, tip: ChainPoint, expectedGeneration: Long) {
        transaction {
            val row = lock(networkKey)
            check(row[SyncStatesTable.generation] == expectedGeneration) { "Discarded stale session observation" }
            val complete = row[SyncStatesTable.completeInclusion]?.let(::inclusion)
            val caughtUp = complete?.get(ChainInclusionsTable.hash)?.contentEquals(tip.hash.hexToByteArray()) == true && complete[ChainInclusionsTable.slot].toPlainString() == tip.slot
            SyncStatesTable.update({ SyncStatesTable.network eq networkKey }) {
                it[observedSlot] = tip.slot.toBigDecimal(); it[observedHash] = tip.hash.hexToByteArray()
                if (row[SyncStatesTable.status] in setOf("complete", "syncing")) {
                    it[status] = if (caughtUp) "complete" else "syncing"; it[reason] = if (caughtUp) null else "Following adopted chain"
                }
            }
        }
        publish(networkKey)
    }

    fun adopt(networkKey: String, generation: Long, era: Int, decoded: DecodedLedgerBlock, expectedPoint: ChainPoint, resolvedLeios: LeiosContentResolver.Closure? = null) {
        val bytes = decoded.encoded
        val byron = decoded.byronHeader
        val header = decoded.headerDetails
        val hash = byron?.headerHash ?: decoded.header.hash256()
        val slot = byron?.slot ?: requireNotNull(header).slot
        val height = byron?.blockNumber ?: requireNotNull(header).blockNumber
        val previous = byron?.previousHash ?: header?.previousHash
        check(expectedPoint.hash == hash.toHexString() && expectedPoint.slot == slot.toString()) { "Fetched block does not match requested point" }
        check(decoded.perasCertificate == null) { "Peras content is disabled for this network baseline" }
        check(decoded.leiosCertificate == null || resolvedLeios != null) { "Certified closure is unavailable" }
        check(decoded.leiosCertificate != null || resolvedLeios == null) { "Unexpected certified content" }
        transaction {
            val state = lock(networkKey)
            check(state[SyncStatesTable.generation] == generation) { "Discarded old-generation block fetch" }
            val existing = ChainInclusionsTable.selectAll().where { (ChainInclusionsTable.network eq networkKey) and (ChainInclusionsTable.hash eq hash) }.singleOrNull()
            if (existing != null) {
                check(existing[ChainInclusionsTable.header].contentEquals(decoded.header.bytes()) && existing[ChainInclusionsTable.body].contentEquals(bytes)) { "Conflicting bytes for existing block identity" }
                if (existing[ChainInclusionsTable.adopted] && existing[ChainInclusionsTable.complete]) return@transaction
            }
            val tip = state[SyncStatesTable.completeInclusion]?.let(::inclusion)
            if (tip != null) check(previous?.contentEquals(tip[ChainInclusionsTable.hash]) == true) { "Block predecessor is not the last complete point" }
            else {
                val genesis = NetworkGenesisTable.selectAll().where { NetworkGenesisTable.network eq networkKey }.single()
                check(previous == null || previous.contentEquals(genesis[NetworkGenesisTable.hash])) { "Replay must begin at verified genesis" }
            }
            if (resolvedLeios != null) {
                val announcing = requireNotNull(tip) { "Certified block has no indexed predecessor" }
                val announcement = LeiosAnnouncementsTable.selectAll().where { LeiosAnnouncementsTable.inclusion eq announcing[ChainInclusionsTable.id].value }.singleOrNull()
                    ?: throw IllegalStateException("Certified predecessor announcement missing")
                check(announcement[LeiosAnnouncementsTable.hash].contentEquals(resolvedLeios.ebHash)) { "Certified closure does not match predecessor announcement" }
            }
            val id = existing?.get(ChainInclusionsTable.id)?.value ?: ChainInclusionsTable.insert {
                it[network] = networkKey; it[ChainInclusionsTable.hash] = hash; it[ChainInclusionsTable.slot] = slot.toBigDecimal()
                it[ChainInclusionsTable.height] = height.toBigDecimal(); it[previousHash] = previous; it[ChainInclusionsTable.era] = era
                it[ChainInclusionsTable.header] = decoded.header.bytes(); it[body] = bytes; it[adopted] = true; it[complete] = false
                it[sourceKind] = if (resolvedLeios == null) "direct" else "direct+endorsed"
            }[ChainInclusionsTable.id].value
            if (existing != null) {
                ReversibleChangesTable.deleteWhere { ReversibleChangesTable.inclusion eq id }
                ChainInclusionsTable.update({ ChainInclusionsTable.id eq id }) { it[adopted] = true }
            }
            header?.announcement?.let { announcement ->
                if (LeiosAnnouncementsTable.selectAll().where { LeiosAnnouncementsTable.inclusion eq id }.empty()) LeiosAnnouncementsTable.insert {
                    it[LeiosAnnouncementsTable.inclusion] = id; it[LeiosAnnouncementsTable.hash] = announcement.hash
                    it[size] = announcement.size.toBigDecimal(); it[raw] = header.body.bytes()
                }
            }
            var ordinal = 0
            val context = LedgerRepository.ProjectionContext(networkKey, id, height.toBigDecimal(), slot.toBigDecimal(), isNewInclusion = existing == null)
            // Musashi applies the predecessor's endorsed closure before this block's direct sequence.
            resolvedLeios?.let { closure ->
                val certification = LeiosCertificationsTable.select(LeiosCertificationsTable.id).where { LeiosCertificationsTable.inclusion eq id }.singleOrNull()?.get(LeiosCertificationsTable.id)?.value
                    ?: LeiosCertificationsTable.insert {
                        it[LeiosCertificationsTable.inclusion] = id; it[announcementHash] = closure.ebHash
                        it[raw] = requireNotNull(decoded.leiosCertificate).bytes(); it[complete] = true
                    }[LeiosCertificationsTable.id].value
                val existingPositions = LeiosContentReferencesTable.select(LeiosContentReferencesTable.position)
                    .where { LeiosContentReferencesTable.certification eq certification }
                    .map { it[LeiosContentReferencesTable.position] }.toSet()
                val missing = closure.entries.mapIndexedNotNull { position, entry ->
                    val batch = projector.apply(context, entry.batch, position, "endorsed", ordinal++, entry.fullTransactionHash)
                    if (position in existingPositions) null else Triple(position, entry, batch)
                }
                missing.chunked(256).forEach { rows ->
                    LeiosContentReferencesTable.batchInsert(rows, useMultiRowValues = true, shouldReturnGeneratedValues = false) { (position, entry, batch) ->
                        this[LeiosContentReferencesTable.id] = nextReferenceId
                        this[LeiosContentReferencesTable.certification] = certification; this[LeiosContentReferencesTable.position] = position
                        this[LeiosContentReferencesTable.fullHash] = entry.fullTransactionHash; this[LeiosContentReferencesTable.size] = entry.size.toBigDecimal(); this[LeiosContentReferencesTable.batch] = batch
                    }
                }
            }
            decoded.transactions.forEachIndexed { position, batch -> projector.apply(context, batch, position, "direct", ordinal++) }
            val batches = HashMap<String, DecodedTransactionBatch>(decoded.transactions.size + (resolvedLeios?.entries?.size ?: 0))
            decoded.transactions.forEach { batches[it.parent.bodyId.toHexString()] = it }
            resolvedLeios?.entries?.forEach { batches[it.batch.parent.bodyId.toHexString()] = it.batch }
            if (batches.isNotEmpty()) {
                OperationConfirmationsTable.select(OperationConfirmationsTable.txId, OperationConfirmationsTable.signedBatch).where {
                    (OperationConfirmationsTable.network eq networkKey) and
                        (OperationConfirmationsTable.txId inList batches.keys) and OperationConfirmationsTable.signedBatch.isNotNull()
                }.forEach { operation ->
                    val txId = operation[OperationConfirmationsTable.txId]
                    val batch = batches.getValue(txId)
                    if (ReviewedTransactionIdentity.matches(era, requireNotNull(operation[OperationConfirmationsTable.signedBatch]), batch)) {
                        OperationConfirmationsTable.update({ (OperationConfirmationsTable.network eq networkKey) and (OperationConfirmationsTable.txId eq txId) }) {
                            it[OperationConfirmationsTable.state] = if (batch.isValid == true) "confirmed" else "phase2_failed"
                            it[OperationConfirmationsTable.hash] = hash.toHexString()
                            it[OperationConfirmationsTable.slot] = slot.toBigDecimal()
                            it[OperationConfirmationsTable.height] = height.toBigDecimal()
                        }
                    }
                }
            }
            ChainInclusionsTable.update({ ChainInclusionsTable.id eq id }) { it[complete] = true }
            val caughtUp = state[SyncStatesTable.observedHash]?.contentEquals(hash) == true && state[SyncStatesTable.observedSlot] == slot.toBigDecimal()
            SyncStatesTable.update({ SyncStatesTable.network eq networkKey }) {
                it[completeInclusion] = id; it[status] = if (caughtUp) "complete" else "syncing"; it[reason] = null
            }
        }
        publish(networkKey)
    }

    class ReplayRequiredException : IllegalStateException("Re-establish intersection from complete retained history")

    fun rollback(networkKey: String, point: ChainPoint?, expectedGeneration: Long): Long {
        val outcome = transaction {
            val state = lock(networkKey)
            check(state[SyncStatesTable.generation] == expectedGeneration) { "Discarded stale session rollback" }
            val next = Math.addExact(state[SyncStatesTable.generation], 1)
            val target = point?.let { p -> ChainInclusionsTable.selectAll().where { (ChainInclusionsTable.network eq networkKey) and (ChainInclusionsTable.hash eq p.hash.hexToByteArray()) and (ChainInclusionsTable.slot eq p.slot.toBigDecimal()) and ChainInclusionsTable.adopted }.singleOrNull() }
            if (point != null && target == null) {
                SyncStatesTable.update({ SyncStatesTable.network eq networkKey }) { it[SyncStatesTable.generation] = next; it[status] = "unavailable"; it[reason] = "Rollback point outside retained history; verified replay required" }
                return@transaction next to true
            }
            var current = state[SyncStatesTable.completeInclusion]?.let(::inclusion)
            val targetId = target?.get(ChainInclusionsTable.id)?.value
            while (current != null && current[ChainInclusionsTable.id].value != targetId) {
                val id = current[ChainInclusionsTable.id].value
                LedgerRepository.rollbackProjection(networkKey, id)
                ReversibleChangesTable.selectAll().where { ReversibleChangesTable.inclusion eq id }.orderBy(ReversibleChangesTable.sequence, SortOrder.DESC).forEach { change ->
                    val family = change[ReversibleChangesTable.family]; val key = change[ReversibleChangesTable.key]
                    DerivedAccountGovernanceStatesTable.deleteWhere { (network eq networkKey) and (DerivedAccountGovernanceStatesTable.family eq family) and (DerivedAccountGovernanceStatesTable.key eq key) }
                    change[ReversibleChangesTable.priorValue]?.let { value -> DerivedAccountGovernanceStatesTable.insert {
                        it[network] = networkKey; it[DerivedAccountGovernanceStatesTable.family] = family; it[DerivedAccountGovernanceStatesTable.key] = key
                        it[DerivedAccountGovernanceStatesTable.value] = value; it[inclusion] = requireNotNull(change[ReversibleChangesTable.priorInclusion]); it[member] = change[ReversibleChangesTable.priorMember]
                    } }
                }
                OperationConfirmationsTable.update({ (OperationConfirmationsTable.network eq networkKey) and (OperationConfirmationsTable.hash eq current!![ChainInclusionsTable.hash].toHexString()) }) {
                    it[OperationConfirmationsTable.state] = "rolled_back"; it[OperationConfirmationsTable.hash] = null; it[slot] = null; it[height] = null
                }
                ChainInclusionsTable.update({ ChainInclusionsTable.id eq id }) { it[complete] = false; it[adopted] = false }
                val previous = current[ChainInclusionsTable.previousHash]
                current = previous?.let { hash -> ChainInclusionsTable.selectAll().where { (ChainInclusionsTable.network eq networkKey) and (ChainInclusionsTable.hash eq hash) and ChainInclusionsTable.adopted }.singleOrNull() }
            }
            check(targetId == null || current != null) { "Rollback ancestry is incomplete" }
            SyncStatesTable.update({ SyncStatesTable.network eq networkKey }) {
                it[SyncStatesTable.generation] = next; it[completeInclusion] = targetId; it[status] = "syncing"; it[reason] = "Rolled back; awaiting adopted continuation"
            }
            next to false
        }
        publish(networkKey, force = true)
        if (outcome.second) throw ReplayRequiredException()
        return outcome.first
    }

    fun boundaryFailure(networkKey: String, reason: String, expectedGeneration: Long) {
        transaction {
            val state = lock(networkKey)
            if (state[SyncStatesTable.generation] != expectedGeneration) return@transaction
            SyncStatesTable.update({ SyncStatesTable.network eq networkKey }) { it[status] = "incomplete"; it[SyncStatesTable.reason] = reason.take(240) }
        }
        publish(networkKey)
    }

    data class AnnouncedClosure(val slot: Long, val hash: ByteArray, val size: Long)

    fun announcedClosure(networkKey: String, previousHash: String): AnnouncedClosure? = transaction {
        (LeiosAnnouncementsTable innerJoin ChainInclusionsTable).select(ChainInclusionsTable.slot, LeiosAnnouncementsTable.hash, LeiosAnnouncementsTable.size).where {
            (ChainInclusionsTable.network eq networkKey) and
                (ChainInclusionsTable.hash eq previousHash.hexToByteArray()) and ChainInclusionsTable.adopted
        }.singleOrNull()?.let {
            AnnouncedClosure(it[ChainInclusionsTable.slot].longValueExact(), it[LeiosAnnouncementsTable.hash], it[LeiosAnnouncementsTable.size].longValueExact())
        }
    }

    fun intersectionPoints(networkKey: String): List<ChainPoint> = transaction {
        ChainInclusionsTable.select(ChainInclusionsTable.slot, ChainInclusionsTable.hash).where { (ChainInclusionsTable.network eq networkKey) and ChainInclusionsTable.adopted and ChainInclusionsTable.complete }
            .orderBy(ChainInclusionsTable.slot to SortOrder.DESC, ChainInclusionsTable.id to SortOrder.DESC).limit(64).map { point(it) }
    }

    fun status(networkKey: String): ChainReadState = transaction {
        val state = SyncStatesTable.selectAll().where { SyncStatesTable.network eq networkKey }.singleOrNull()
            ?: return@transaction ChainReadState(networkKey, "unavailable", "No verified indexed coverage", null, null)
        val observed = state[SyncStatesTable.observedHash]?.let { ChainPoint(requireNotNull(state[SyncStatesTable.observedSlot]).toPlainString(), it.toHexString()) }
        ChainReadState(networkKey, state[SyncStatesTable.status], state[SyncStatesTable.reason], observed, state[SyncStatesTable.completeInclusion]?.let { point(inclusion(it)) })
    }

    private fun inclusion(id: Long): ResultRow = ChainInclusionsTable.select(ChainInclusionsTable.id, ChainInclusionsTable.hash, ChainInclusionsTable.slot, ChainInclusionsTable.previousHash).where { ChainInclusionsTable.id eq id }.single()
    private fun point(row: ResultRow) = ChainPoint(row[ChainInclusionsTable.slot].toPlainString(), row[ChainInclusionsTable.hash].toHexString())

    private fun publish(network: String, force: Boolean = false) {
        val state = status(network)
        val now = System.nanoTime()
        val last = lastProgressNotification[network]
        if (!force && state.status == "syncing" && last != null && now - last < 500_000_000L) return
        lastProgressNotification[network] = now
        messages.convertAndSend("/topic/messages", SocketResponse.Success("chainstatus", state))
        if (state.status == "complete") walletRefresh.update { it + 1L }
    }
}
