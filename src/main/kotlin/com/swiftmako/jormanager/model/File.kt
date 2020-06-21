package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class File(
        @Json(name = "value") val value: Long,
        @Json(name = "text") val text: String
)