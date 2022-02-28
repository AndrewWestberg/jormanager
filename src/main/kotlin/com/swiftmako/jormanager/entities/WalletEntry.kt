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
        @GeneratedValue(strategy = GenerationType.SEQUENCE)
        val id: Long? = null,
        @Column(name = "name")
        val name: String,
        @Column(name = "type")
        val type: String, // payment, stake, address
        @Column(name = "payment_addr")
        val paymentAddr: String,
        @OneToOne(cascade = [CascadeType.ALL])
        @JoinColumn(name = "payment_skey", referencedColumnName = "id")
        val paymentSkey: File?=null,
        @OneToOne(cascade = [CascadeType.ALL])
        @JoinColumn(name = "payment_vkey", referencedColumnName = "id")
        val paymentVkey: File?=null,
        @Column(name = "staking_addr")
        val stakingAddr: String?=null,
        @OneToOne(cascade = [CascadeType.ALL])
        @JoinColumn(name = "staking_skey", referencedColumnName = "id")
        val stakingSkey: File?=null,
        @OneToOne(cascade = [CascadeType.ALL])
        @JoinColumn(name = "staking_vkey", referencedColumnName = "id")
        val stakingVkey: File?=null,
        @OneToOne(cascade = [CascadeType.ALL])
        @JoinColumn(name = "staking_reg_cert", referencedColumnName = "id")
        val stakingRegCert: File?=null,
        @Column(name = "deleted")
        val deleted: Boolean = false
) {
}