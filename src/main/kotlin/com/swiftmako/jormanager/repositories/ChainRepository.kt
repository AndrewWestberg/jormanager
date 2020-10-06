package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.ChainBlock
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import javax.transaction.Transactional

@Repository
interface ChainRepository : JpaRepository<ChainBlock, Long> {

    @Query("SELECT c FROM ChainBlock c WHERE c.slotNumber = :slot")
    fun findBySlot(@Param("slot") slot: Long): ChainBlock?

    @Query("SELECT c FROM ChainBlock c WHERE c.blockNumber = :block_number")
    fun findByBlockNumber(@Param("block_number") blockNumber: Long): ChainBlock?

    @Transactional
    @Modifying
    @Query("DELETE FROM ChainBlock c WHERE c.blockNumber >= :block_number")
    fun deleteByBlockNumberAndAbove(@Param("block_number") blockNumber: Long)

    @Query("SELECT MAX(c.slotNumber) FROM ChainBlock c")
    fun findSyncedTip(): Long
}