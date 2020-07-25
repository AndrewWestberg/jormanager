package com.swiftmako.jormanager.model


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CalculateFeeRequest(
        @Json(name = "fromId")
        val fromId: Long,
        @Json(name = "toAccounts")
        val toAccounts: List<Long?>,
        @Json(name = "txOut")
        val txOut: Int,
        @Json(name = "isClaim")
        val isClaim: Boolean
)