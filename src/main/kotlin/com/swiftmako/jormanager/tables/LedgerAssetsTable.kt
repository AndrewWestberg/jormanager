package com.swiftmako.jormanager.tables

import org.jetbrains.exposed.v1.core.Column

object LedgerAssetsTable : JmLongIdTable(name = "ledger_assets") {
    // CREATE TABLE IF NOT EXISTS "ledger_assets" ("id" BIGSERIAL PRIMARY KEY, "policy" TEXT NOT NULL, "name" TEXT NOT NULL, "image" TEXT, "description" TEXT)
    // policy id for this asset
    val policy: Column<String> = text("policy")

    // name for this asset
    val name: Column<String> = text("name")

    // the asset image ipfs link
    val image: Column<String> = text("image")

    // the asset image description
    val description: Column<String?> = text("description").nullable()
}
