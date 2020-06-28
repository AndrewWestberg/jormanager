package com.swiftmako.jormanager.model.ekg


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Real(
    @Json(name = "val")
    val valX: Double = 0.0
)