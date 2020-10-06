package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.Block
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface BlockRepository : JpaRepository<Block, Long> {

    @Query("SELECT b FROM Block b WHERE b.pool = :pool AND b.slot = :slot")
    fun findByPoolAndSlot(@Param(value = "pool") pool: String, @Param("slot") slot: Long): Block?

    /**
     * Only select blocks in pending or completed status to validate. Other states have already been validated
     */
    @Query("SELECT b FROM Block b WHERE b.slot < :slotNumber AND b.status != 'missed' AND b.status != 'forged' AND b.status != 'orphaned'")
    fun findUnvalidatedBlocksOlderThan(@Param(value="slotNumber") slotNumber: Long): List<Block>
}