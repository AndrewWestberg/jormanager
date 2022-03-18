package com.swiftmako.jormanager.model

data class BlockFetch(
    val id: Long?,
    val blockNumber: Long,
    val slotNumber: Long,
    val hash: String,
    val prevHash: String,
)
