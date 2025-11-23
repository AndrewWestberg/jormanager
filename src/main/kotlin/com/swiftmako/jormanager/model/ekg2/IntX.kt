package com.swiftmako.jormanager.model.ekg2

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class IntX(
    @param:Json(name = "type")
    val type: String = "",
    @param:Json(name = "val")
    val valX: Long = 0L
)
