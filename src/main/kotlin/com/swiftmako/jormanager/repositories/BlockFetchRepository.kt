package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.BlockFetch
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface BlockFetchRepository : JpaRepository<BlockFetch, Long> {
    @Query("SELECT MAX(b.blockNumber) FROM BlockFetch b")
    fun findTipBlockNumber(): Long

    @Query("SELECT b.* FROM blockfetch b ORDER BY b.block_number DESC LIMIT 1", nativeQuery = true)
    fun findTipBlock(): BlockFetch?

    @Query("SELECT b.* FROM blockfetch b ORDER BY b.block_number ASC LIMIT 1", nativeQuery = true)
    fun findStartBlock(): BlockFetch?
}