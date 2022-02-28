package com.swiftmako.jormanager.entities

import javax.persistence.*

@Entity
@Table(name = "ledger_utxo_assets")
data class LedgerUtxoAsset(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    val id: Long? = null,
    @Column(name = "ledger_utxo_id")
    val ledgerUtxoId: Long,
    @Column(name = "ledger_asset_id")
    val ledgerAssetId: Long,
    @Column(name = "amount")
    val amount: String,
)
