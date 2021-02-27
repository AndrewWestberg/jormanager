package com.swiftmako.jormanager.model

import java.math.BigInteger

data class WalletItem(
        val id: Long,
        val name: String,
        val type: String, // payment, stake, address
        val paymentAddr: String,
        val hasPaymentKeys: Boolean,
        val paymentAddrUtxoCount: Long,
        val paymentAddrLovelace: BigInteger,
        val stakingAddr: String?,
        val stakingAddrRegistered: Boolean,
        val stakingAddrLovelace: BigInteger?,
        val nativeAssetMap: Map<String, BigInteger>,
)