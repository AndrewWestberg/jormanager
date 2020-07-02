package com.swiftmako.jormanager.entities

import javax.persistence.CascadeType
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.JoinColumn
import javax.persistence.OneToOne
import javax.persistence.Table

@Entity
@Table(name = "wallet")
data class WalletEntry(
        @Id
        @GeneratedValue(strategy = GenerationType.AUTO)
        val id: Long? = null,
        @Column(name = "type")
        val type: String, // payment, stake, address
        @Column(name = "payment_addr")
        val paymentAddr: String,
        @OneToOne(cascade = [CascadeType.ALL])
        @JoinColumn(name = "payment_skey", referencedColumnName = "id")
        val paymentSkey: File?,
        @OneToOne(cascade = [CascadeType.ALL])
        @JoinColumn(name = "payment_vkey", referencedColumnName = "id")
        val paymentVkey: File?,
        @Column(name = "staking_addr")
        val stakingAddr: String?,
        @OneToOne(cascade = [CascadeType.ALL])
        @JoinColumn(name = "staking_skey", referencedColumnName = "id")
        val stakingSkey: File?,
        @OneToOne(cascade = [CascadeType.ALL])
        @JoinColumn(name = "staking_vkey", referencedColumnName = "id")
        val stakingVkey: File?
)