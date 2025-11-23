package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Relay(
    @param:JsonProperty("addr") @param:Json(name = "addr") val addr: String,
    @param:JsonProperty("port") @param:Json(name = "port") val port: Int
)
