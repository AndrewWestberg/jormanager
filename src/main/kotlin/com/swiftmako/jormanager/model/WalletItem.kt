package com.swiftmako.jormanager.model

data class WalletItem(
        val id: Long,
        val name: String,
        val type: String, // payment, stake, address
        val paymentAddr: String,
        val paymentAddrUtxoCount: Long,
        val paymentAddrLovelace: Long,
        val stakingAddr: String?,
        val stakingAddrLovelace: Long?
)