package com.swiftmako.jormanager.model


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigInteger

@JsonClass(generateAdapter = true)
data class ToAccount(
        @Json(name = "currency")
        val currency: String,
        @Json(name = "account")
        val account: Long,
        @Json(name = "amount")
        val amount: BigInteger?,
        @Json(name = "percent")
        val percent: Int?,
        @Json(name = "type")
        val type: String,
        @Json(name = "tokenFee")
        val tokenFee: BigInteger,
)