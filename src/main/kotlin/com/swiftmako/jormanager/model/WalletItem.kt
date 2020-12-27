package com.swiftmako.jormanager.model

data class WalletItem(
        val id: Long,
        val name: String,
        val type: String, // payment, stake, address
        val paymentAddr: String,
        val hasPaymentKeys: Boolean,
        val paymentAddrUtxoCount: Long,
        val paymentAddrLovelace: Long,
        val stakingAddr: String?,
        val stakingAddrRegistered: Boolean,
        val stakingAddrLovelace: Long?,
        val nativeAssetMap: Map<String, Long>,
)