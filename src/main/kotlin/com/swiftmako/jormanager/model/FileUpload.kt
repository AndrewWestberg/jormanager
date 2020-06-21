package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class FileUpload(
        @Json(name = "name") val name: String,
        @Json(name = "content") val content: String
)