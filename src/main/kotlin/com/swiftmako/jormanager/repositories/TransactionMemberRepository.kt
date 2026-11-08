package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.services.ChainIngestionCoordinator
import com.swiftmako.jormanager.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Repository

@Repository
class TransactionMemberRepository(private val coordinator: ChainIngestionCoordinator) {
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
