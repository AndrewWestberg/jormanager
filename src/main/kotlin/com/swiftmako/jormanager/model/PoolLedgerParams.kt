package com.swiftmako.jormanager.model


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PoolLedgerParams(
        @Json(name = "cost")
        val cost: Long,
        @Json(name = "margin")
        val margin: Double,
        @Json(name = "pledge")
        val pledge: Long
)