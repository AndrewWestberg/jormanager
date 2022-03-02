package com.swiftmako.jormanager.entities

import javax.persistence.*

@Entity
@Table(name = "ledger_assets")
data class LedgerAsset(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    val id: Long? = null,
    @Column(name = "policy")
    val policy: String,
    @Column(name = "name")
    val name: String,
    @Column(name = "image")
    @Lob
    val image: String,
    @Column(name = "description")
    @Lob
    val description: String?,
) {
    companion object {
        val DUMMY = LedgerAsset(-1L, "", "", "", null)
    }
}