package com.swiftmako.jormanager.model


import com.fasterxml.jackson.annotation.JsonProperty
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Relay(
        @JsonProperty("addr") @Json(name = "addr") val addr: String,
        @JsonProperty("port") @Json(name = "port") val port: Int
)