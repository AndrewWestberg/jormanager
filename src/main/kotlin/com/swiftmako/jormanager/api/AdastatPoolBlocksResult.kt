package com.swiftmako.jormanager.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AdastatPoolBlocksResult(
        @Json(name = "res") val res: Boolean,
        @Json(name = "code") val code: Int,
        @Json(name = "desc") val desc: String
)