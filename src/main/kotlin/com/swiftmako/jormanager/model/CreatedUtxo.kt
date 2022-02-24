package com.swiftmako.jormanager.model

import java.math.BigInteger

data class CreatedUtxo(
    val address: String,
    val stakeAddress: String?,
    val hash: String,
    val ix: Long,
    val lovelace: BigInteger,
    val nativeAssets: List<NativeAsset>,
)
