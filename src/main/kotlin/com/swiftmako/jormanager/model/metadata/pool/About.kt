package com.swiftmako.jormanager.model.metadata.pool

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class About(
    @param:Json(name = "me")
    val me: String? = null,
    @param:Json(name = "server")
    val server: String? = null,
    @param:Json(name = "company")
    val company: String? = null
)
