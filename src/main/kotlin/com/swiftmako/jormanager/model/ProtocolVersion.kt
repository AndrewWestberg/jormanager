package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ProtocolVersion(
    @param:Json(name = "minor")
    val minor: Int,
    @param:Json(name = "major")
    val major: Int
)
