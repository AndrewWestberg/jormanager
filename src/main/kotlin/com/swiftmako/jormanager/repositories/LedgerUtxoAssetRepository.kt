package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.LedgerUtxoAsset
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface LedgerUtxoAssetRepository : JpaRepository<LedgerUtxoAsset, Long> {

    @Query(
        "INSERT INTO ledger_utxo_assets (id,ledger_utxo_id,ledger_asset_id,amount) VALUES (:id,:ledgerUtxoId,:ledgerAssetId,:amount)",
        nativeQuery = true
    )
    @Modifying
    fun insertLedgerUtxoAsset(
        @Param("id") id: Long,
        @Param("ledgerUtxoId") ledgerUtxoId: Long,
        @Param("ledgerAssetId") ledgerAssetId: Long,
        @Param("amount") amount: String
    )
}