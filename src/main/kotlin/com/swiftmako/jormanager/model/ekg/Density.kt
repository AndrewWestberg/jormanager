package com.swiftmako.jormanager.model.ekg

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Density(
    @param:Json(name = "real")
    val real: Real = Real()
)
