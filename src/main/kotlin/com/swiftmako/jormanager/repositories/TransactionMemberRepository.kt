package com.swiftmako.jormanager.repositories

import com.fasterxml.jackson.databind.ObjectMapper
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.*
import com.swiftmako.jormanager.services.ChainIngestionCoordinator
import com.swiftmako.jormanager.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Repository

@Repository
class TransactionMemberRepository(private val coordinator: ChainIngestionCoordinator, private val json: ObjectMapper) {
    fun find(requestId: String, network: String, txId: String): TransactionView = transaction {
        require(txId.matches(Regex("[0-9a-f]{64}"))) { "Invalid transaction ID" }
        SyncStatesTable.selectAll().where { SyncStatesTable.network eq network }.forUpdate().singleOrNull()
        val rows = (TransactionMembersTable innerJoin TransactionBatchesTable innerJoin ChainInclusionsTable).selectAll().where {
            (ChainInclusionsTable.network eq network) and (TransactionMembersTable.txId eq txId.hexToByteArray())
        }.orderBy(ChainInclusionsTable.slot, SortOrder.DESC)
        val operationState = OperationConfirmationsTable.select(OperationConfirmationsTable.state).where {
            (OperationConfirmationsTable.network eq network) and (OperationConfirmationsTable.txId eq txId)
        }.singleOrNull()?.get(OperationConfirmationsTable.state)
        TransactionView(requestId, network, txId, coordinator.status(network), rows.map(::view), operationState)
    }

    private fun view(row: ResultRow): TransactionOccurrenceView {
        val member = row[TransactionMembersTable.id].value
        val adopted = row[ChainInclusionsTable.adopted]
        val parent = row[TransactionMembersTable.parent]?.let { id -> TransactionMembersTable.selectAll().where { TransactionMembersTable.id eq id }.single()[TransactionMembersTable.txId].toHexString() }
        val children = TransactionMembersTable.selectAll().where { TransactionMembersTable.parent eq member }.orderBy(TransactionMembersTable.childPosition).map { it[TransactionMembersTable.txId].toHexString() }
        val inputs = InputOccurrencesTable.selectAll().where { InputOccurrencesTable.member eq member }.orderBy(InputOccurrencesTable.role to SortOrder.ASC, InputOccurrencesTable.position to SortOrder.ASC).map {
            InputView(it[InputOccurrencesTable.txId].toHexString(), it[InputOccurrencesTable.index], it[InputOccurrencesTable.role], adopted && it[InputOccurrencesTable.effective])
        }
        val outputs = OutputOccurrencesTable.selectAll().where { OutputOccurrencesTable.member eq member }.orderBy(OutputOccurrencesTable.index).map { output ->
            val assets = OutputAssetsTable.selectAll().where { OutputAssetsTable.output eq output[OutputOccurrencesTable.id].value }.map { AssetView(it[OutputAssetsTable.policy].toHexString(), it[OutputAssetsTable.name].toHexString(), it[OutputAssetsTable.quantity].toPlainString()) }
            OutputView(output[OutputOccurrencesTable.index], output[OutputOccurrencesTable.address].toHexString(), output[OutputOccurrencesTable.coin].toPlainString(), assets, output[OutputOccurrencesTable.collateralReturn], adopted && output[OutputOccurrencesTable.effective])
        }
        val effects = AccountEffectsTable.selectAll().where { AccountEffectsTable.member eq member }.orderBy(AccountEffectsTable.position).map {
            AccountEffectView(it[AccountEffectsTable.kind], it[AccountEffectsTable.account].toHexString(), it[AccountEffectsTable.amount]?.toPlainString(), adopted && it[AccountEffectsTable.effective], it[AccountEffectsTable.lower]?.toPlainString(), it[AccountEffectsTable.upper]?.toPlainString())
        }
        val data = MemberDataTable.selectAll().where { MemberDataTable.member eq member }.orderBy(MemberDataTable.family to SortOrder.ASC, MemberDataTable.position to SortOrder.ASC).map {
            MemberDataView(it[MemberDataTable.family], it[MemberDataTable.position], it[MemberDataTable.raw].toHexString(), json.readTree(it[MemberDataTable.typed]))
        } + CertificateGovernanceEffectsTable.selectAll().where { CertificateGovernanceEffectsTable.member eq member }.orderBy(CertificateGovernanceEffectsTable.position).map {
            MemberDataView(it[CertificateGovernanceEffectsTable.kind], it[CertificateGovernanceEffectsTable.position], it[CertificateGovernanceEffectsTable.raw].toHexString(), json.readTree(it[CertificateGovernanceEffectsTable.typed]))
        }
        val deposits = effects.filter { it.kind == "DIRECT_DEPOSIT" }.map { DirectDepositView(it.accountAddressHex, requireNotNull(it.amount), it.effective) }
        return TransactionOccurrenceView(
            InclusionView(row[ChainInclusionsTable.hash].toHexString(), row[ChainInclusionsTable.slot].toPlainString(), row[ChainInclusionsTable.height].toPlainString(), row[ChainInclusionsTable.era], adopted, row[TransactionBatchesTable.sourceKind]),
            parent, children, row[TransactionBatchesTable.valid], adopted && row[TransactionMembersTable.effective], row[TransactionMembersTable.fee]?.toPlainString(), inputs, outputs,
            DirectDepositsView(if (deposits.isEmpty()) "absent" else "present", deposits), effects, data,
            if (!adopted) "rolled_back" else if (row[TransactionBatchesTable.valid]) "confirmed" else "phase2_failed",
            leiosProvenance(row),
        )
    }

    private fun leiosProvenance(row: ResultRow): LeiosProvenanceView? {
        if (row[TransactionBatchesTable.sourceKind] != "endorsed") return null
        val reference = LeiosContentReferencesTable.selectAll().where {
            LeiosContentReferencesTable.batch eq row[TransactionBatchesTable.id].value
        }.single()
        val certification = LeiosCertificationsTable.selectAll().where {
            LeiosCertificationsTable.id eq reference[LeiosContentReferencesTable.certification]
        }.single()
        val announcement = ChainInclusionsTable.selectAll().where {
            (ChainInclusionsTable.network eq row[ChainInclusionsTable.network]) and
                (ChainInclusionsTable.hash eq requireNotNull(row[ChainInclusionsTable.previousHash]))
        }.single()
        return LeiosProvenanceView(
            ChainPoint(announcement[ChainInclusionsTable.slot].toPlainString(), announcement[ChainInclusionsTable.hash].toHexString()),
            certification[LeiosCertificationsTable.announcementHash].toHexString(),
            reference[LeiosContentReferencesTable.fullHash].toHexString(),
            reference[LeiosContentReferencesTable.position],
            reference[LeiosContentReferencesTable.size].toPlainString(),
            certification[LeiosCertificationsTable.raw].toHexString(),
        )
    }

    /** Account-only members remain discoverable; reward accrual is not inferred from transaction bodies. */
    fun accountHistory(requestId: String, network: String, accountHex: String): Map<String, Any?> = transaction {
        require(accountHex.matches(Regex("[0-9a-f]{58}"))) { "Invalid reward account address" }
        SyncStatesTable.selectAll().where { SyncStatesTable.network eq network }.forUpdate().singleOrNull()
        val history = (AccountEffectsTable innerJoin TransactionMembersTable innerJoin TransactionBatchesTable innerJoin ChainInclusionsTable)
            .selectAll().where { (ChainInclusionsTable.network eq network) and (AccountEffectsTable.account eq accountHex.hexToByteArray()) }
            .orderBy(ChainInclusionsTable.slot, SortOrder.DESC).map {
                mapOf("txId" to it[TransactionMembersTable.txId].toHexString(), "inclusionHash" to it[ChainInclusionsTable.hash].toHexString(), "slot" to it[ChainInclusionsTable.slot].toPlainString(), "kind" to it[AccountEffectsTable.kind], "amount" to it[AccountEffectsTable.amount]?.toPlainString(), "effective" to (it[ChainInclusionsTable.adopted] && it[AccountEffectsTable.effective]))
            }
        mapOf("requestId" to requestId, "networkKey" to network, "accountAddressHex" to accountHex, "readState" to coordinator.status(network), "balance" to null, "balanceReason" to "A point-matched node account snapshot is required; transaction credits are not reward balance", "history" to history)
    }
}
