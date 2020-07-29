package com.swiftmako.jormanager.model.topology


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Producer(
        @Json(name = "addr")
        val addr: String,
        @Json(name = "continent")
        val continent: String? = null,
        @Json(name = "port")
        val port: Int,
        @Json(name = "valency")
        val valency: Int = 1,
        @Json(name = "state")
        val state: String? = null,
        @Json(name = "latency")
        var latency: Long = Long.MAX_VALUE
)