package com.swiftmako.jormanager.model


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class StakeAddressInfo(
        @Json(name = "delegation")
        val delegation: String? = null,
        @Json(name = "rewardAccountBalance")
        val rewardAccountBalance: Long = 0
)