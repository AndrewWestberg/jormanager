package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.BlockFetch
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface BlockFetchRepository : JpaRepository<BlockFetch, Long> {
    @Query("SELECT MAX(b.blockNumber) FROM BlockFetch b")
    fun findTipBlockNumber(): Long
}