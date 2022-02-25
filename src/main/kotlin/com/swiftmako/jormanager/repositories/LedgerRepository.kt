package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.LedgerAddress
import com.swiftmako.jormanager.entities.LedgerAsset
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import javax.transaction.Transactional


@Repository
interface LedgerRepository : JpaRepository<LedgerAddress, Long> {

    @Transactional
    @Modifying
    @Query("DELETE FROM LedgerUtxo lu WHERE lu.blockCreated >= :block_number")
    fun doRollbackDelete(@Param("block_number") blockNumber: Long)

    @Transactional
    @Modifying
    @Query("UPDATE LedgerUtxo lu SET lu.blockSpent = null, lu.slotSpent = null WHERE lu.blockSpent >= :block_number")
    fun doRollbackUpdate(@Param("block_number") blockNumber: Long)

    @Query("SELECT la FROM LedgerAsset la WHERE la.policy = :policy AND la.name = :name")
    fun getLedgerAssetByPolicyAndName(@Param("policy") policy: String, @Param("name") name: String): LedgerAsset?

    @Transactional
    @Modifying
    @Query("UPDATE LedgerUtxo lu SET lu.blockSpent = :blockNumber, lu.slotSpent = :slotNumber WHERE lu.txId = :txId and lu.txIx = :txIx")
    fun spendUtxo(
        @Param("txId") txId: String,
        @Param("txIx") txIx: Int,
        @Param("blockNumber") blockNumber: Long,
        @Param("slotNumber") slotNumber: Long,
    )

    @Query("SELECT l FROM LedgerAddress l WHERE l.address = :address")
    fun getByAddress(@Param("address") address: String): LedgerAddress?

    @Transactional
    @Modifying
    @Query("DELETE FROM LedgerUtxo lu WHERE lu.slotSpent < :beforeSlot")
    fun pruneSpent(@Param("beforeSlot") beforeSlot: Long)
}