package com.swiftmako.jormanager.tables

import org.jetbrains.exposed.sql.Column

object BlockFetchTable : JmLongIdTable(name = "blockfetch") {

    val blockNumber: Column<Long> = long("block_number")

    val slotNumber: Column<Long> = long("slot_number")

    val hash: Column<String> = varchar("hash", 255)

    val prevHash: Column<String> = varchar("prev_hash", 255)
}
