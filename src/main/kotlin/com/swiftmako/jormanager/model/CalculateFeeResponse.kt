package com.swiftmako.jormanager.model

import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigInteger

@JsonClass(generateAdapter = true)
data class CalculateFeeResponse(
    @param:Json(name = "txFee")
    @get:JsonSerialize(using = ToStringSerializer::class)
    val txFee: BigInteger,
    @param:Json(name = "tokenFees")
    @get:JsonSerialize(contentUsing = ToStringSerializer::class)
    val tokenFees: List<BigInteger>,
    @param:Json(name = "tokenKeepFee")
    @get:JsonSerialize(using = ToStringSerializer::class)
    val tokenKeepFee: BigInteger,
    @param:Json(name = "tokenLocked")
    @get:JsonSerialize(using = ToStringSerializer::class)
    val tokenLocked: BigInteger,
    @param:Json(name = "uuid")
    val uuid: String,
)
