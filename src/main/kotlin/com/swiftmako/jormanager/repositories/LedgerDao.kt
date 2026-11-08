package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.tables.SyncStatesTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Component

/** Spending reads use only complete, adopted, network-scoped state. Pending intent is stored separately. */
@Component
class LedgerDao {
    class WalletIndexUnavailableException : IllegalStateException("Wallet index has no complete current coverage for target network")

    fun queryUtxos(networkKey: String, address: String): List<Utxo> = transaction {
        val state = SyncStatesTable.selectAll().where { SyncStatesTable.network eq networkKey }.forUpdate().singleOrNull()
        if (state == null || state[SyncStatesTable.status] != "complete") throw WalletIndexUnavailableException()
        LedgerRepository.queryUtxos(networkKey, address)
    }
}
