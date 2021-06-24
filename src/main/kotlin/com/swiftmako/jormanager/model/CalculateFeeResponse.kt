package com.swiftmako.jormanager.model


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigInteger

@JsonClass(generateAdapter = true)
data class CalculateFeeResponse(
    @Json(name = "txFee")
    val txFee: BigInteger,
    @Json(name = "tokenFees")
    val tokenFees: List<BigInteger>,
    @Json(name = "tokenKeepFee")
    val tokenKeepFee: BigInteger,
    @Json(name = "tokenLocked")
    val tokenLocked: BigInteger,
    @Json(name = "uuid")
    val uuid: String,
)