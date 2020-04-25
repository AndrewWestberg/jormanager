package com.swiftmako.jormanager.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AdastatBlocks(
        @Json(name = "pool") val pool: String,
        @Json(name = "epoch") val epoch: Int,
        @Json(name = "blocks") val blocks: Int
)