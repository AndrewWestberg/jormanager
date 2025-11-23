package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigInteger

@JsonClass(generateAdapter = true)
data class ToAccount(
    @param:Json(name = "currency")
    val currency: String,
    @param:Json(name = "account")
    val account: Long,
    @param:Json(name = "address")
    val address: String,
    @param:Json(name = "amount")
    val amount: BigInteger?,
    @param:Json(name = "percent")
    val percent: Int?,
    @param:Json(name = "tokenFee")
    val tokenFee: BigInteger,
) {
    val isAddress by lazy { account < 0 && address.isNotBlank() }
}
