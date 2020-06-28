package com.swiftmako.jormanager.model.ekg


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class NodeMetrics(
    @Json(name = "Sys")
    val sys: Sys = Sys()
)