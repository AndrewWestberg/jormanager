package com.swiftmako.jormanager.model.topology


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Topology(
    @Json(name = "Producers")
    val producers: List<Producer> = listOf()
)