package com.swiftmako.jormanager.tables

import org.jetbrains.exposed.sql.Column

object LedgerUtxoAssetsTable : JmLongIdTable(name = "ledger_utxo_assets") {
    // CREATE TABLE IF NOT EXISTS "ledger_utxo_assets" ("id" BIGSERIAL PRIMARY KEY, "ledger_utxo_id" BIGINT NOT NULL, "ledger_asset_id" BIGINT NOT NULL, "amount" BIGINT NOT NULL, CONSTRAINT fk_ledger_utxo_id_id FOREIGN KEY ("ledger_utxo_id") REFERENCES "ledger_utxos"(id) ON DELETE CASCADE)
    val ledgerUtxoId: Column<Long> = long("ledger_utxo_id").references(LedgerUtxosTable.id)
    val ledgerAssetId: Column<Long> = long("ledger_asset_id").references(LedgerAssetsTable.id)
    val amount: Column<String> = text("amount")
}
