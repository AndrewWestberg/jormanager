package com.swiftmako.jormanager.model


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CalculateFeeRequest(
        @Json(name = "fromAddress")
        val fromAddress: String,
        @Json(name = "txOut")
        val txOut: Int
)