package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class File(
    @param:Json(name = "value") val value: Long,
    @param:Json(name = "text") val text: String
)
