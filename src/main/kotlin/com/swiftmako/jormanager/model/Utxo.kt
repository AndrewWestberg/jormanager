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
            val key = "${nativeAsset.name}.${nativeAsset.policy}"
            val amount = (nativeAssetMap[key] ?: 0L) + nativeAsset.amount
            nativeAssetMap[key] = amount
        }
    }

    return nativeAssetMap
}