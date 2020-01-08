package com.swiftmako.jormanager.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BlockDetail(
        @Json(name = "block") val block: String,
        @Json(name = "chain_length") val chainLength: Long
)