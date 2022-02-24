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
    @Query("UPDATE LedgerUtxo lu SET lu.blockSpent = :blockNumber, lu.slotSpent = :slotNumber WHERE lu.hash = :hash and lu.ix = :ix")
    fun spendUtxo(
        @Param("hash") hash: String,
        @Param("ix") ix: Int,
        @Param("blockNumber") blockNumber: Long,
        @Param("blockNumber") slotNumber: Long
    )

    @Transactional
    @Modifying
    @Query("UPDATE LedgerAsset la SET la.policy = :policy, la.name = :name, la.image = :image, la.description = :description WHERE la.id = :id")
    fun updateLedgerAsset(
        @Param("id") id: Long,
        @Param("policy") policy: String,
        @Param("name") name: String,
        @Param("image") image: String,
        @Param("description") description: String?
    )

    @Transactional
    @Modifying
    @Query(
        "INSERT INTO ledger_assets (policy,name,image,description) VALUES (:policy,:name,:image,:description)",
        nativeQuery = true
    )
    fun insertLedgerAsset(
        @Param("policy") policy: String,
        @Param("name") name: String,
        @Param("image") image: String,
        @Param("description") description: String?
    ): Long

    @Transactional
    @Modifying
    @Query(
        "INSERT INTO ledger_utxos (ledger_id,tx_id,tx_ix,lovelace,block_created,slot_created,block_spent,slot_spent) VALUES (:ledgerId,:txId,:txIx,:lovelace,:blockCreated,:slotCreated,:blockSpent,:slotSpent)",
        nativeQuery = true
    )
    fun insertLedgerUtxo(
        @Param("ledgerId") ledgerId: Long,
        @Param("txId") txId: String,
        @Param("txIx") txIx: Int,
        @Param("lovelace") lovelace: String,
        @Param("blockCreated") blockCreated: Long,
        @Param("slotCreated") slotCreated: Long,
        @Param("blockSpent") blockSpent: Long?,
        @Param("slotSpent") slotSpent: Long?,
    ): Long

    @Transactional
    @Modifying
    @Query(
        "INSERT INTO ledger_utxo_assets (ledger_utxo_id,ledger_asset_id,amount) VALUES (:ledgerUtxoId,:ledgerAssetId,:amount)",
        nativeQuery = true
    )
    fun insertLedgerUtxoAsset(
        @Param("ledgerUtxoId") ledgerUtxoId: Long,
        @Param("ledgerAssetId") ledgerAssetId: Long,
        @Param("amount") amount: String
    ): Long

    @Query("SELECT l FROM LedgerAddress l WHERE l.address = :address")
    fun getByAddress(@Param("address") address: String): LedgerAddress?

    @Transactional
    @Modifying
    @Query("DELETE FROM LedgerUtxo lu WHERE lu.slotSpent < (:currentSlot - 1800)")
    fun pruneSpent(@Param("currentSlot") currentSlot: Long)
}