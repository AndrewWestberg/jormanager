package com.swiftmako.jormanager.entities

import javax.persistence.*

@Entity
@Table(name = "ledger_utxos")
data class LedgerUtxo(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    val id: Long? = null,
    @Column(name = "ledger_id")
    val ledgerId: Long,
    @Column(name = "tx_id")
    val txId: String,
    @Column(name = "tx_ix")
    val txIx: Int,
    @Column(name = "lovelace")
    val lovelace: String,
    @Column(name = "block_created")
    val blockCreated: Long,
    @Column(name = "slot_created")
    val slotCreated: Long,
    @Column(name = "block_spent")
    val blockSpent: Long?,
    @Column(name = "slot_spent")
    val slotSpent: Long?,
)
