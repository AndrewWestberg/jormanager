package com.swiftmako.jormanager.model.metadata

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class MetadataPostInfo(
    @param:Json(name = "fields")
    val fields: Fields,
    @param:Json(name = "url")
    val url: String
)
