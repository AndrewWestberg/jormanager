package com.swiftmako.jormanager.model

import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import java.math.BigInteger

data class WalletItem(
    val id: Long,
    val name: String,
    val type: String, // payment, stake, address
    val paymentAddr: String,
    val hasPaymentKeys: Boolean,
    val paymentAddrUtxoCount: Long,
    @get:JsonSerialize(using = ToStringSerializer::class)
    val paymentAddrLovelace: BigInteger,
    val stakingAddr: String?,
    val stakingAddrRegistered: Boolean,
    @get:JsonSerialize(using = ToStringSerializer::class)
    val stakingAddrLovelace: BigInteger?,
    @get:JsonSerialize(contentUsing = ToStringSerializer::class)
    val nativeAssetMap: Map<String, BigInteger>,
)
