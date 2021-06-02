package com.swiftmako.jormanager.model


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigInteger

@JsonClass(generateAdapter = true)
data class ToAccountFeeRequest(
    @Json(name = "currency")
    val currency: String,
    @Json(name = "account")
    val account: Long,
    @Json(name = "address")
    val address: String,
    @Json(name = "amount")
    val amount: BigInteger?,
) {
    val isAddress by lazy { account < 0 && address.isNotBlank() }
}