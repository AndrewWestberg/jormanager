package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.model.ChainReadState
import com.swiftmako.jormanager.services.ChainIngestionCoordinator
import com.swiftmako.jormanager.services.PendingWalletState
import com.swiftmako.jormanager.tables.OperationConfirmationsTable
import com.swiftmako.jormanager.tables.OperationInputsTable
import com.swiftmako.jormanager.tables.SyncStatesTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Component

/** Spending reads require verified continuity of the current network session. */
@Component
class LedgerDao(private val coordinator: ChainIngestionCoordinator) {
    class WalletIndexUnavailableException(val readState: ChainReadState) : IllegalStateException("Wallet index has no usable coverage for target network")

    fun querySnapshot(networkKey: String, addresses: Set<String>): PendingWalletState.DatabaseSnapshot = transaction {
        val state = SyncStatesTable.selectAll().where { SyncStatesTable.network eq networkKey }.forUpdate().singleOrNull()
        val readState = coordinator.readState(networkKey, state)
        if (!readState.walletReady) throw WalletIndexUnavailableException(readState)
        val operations = OperationConfirmationsTable.selectAll().where { OperationConfirmationsTable.network eq networkKey }.map {
            PendingWalletState.Operation(it[OperationConfirmationsTable.id], networkKey, it[OperationConfirmationsTable.txId],
                it[OperationConfirmationsTable.era], it[OperationConfirmationsTable.state], it[OperationConfirmationsTable.signedBatch],
                it[OperationConfirmationsTable.validFrom], it[OperationConfirmationsTable.validUntil])
        }
        val states = operations.associate { it.id to it.state }
        val reservations = OperationInputsTable.selectAll().where { OperationInputsTable.network eq networkKey }.map {
            PendingWalletState.Reservation(it[OperationInputsTable.operationId], it[OperationInputsTable.txId],
                it[OperationInputsTable.index], states.getValue(it[OperationInputsTable.operationId]))
        }
        PendingWalletState.DatabaseSnapshot(readState, LedgerRepository.queryUtxos(networkKey, addresses), operations, reservations)
    }
}
