package com.swiftmako.jormanager.model


import com.fasterxml.jackson.annotation.JsonProperty
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class About(
        @JsonProperty("me") @Json(name = "me") val me: String?,
        @JsonProperty("server") @Json(name = "server") val server: String?,
        @JsonProperty("company") @Json(name = "company") val company: String?
)