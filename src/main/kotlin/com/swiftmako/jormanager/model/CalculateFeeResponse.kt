package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigInteger

@JsonClass(generateAdapter = true)
data class CalculateFeeResponse(
    @param:Json(name = "txFee")
    val txFee: BigInteger,
    @param:Json(name = "tokenFees")
    val tokenFees: List<BigInteger>,
    @param:Json(name = "tokenKeepFee")
    val tokenKeepFee: BigInteger,
    @param:Json(name = "tokenLocked")
    val tokenLocked: BigInteger,
    @param:Json(name = "uuid")
    val uuid: String,
)
