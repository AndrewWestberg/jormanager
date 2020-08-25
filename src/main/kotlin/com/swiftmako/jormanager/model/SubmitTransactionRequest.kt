package com.swiftmako.jormanager.model


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SubmitTransactionRequest(
        @Json(name = "spendingPassword")
        val spendingPassword: String,
        @Json(name = "fromId")
        val fromId: Long,
        @Json(name = "txFee")
        val txFee: Int,
        @Json(name = "toAccounts")
        val toAccounts: List<ToAccount>,
        @Json(name = "isClaim")
        val isClaim: Boolean
)