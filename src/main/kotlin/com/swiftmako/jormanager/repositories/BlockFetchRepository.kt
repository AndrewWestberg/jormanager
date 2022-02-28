package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.BlockFetch
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface BlockFetchRepository : JpaRepository<BlockFetch, Long> {
    @Query("SELECT MAX(b.blockNumber) FROM BlockFetch b")
    fun findTipBlockNumber(): Long

    @Query("SELECT b.* FROM blockfetch b ORDER BY b.block_number DESC LIMIT 1", nativeQuery = true)
    fun findTipBlock(): BlockFetch?

    @Query("SELECT b.* FROM blockfetch b ORDER BY b.block_number ASC LIMIT 1", nativeQuery = true)
    fun findStartBlock(): BlockFetch?

    @Modifying
    @Query("DELETE FROM BlockFetch b WHERE b.blockNumber >= :blockNumber")
    fun doRollbackDelete(@Param("blockNumber") blockNumber: Long)

    @Query(
        "INSERT INTO blockfetch (id,block_number,slot_number,hash,prev_hash) VALUES (:id,:blockNumber,:slotNumber,:hash,:prevHash)",
        nativeQuery = true
    )
    @Modifying
    fun insertBlockFetch(
        @Param("id") id: Long,
        @Param("blockNumber") blockNumber: Long,
        @Param("slotNumber") slotNumber: Long,
        @Param("hash") hash: String,
        @Param("prevHash") prevHash: String
    )
}