package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.LedgerUtxo
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface LedgerUtxoRepository : JpaRepository<LedgerUtxo, Long> {

    @Query(
        "INSERT INTO ledger_utxos (id,ledger_id,tx_id,tx_ix,lovelace,block_created,slot_created,block_spent,slot_spent) VALUES (:id,:ledgerId,:txId,:txIx,:lovelace,:blockCreated,:slotCreated,:blockSpent,:slotSpent)",
        nativeQuery = true
    )
    @Modifying
    fun insertLedgerUtxo(
        @Param("id") id: Long,
        @Param("ledgerId") ledgerId: Long,
        @Param("txId") txId: String,
        @Param("txIx") txIx: Int,
        @Param("lovelace") lovelace: String,
        @Param("blockCreated") blockCreated: Long,
        @Param("slotCreated") slotCreated: Long,
        @Param("blockSpent") blockSpent: Long?,
        @Param("slotSpent") slotSpent: Long?
    )
}