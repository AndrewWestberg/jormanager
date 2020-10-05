package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.ChainBlock
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ChainRepository : JpaRepository<ChainBlock, Long> {

    @Query("SELECT c FROM ChainBlock c WHERE c.slotNumber = :slot")
    fun findBySlot(@Param("slot") slot: Long): ChainBlock?

    @Query("SELECT c FROM ChainBlock c WHERE c.blockNumber = :block_number")
    fun findByBlockNumber(@Param("block_number") blockNumber: Long): ChainBlock?

    @Query("SELECT c FROM ChainBlock c WHERE c.blockNumber >= :block_number")
    fun findByBlockNumberAndAbove(@Param("block_number") blockNumber: Long): List<ChainBlock>
}