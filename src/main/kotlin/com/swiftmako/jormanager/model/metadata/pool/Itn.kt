package com.swiftmako.jormanager.model.metadata.pool


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Itn(
    @Json(name = "owner")
    val owner: String? = null,
    @Json(name = "witness")
    val witness: String? = null
)