package com.swiftmako.jormanager.services

import com.swiftmako.jormanager.model.ChainReadState
import com.swiftmako.jormanager.tables.ChainInclusionsTable
import com.swiftmako.jormanager.tables.LedgerUtxosTable
import com.swiftmako.jormanager.tables.NetworkGenesisTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Service

/** Reports real retained coverage. Legacy identifiers are retained, never relabelled as decoded occurrences. */
@Service
class LedgerReplayService(private val coordinator: ChainIngestionCoordinator) {
    data class Coverage(
        val networkKey: String,
        val readState: ChainReadState,
        val genesisVerifiedAndSeeded: Boolean,
        val retainedAdoptedInclusions: String,
        val unassignedLegacyOutputs: String,
        val replayAnchor: String,
        val recovery: String,
    )

    fun inspect(networkKey: String): Coverage = transaction {
        val state = coordinator.status(networkKey)
        val seeded = NetworkGenesisTable.selectAll().where { NetworkGenesisTable.network eq networkKey }.singleOrNull()?.get(NetworkGenesisTable.seeded) == true
        val count = ChainInclusionsTable.selectAll().where { (ChainInclusionsTable.network eq networkKey) and ChainInclusionsTable.adopted }.count()
        val legacy = LedgerUtxosTable.selectAll().where { LedgerUtxosTable.networkKey.isNull() }.count()
        Coverage(networkKey, state, seeded, count.toString(), legacy.toString(),
            if (state.lastCompletePoint == null) "verified-genesis" else "last-complete-retained-point",
            if (state.status == "complete") "Current coverage is complete; legacy rows remain historical only."
            else "The observer re-intersects from durable complete history. A source retaining the required blocks and matching genesis is required; do not assign legacy rows to a guessed network.")
    }
}
