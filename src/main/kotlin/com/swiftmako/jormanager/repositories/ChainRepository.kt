package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.ChainBlock
import jakarta.transaction.Transactional
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
@Transactional
interface ChainRepository : JpaRepository<ChainBlock, Long> {
    @Query("SELECT c FROM ChainBlock c WHERE c.slotNumber = :slot")
    fun findBySlot(
        @Param("slot") slot: Long
    ): ChainBlock?

    @Query("SELECT c FROM ChainBlock c WHERE c.blockNumber = :block_number")
    fun findByBlockNumber(
        @Param("block_number") blockNumber: Long
    ): ChainBlock?

    @Modifying
    @Query("DELETE FROM ChainBlock c WHERE c.blockNumber >= :block_number")
    fun deleteByBlockNumberAndAbove(
        @Param("block_number") blockNumber: Long
    )

    @Query("SELECT MAX(c.slotNumber) FROM ChainBlock c")
    fun findSyncedTip(): Long

    @Query("SELECT MAX(c.blockNumber) FROM ChainBlock c")
    fun findTipBlockNumber(): Long

    // @Query(value = "SELECT c.* FROM chain c ORDER BY c.block_number DESC LIMIT 1", nativeQuery = true)
    @Query("SELECT c FROM ChainBlock c ORDER BY c.blockNumber DESC LIMIT 1")
    fun findTipBlock(): ChainBlock?

    // @Query("SELECT c.* FROM chain c ORDER BY c.block_number ASC LIMIT 1", nativeQuery = true)
    @Query("SELECT c FROM ChainBlock c ORDER BY c.blockNumber ASC LIMIT 1")
    fun findStartBlock(): ChainBlock?

    @Query("SELECT c FROM ChainBlock c WHERE c.slotNumber > :firstSlot AND c.slotNumber <= :lastSlot")
    fun findBetweenSlots(
        @Param("firstSlot") firstSlot: Long,
        @Param("lastSlot") lastSlot: Long
    ): List<ChainBlock>

    @Modifying
    @Query("DELETE FROM ChainBlock c WHERE c.etaV=''")
    fun deleteEmptyEta()

    @Query("SELECT c FROM ChainBlock c WHERE c.slotNumber < :slot AND :slot - c.slotNumber < 480 ORDER BY c.slotNumber DESC")
    fun findFirstBeforeSlot(
        @Param("slot") slot: Long,
        pageable: Pageable = PageRequest.of(0, 1)
    ): List<ChainBlock>
}
