package com.swiftmako.jormanager.model


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigInteger

@JsonClass(generateAdapter = true)
data class StakeAddressInfo(
        @Json(name = "address")
        val address: String,
        @Json(name = "delegation")
        val delegation: String? = null,
        @Json(name = "rewardAccountBalance")
        val rewardAccountBalance: BigInteger = BigInteger.ZERO
)