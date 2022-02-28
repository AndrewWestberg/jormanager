package com.swiftmako.jormanager.entities

import javax.persistence.*

@Entity
@Table(name = "ledger")
data class LedgerAddress(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    val id: Long? = null,
    @Column(name = "address")
    val address: String,
    @Column(name = "stake_address")
    val stakeAddress: String?,
)
