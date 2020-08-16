package com.swiftmako.jormanager.model


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ProtocolVersion(
    @Json(name = "minor")
    val minor: Int,
    @Json(name = "major")
    val major: Int
)