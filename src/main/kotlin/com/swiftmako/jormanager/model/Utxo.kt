package com.swiftmako.jormanager.model

import java.math.BigInteger

data class Utxo(
        val hash: String,
        val ix: Long,
        val lovelace: BigInteger,
        val nativeAssets: List<NativeAsset>,
)

fun List<Utxo>.toNativeAssetMap(): Map<String, BigInteger> {
    val nativeAssetMap = mutableMapOf<String, BigInteger>()
    this.forEach { utxo ->
        utxo.nativeAssets.forEach { nativeAsset ->
            val currency = "${nativeAsset.policy}.${nativeAsset.name}".trimEnd('.')
            val amount = (nativeAssetMap[currency] ?: BigInteger.ZERO) + nativeAsset.amount
            nativeAssetMap[currency] = amount
        }
    }

    return nativeAssetMap
}