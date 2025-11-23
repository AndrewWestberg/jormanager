package com.swiftmako.jormanager.model.metadata.pool

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Metadata(
    @param:Json(name = "name")
    val name: String,
    @param:Json(name = "description")
    val description: String,
    @param:Json(name = "ticker")
    val ticker: String,
    @param:Json(name = "homepage")
    val homepage: String,
    @param:Json(name = "extended")
    val extended: String
)
