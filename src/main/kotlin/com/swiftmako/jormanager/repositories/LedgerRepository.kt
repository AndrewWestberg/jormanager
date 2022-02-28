package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.LedgerAddress
import com.swiftmako.jormanager.entities.LedgerAsset
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository


@Repository
interface LedgerRepository : JpaRepository<LedgerAddress, Long> {

    @Modifying
    @Query("DELETE FROM LedgerUtxo lu WHERE lu.blockCreated >= :block_number")
    fun doRollbackDelete(@Param("block_number") blockNumber: Long)

    @Modifying
    @Query("UPDATE LedgerUtxo lu SET lu.blockSpent = null, lu.slotSpent = null WHERE lu.blockSpent >= :block_number")
    fun doRollbackUpdate(@Param("block_number") blockNumber: Long)

    @Query("SELECT la FROM LedgerAsset la WHERE la.policy = :policy AND la.name = :name")
    fun getLedgerAssetByPolicyAndName(@Param("policy") policy: String, @Param("name") name: String): LedgerAsset?

    @Modifying
    @Query("UPDATE LedgerUtxo lu SET lu.blockSpent = :blockNumber, lu.slotSpent = :slotNumber WHERE lu.txId = :txId and lu.txIx = :txIx")
    fun spendUtxo(
        @Param("txId") txId: String,
        @Param("txIx") txIx: Int,
        @Param("blockNumber") blockNumber: Long,
        @Param("slotNumber") slotNumber: Long,
    )

    @Query("SELECT l.id FROM LedgerAddress l WHERE l.address = :address")
//    @QueryHints(QueryHint(name = org.hibernate.annotations.QueryHints.CACHEABLE, value = "true"))
    fun getIdByAddress(@Param("address") address: String): Long?

    @Modifying
    @Query("DELETE FROM LedgerUtxo lu WHERE lu.slotSpent < :beforeSlot")
    fun pruneSpent(@Param("beforeSlot") beforeSlot: Long)

//    @Query("VALUES NEXT VALUE FOR HIBERNATE_SEQUENCE", nativeQuery = true)
//    fun nextHibernateSeqVal(): Long

    @Query("INSERT INTO ledger (id,address,stake_address) VALUES (:id,:address,:stakeAddress)", nativeQuery = true)
    @Modifying
    fun insertLedgerAddress(
        @Param("id") id: Long,
        @Param("address") address: String,
        @Param("stakeAddress") stakeAddress: String?
    )
}