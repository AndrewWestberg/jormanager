package com.swiftmako.jormanager.model.ekg


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ChainDB(
    @Json(name = "metrics")
    val metrics: Metrics = Metrics()
)