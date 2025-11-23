package com.swiftmako.jormanager.tables

import org.jetbrains.exposed.sql.Column

object LedgerUtxosTable : JmLongIdTable(name = "ledger_utxos") {
    // CREATE TABLE IF NOT EXISTS "ledger_utxos" ("id" BIGSERIAL PRIMARY KEY, "ledger_id" BIGINT NOT NULL, "tx_id" TEXT NOT NULL, "tx_ix" INTEGER NOT NULL, "lovelace" BIGINT NOT NULL, "block_created" BIGINT NOT NULL, "slot_created" BIGINT NOT NULL, "block_spent" BIGINT, "slot_spent" BIGINT, "rolled_back" BOOL NOT NULL DEFAULT false, CONSTRAINT fk_ledger_utxos_ledger_id_id FOREIGN KEY ("ledger_id") REFERENCES "ledger"(id) ON DELETE CASCADE)
    // foreign key to the ledger table
    val ledgerId: Column<Long> = long("ledger_id").references(LedgerTable.id)

    // transaction id
    val txId: Column<String> = text("tx_id")

    // transaction index
    val txIx: Column<Int> = integer("tx_ix")

    // lovelaces in this utxo
    val lovelace: Column<String> = text("lovelace")

    // when this utxo was created or spent
    val blockCreated: Column<Long> = long("block_created")
    val slotCreated: Column<Long> = long("slot_created")
    val blockSpent: Column<Long?> = long("block_spent").nullable()
    val slotSpent: Column<Long?> = long("slot_spent").nullable()
}
