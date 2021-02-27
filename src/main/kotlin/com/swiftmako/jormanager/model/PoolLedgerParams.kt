package com.swiftmako.jormanager.model


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigInteger

@JsonClass(generateAdapter = true)
data class PoolLedgerParams(
        @Json(name = "cost")
        val cost: BigInteger,
        @Json(name = "margin")
        val margin: Double,
        @Json(name = "pledge")
        val pledge: BigInteger
)