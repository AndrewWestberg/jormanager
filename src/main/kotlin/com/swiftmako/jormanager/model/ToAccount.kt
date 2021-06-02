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
        @Json(name = "address")
        val address: String,
        @Json(name = "amount")
        val amount: BigInteger?,
        @Json(name = "percent")
        val percent: Int?,
        @Json(name = "tokenFee")
        val tokenFee: BigInteger,
) {
    val isAddress by lazy { account < 0 && address.isNotBlank() }
}