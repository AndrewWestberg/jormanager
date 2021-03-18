package com.swiftmako.jormanager.model


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class QueryTip(
        @Json(name = "epoch")
        val epoch: Long = 0,
        @Json(name = "block")
        val block: Long = 0,
        @Json(name = "hash")
        val hash: String = "",
        @Json(name = "slot")
        val slot: Long = 0
)