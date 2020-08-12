package com.swiftmako.jormanager.model


import com.fasterxml.jackson.annotation.JsonProperty
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Metadata(
        @JsonProperty("ticker") @Json(name = "ticker") val ticker: String?,
        @JsonProperty("name") @Json(name = "name") val name: String?,
        @JsonProperty("description") @Json(name = "description") val description: String?,
        @JsonProperty("homepage") @Json(name = "homepage") val homepage: String?,
        @JsonProperty("extended") @Json(name = "extended") val extended: Extended?
)