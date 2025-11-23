package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigInteger

@JsonClass(generateAdapter = true)
data class ToAccountFeeRequest(
    @param:Json(name = "currency")
    val currency: String,
    @param:Json(name = "account")
    val account: Long,
    @param:Json(name = "address")
    val address: String,
    @param:Json(name = "amount")
    val amount: BigInteger?,
) {
    val isAddress by lazy { account < 0 && address.isNotBlank() }
}
