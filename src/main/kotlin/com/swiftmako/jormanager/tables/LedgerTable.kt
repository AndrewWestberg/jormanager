package com.swiftmako.jormanager.tables

import org.jetbrains.exposed.sql.Column

object LedgerTable : JmLongIdTable(name = "ledger") {
    // Address that holds utxos on the ledger
    val address: Column<String> = text("address")

    // Stake address portion of the address (optional)
    val stakeAddress: Column<String?> = text("stake_address").nullable()
}