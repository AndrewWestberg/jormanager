package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigInteger

@JsonClass(generateAdapter = true)
data class PoolLedgerParams(
    @param:Json(name = "cost")
    val cost: BigInteger,
    @param:Json(name = "margin")
    val margin: Double,
    @param:Json(name = "pledge")
    val pledge: BigInteger
)
