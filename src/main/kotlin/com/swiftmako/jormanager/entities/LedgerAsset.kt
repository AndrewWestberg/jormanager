package com.swiftmako.jormanager.entities

import javax.persistence.*

@Entity
@Table(name = "ledger_assets")
data class LedgerAsset(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    val id: Long? = null,
    @Column(name = "policy")
    val policy: String,
    @Column(name = "name")
    val name: String,
    @Column(name = "image")
    val image: String,
    @Column(name = "description")
    val description: String?,
)