package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigInteger

@JsonClass(generateAdapter = true)
data class StakeAddressInfo(
    @param:Json(name = "address")
    val address: String,
    @param:Json(name = "delegation")
    val delegation: String? = null,
    @param:Json(name = "rewardAccountBalance")
    val rewardAccountBalance: BigInteger = BigInteger.ZERO
)
