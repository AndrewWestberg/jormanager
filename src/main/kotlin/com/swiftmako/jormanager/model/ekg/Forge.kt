package com.swiftmako.jormanager.model.ekg

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Forge(
    @Json(name="metrics")
    val metrics: MetricsY = MetricsY()
)