package com.swiftmako.jormanager.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.*
import com.swiftmako.jormanager.repositories.LedgerRepository
import com.swiftmako.jormanager.tables.*
import com.swiftmako.jormanager.utils.CborSpan
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.springframework.stereotype.Component
import java.math.BigInteger

/** Applies node-adopted outcomes, not a second consensus or script validator. All calls share the chain transaction. */
@Component
class BatchEffectProjector(private val json: ObjectMapper) {
    class Journal(var nextSequence: Int = 0)

    fun apply(network: String, inclusion: Long, batch: DecodedTransactionBatch, position: Int, source: String, applicationOrdinal: Int, journal: Journal, fullHash: ByteArray? = null) {
        val valid = requireNotNull(batch.isValid) { "A submission is not an adopted batch" }
        val existing = TransactionBatchesTable.selectAll().where {
            (TransactionBatchesTable.inclusion eq inclusion) and (TransactionBatchesTable.sourceKind eq source) and (TransactionBatchesTable.position eq position)
        }.singleOrNull()
        val batchId = existing?.get(TransactionBatchesTable.id)?.value ?: TransactionBatchesTable.insert {
            it[TransactionBatchesTable.inclusion] = inclusion; it[TransactionBatchesTable.sourceKind] = source
            it[TransactionBatchesTable.position] = position; it[TransactionBatchesTable.applicationOrdinal] = applicationOrdinal
            it[TransactionBatchesTable.valid] = valid; it[raw] = batch.raw.bytes(); it[TransactionBatchesTable.fullHash] = fullHash
        }[TransactionBatchesTable.id].value
        val parentId = member(batchId, batch.parent, null, null, batch.children.size, valid)
        val childIds = batch.children.mapIndexed { index, child -> member(batchId, child, parentId, index, index, valid) }
        val ordered = batch.children.zip(childIds) + (batch.parent to parentId)
        val batchIds = ordered.map { it.first.bodyId.toHexString() }.toSet()
        // A member cannot spend an output created in this same batch, even if it was processed earlier.
        ordered.forEach { (tx, _) -> require(tx.inputs.none { it.transactionId.toHexString() in batchIds }) { "Same-batch spending reference" } }
        val checkpoints = BatchAccountCheckpoints(batch)
        for ((tx, memberId) in ordered) {
            inputs(network, inclusion, memberId, tx.inputs, "spending", valid)
            inputs(network, inclusion, memberId, tx.referenceInputs, "reference", false)
            inputs(network, inclusion, memberId, tx.collateralInputs, "collateral", !valid)
            tx.outputs.forEachIndexed { index, output -> LedgerRepository.createOutput(network, inclusion, memberId, tx.bodyId, index, output, false, valid) }
            tx.collateralReturn?.let { LedgerRepository.createOutput(network, inclusion, memberId, tx.bodyId, tx.outputs.size, it, true, !valid) }
            data(memberId, tx, valid)
            var effectPosition = 0
            fun effect(kind: String, address: ByteArray, amount: BigInteger?, lower: BigInteger? = null, upper: BigInteger? = null) {
                val index = effectPosition++
                if (AccountEffectsTable.selectAll().where { (AccountEffectsTable.member eq memberId) and (AccountEffectsTable.position eq index) }.empty()) {
                    AccountEffectsTable.insert {
                        it[member] = memberId; it[AccountEffectsTable.position] = index; it[account] = address; it[AccountEffectsTable.kind] = kind
                        it[AccountEffectsTable.amount] = amount?.toBigDecimal(); it[AccountEffectsTable.lower] = lower?.toBigDecimal()
                        it[AccountEffectsTable.upper] = upper?.toBigDecimal(); it[effective] = valid
                    }
                }
                if (valid && amount != null && kind in setOf("WITHDRAWAL", "DIRECT_DEPOSIT", "DONATION")) {
                    // These are category totals, not a fabricated reward balance: epochs also change account balances.
                    val previous = state(network, kind, address)?.get(DerivedAccountGovernanceStatesTable.value)?.toBigInteger() ?: BigInteger.ZERO
                    project(network, inclusion, memberId, kind, address, (previous + amount).toString(), journal.nextSequence++)
                }
            }
            tx.startingAccountIntervals.forEach {
                if (valid) checkpoints.starting(it)
                effect("STARTING_INTERVAL", it.address, it.exact, it.lowerInclusive, it.upperExclusive)
            }
            tx.accountIntervals.forEach {
                if (valid) checkpoints.current(it)
                effect("CURRENT_INTERVAL", it.address, it.exact, it.lowerInclusive, it.upperExclusive)
            }
            tx.withdrawals.forEach {
                if (valid) checkpoints.withdraw(it.address, it.amount)
                effect("WITHDRAWAL", it.address, it.amount)
            }
            tx.certificates.forEachIndexed { index, certificate ->
                certificate(network, inclusion, memberId, index, certificate, valid, journal)
                certificate.credential?.let { credential ->
                    val key = byteArrayOf(credential.kind.toByte()) + credential.hash
                    val kind = when (certificate.kind) { 7, 11, 12, 13, 16 -> "REGISTRATION_DEPOSIT"; 8, 17 -> "REGISTRATION_REFUND"; else -> null }
                    if (kind != null) effect(kind, key, certificate.amount)
                }
            }
            tx.directDeposits.forEach {
                if (valid) checkpoints.deposit(it.address, it.amount)
                effect("DIRECT_DEPOSIT", it.address, it.amount)
            }
            tx.fields[22]?.let { effect("DONATION", byteArrayOf(), it.integer()) }
            tx.votes.forEachIndexed { index, vote ->
                val key = byteArrayOf(vote.voterKind.toByte()) + vote.voterHash + vote.actionTransactionId + byteArrayOf((vote.actionIndex ushr 8).toByte(), vote.actionIndex.toByte())
                val typed = mapOf("voterKind" to vote.voterKind, "voterHash" to vote.voterHash.toHexString(), "actionTxId" to vote.actionTransactionId.toHexString(), "actionIndex" to vote.actionIndex, "vote" to vote.vote, "anchor" to vote.anchor?.let(::cborValue))
                governance(memberId, index, "VOTE", key, null, requireNotNull(tx.fields[19]), typed, valid)
                if (valid) project(network, inclusion, memberId, "VOTE", key, json.writeValueAsString(typed), journal.nextSequence++)
            }
            tx.proposals.forEachIndexed { index, proposal ->
                val key = tx.bodyId + byteArrayOf((index ushr 8).toByte(), index.toByte())
                val typed = mapOf("actionKind" to proposal.actionKind, "deposit" to proposal.deposit.toString(), "rewardAccount" to proposal.rewardAccount.toHexString(), "action" to cborValue(proposal.action), "anchor" to cborValue(proposal.anchor))
                governance(memberId, index, "PROPOSAL", key, proposal.deposit, proposal.raw, typed, valid)
                if (valid) project(network, inclusion, memberId, "PROPOSAL", key, json.writeValueAsString(typed), journal.nextSequence++)
            }
        }
    }

    private fun member(batch: Long, tx: DecodedTransactionMember, parent: Long?, childPosition: Int?, ordinal: Int, valid: Boolean): Long =
        TransactionMembersTable.selectAll().where { (TransactionMembersTable.batch eq batch) and (TransactionMembersTable.txId eq tx.bodyId) }
            .singleOrNull()?.get(TransactionMembersTable.id)?.value ?: TransactionMembersTable.insert {
            it[TransactionMembersTable.batch] = batch; it[txId] = tx.bodyId; it[TransactionMembersTable.parent] = parent
            it[TransactionMembersTable.childPosition] = childPosition; it[processingOrdinal] = ordinal
            it[rawBody] = tx.body.bytes(); it[rawWitnesses] = tx.witnesses.bytes(); it[rawAuxiliary] = tx.auxiliaryData?.bytes()
            it[auxiliaryPresence] = if (tx.auxiliaryData == null) "absent" else if (tx.auxiliaryData.isNull()) "null" else "present"
            it[fee] = tx.fee?.toBigDecimal(); it[effective] = valid
        }[TransactionMembersTable.id].value

    private fun inputs(network: String, inclusion: Long, member: Long, inputs: List<DecodedInput>, role: String, effective: Boolean) {
        inputs.forEachIndexed { position, input ->
            val resolved = if (effective) LedgerRepository.spend(network, inclusion, input.transactionId, input.index)
                else LedgerRepository.currentOutput(network, input.transactionId, input.index)?.get(LedgerUtxosTable.outputOccurrenceId)
            val existing = InputOccurrencesTable.selectAll().where { (InputOccurrencesTable.member eq member) and (InputOccurrencesTable.role eq role) and (InputOccurrencesTable.position eq position) }.singleOrNull()
            if (existing == null) InputOccurrencesTable.insert {
                it[InputOccurrencesTable.member] = member; it[InputOccurrencesTable.role] = role; it[InputOccurrencesTable.position] = position
                it[txId] = input.transactionId; it[index] = input.index; it[resolvedOutput] = resolved; it[InputOccurrencesTable.effective] = effective
            } else InputOccurrencesTable.update({ InputOccurrencesTable.id eq existing[InputOccurrencesTable.id] }) { it[resolvedOutput] = resolved }
        }
    }

    private fun data(member: Long, tx: DecodedTransactionMember, effective: Boolean) {
        fun save(family: String, index: Int, raw: CborSpan, typed: Any?) {
            if (MemberDataTable.selectAll().where { (MemberDataTable.member eq member) and (MemberDataTable.family eq family) and (MemberDataTable.position eq index) }.empty()) MemberDataTable.insert {
                it[MemberDataTable.member] = member; it[MemberDataTable.family] = family; it[position] = index
                it[MemberDataTable.raw] = raw.bytes(); it[MemberDataTable.typed] = json.writeValueAsString(typed); it[MemberDataTable.effective] = effective
            }
        }
        val families = mapOf(3 to "validityEnd", 4 to "certificates", 5 to "withdrawals", 7 to "auxiliaryHash", 8 to "validityStart", 9 to "mint", 11 to "scriptIntegrityHash", 14 to "guards", 15 to "network", 17 to "totalCollateral", 19 to "votes", 20 to "proposals", 21 to "treasury", 22 to "donation", 24 to "requiredGuards", 25 to "directDeposits", 26 to "accountIntervals", 27 to "startingAccountIntervals")
        tx.fields.forEach { (key, value) -> families[key]?.let { save(it, 0, value, cborValue(value)) } }
        tx.witnessData.scripts.forEachIndexed { index, script -> save("script", index, script.raw, mapOf("language" to script.language, "value" to cborValue(script.raw))) }
        tx.witnessData.datums.forEachIndexed { index, datum -> save("datum", index, datum, cborValue(datum)) }
        tx.witnessData.redeemers.forEachIndexed { index, redeemer -> save("redeemer", index, redeemer.data, mapOf("purpose" to redeemer.purpose, "index" to redeemer.index.toString(), "memory" to redeemer.memory.toString(), "steps" to redeemer.steps.toString(), "data" to cborValue(redeemer.data))) }
        tx.auxiliary?.metadata?.let { save("metadata", 0, it, cborValue(it)) }
        tx.auxiliary?.scripts?.forEachIndexed { index, script -> save("auxiliaryScript", index, script.raw, mapOf("language" to script.language, "value" to cborValue(script.raw))) }
    }

    private fun certificate(network: String, inclusion: Long, member: Long, position: Int, cert: DecodedCertificate, effective: Boolean, journal: Journal) {
        val credential = cert.credential?.let { byteArrayOf(it.kind.toByte()) + it.hash }
        val key = cert.pool ?: credential ?: cert.raw.hash256()
        val typed = linkedMapOf<String, Any?>("tag" to cert.kind, "credential" to credential?.toHexString(), "pool" to cert.pool?.toHexString(), "amount" to cert.amount?.toString(), "epoch" to cert.epoch?.toString(), "payload" to cborValue(cert.raw))
        cert.poolParameters?.let { pool -> typed["poolParameters"] = mapOf("operator" to pool.operator.toHexString(), "vrf" to pool.vrf.toHexString(), "blsPublicKey" to pool.blsPublicKey?.toHexString(), "blsProof" to pool.blsProof?.toHexString(), "pledge" to pool.pledge.toString(), "cost" to pool.cost.toString(), "marginNumerator" to pool.marginNumerator.toString(), "marginDenominator" to pool.marginDenominator.toString(), "rewardAccount" to pool.rewardAccount.toHexString(), "owners" to pool.owners.map { it.toHexString() }, "relays" to cborValue(pool.relays), "metadata" to pool.metadata?.let(::cborValue)) }
        governance(member, position, "CERTIFICATE", key, cert.amount, cert.raw, typed, effective)
        if (!effective) return
        // Registration intent and scheduled retirement are distinct from epoch-bound activation/refund.
        val family = when (cert.kind) { 3 -> "POOL_REGISTRATION"; 4 -> "POOL_RETIREMENT"; 0, 7, 11, 12, 13 -> "STAKE_REGISTRATION"; 1, 8 -> "STAKE_DEREGISTRATION"; 2, 9, 10 -> "STAKE_DELEGATION"; 14, 15 -> "COMMITTEE"; 16, 17, 18 -> "DREP"; else -> "CERTIFICATE_${cert.kind}" }
        project(network, inclusion, member, family, key, json.writeValueAsString(typed), journal.nextSequence++)
        if (cert.kind == 3) project(network, inclusion, member, "POOL_RETIREMENT", key, "null", journal.nextSequence++)
    }

    private fun governance(member: Long, position: Int, kind: String, target: ByteArray?, amount: BigInteger?, raw: CborSpan, typed: Any, effective: Boolean) {
        if (CertificateGovernanceEffectsTable.selectAll().where { (CertificateGovernanceEffectsTable.member eq member) and (CertificateGovernanceEffectsTable.kind eq kind) and (CertificateGovernanceEffectsTable.position eq position) }.empty()) CertificateGovernanceEffectsTable.insert {
            it[CertificateGovernanceEffectsTable.member] = member; it[CertificateGovernanceEffectsTable.position] = position
            it[CertificateGovernanceEffectsTable.kind] = kind; it[CertificateGovernanceEffectsTable.target] = target
            it[CertificateGovernanceEffectsTable.amount] = amount?.toBigDecimal(); it[CertificateGovernanceEffectsTable.raw] = raw.bytes()
            it[CertificateGovernanceEffectsTable.typed] = json.writeValueAsString(typed); it[CertificateGovernanceEffectsTable.effective] = effective
        }
    }

    private fun state(network: String, family: String, key: ByteArray): ResultRow? = DerivedAccountGovernanceStatesTable.selectAll().where {
        (DerivedAccountGovernanceStatesTable.network eq network) and (DerivedAccountGovernanceStatesTable.family eq family) and (DerivedAccountGovernanceStatesTable.key eq key)
    }.singleOrNull()

    private fun project(network: String, inclusion: Long, member: Long, family: String, key: ByteArray, value: String, sequence: Int) {
        val prior = state(network, family, key)
        ReversibleChangesTable.insert {
            it[ReversibleChangesTable.inclusion] = inclusion; it[ReversibleChangesTable.sequence] = sequence
            it[ReversibleChangesTable.family] = family; it[ReversibleChangesTable.key] = key
            it[priorValue] = prior?.get(DerivedAccountGovernanceStatesTable.value)
            it[priorInclusion] = prior?.get(DerivedAccountGovernanceStatesTable.inclusion)
            it[priorMember] = prior?.get(DerivedAccountGovernanceStatesTable.member)
        }
        if (prior == null) DerivedAccountGovernanceStatesTable.insert {
            it[DerivedAccountGovernanceStatesTable.network] = network; it[DerivedAccountGovernanceStatesTable.family] = family
            it[DerivedAccountGovernanceStatesTable.key] = key; it[DerivedAccountGovernanceStatesTable.value] = value
            it[DerivedAccountGovernanceStatesTable.inclusion] = inclusion; it[DerivedAccountGovernanceStatesTable.member] = member
        } else DerivedAccountGovernanceStatesTable.update({ (DerivedAccountGovernanceStatesTable.network eq network) and (DerivedAccountGovernanceStatesTable.family eq family) and (DerivedAccountGovernanceStatesTable.key eq key) }) {
            it[DerivedAccountGovernanceStatesTable.value] = value; it[DerivedAccountGovernanceStatesTable.inclusion] = inclusion; it[DerivedAccountGovernanceStatesTable.member] = member
        }
    }

    /** Flat typed nodes preserve structured keys without imposing a JSON/stack nesting limit on ledger data. */
    fun cborValue(span: CborSpan): Any {
        val pending = ArrayList<CborSpan>()
        val nodes = ArrayList<Map<String, Any?>>()
        pending.add(span)
        var index = 0
        while (index < pending.size) {
            val item = pending[index++]
            fun childIndices(): List<Int> {
                val start = pending.size
                pending.addAll(item.children)
                return List(item.children.size) { start + it }
            }
            nodes.add(when (item.major) {
                0, 1 -> mapOf("type" to "integer", "value" to item.integer().toString())
                2 -> mapOf("type" to "bytes", "hex" to item.payload().toHexString())
                3 -> mapOf("type" to "text", "value" to item.payload().toString(Charsets.UTF_8))
                4 -> mapOf("type" to "array", "items" to childIndices())
                5 -> mapOf("type" to "map", "entries" to childIndices().chunked(2))
                6 -> mapOf("type" to "tag", "tag" to item.argument.toString(), "value" to childIndices().single())
                else -> if (item.isNull()) mapOf("type" to "null")
                    else if (item.argument == BigInteger.valueOf(20) || item.argument == BigInteger.valueOf(21)) mapOf("type" to "boolean", "value" to item.boolean())
                    else mapOf("type" to "simple", "rawHex" to item.bytes().toHexString())
            })
        }
        return mapOf("root" to 0, "nodes" to nodes)
    }
}
