package com.swiftmako.jormanager.model

data class Utxo(
        val hash: String,
        val ix: Long,
        val lovelace: Long,
        val nativeAssets: List<NativeAsset>,
)

fun List<Utxo>.toNativeAssetMap(): Map<String, Long> {
    val nativeAssetMap = mutableMapOf<String, Long>()
    this.forEach { utxo ->
        utxo.nativeAssets.forEach { nativeAsset ->
            val currency = "${nativeAsset.policy}.${nativeAsset.name}".trimEnd('.')
            val amount = (nativeAssetMap[currency] ?: 0L) + nativeAsset.amount
            nativeAssetMap[currency] = amount
        }
    }

    return nativeAssetMap
}