package com.swiftmako.jormanager.model.metadata.pool


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Metadata(
        @Json(name = "name")
        val name: String,
        @Json(name = "description")
        val description: String,
        @Json(name = "ticker")
        val ticker: String,
        @Json(name = "homepage")
        val homepage: String,
        @Json(name = "extended")
        val extended: String
)