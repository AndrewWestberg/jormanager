package com.swiftmako.jormanager.model.metadata.pool


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class About(
    @Json(name = "me")
    val me: String? = null,
    @Json(name = "server")
    val server: String? = null,
    @Json(name = "company")
    val company: String? = null
)