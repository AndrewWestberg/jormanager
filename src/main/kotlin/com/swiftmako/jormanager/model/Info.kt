package com.swiftmako.jormanager.model


import com.fasterxml.jackson.annotation.JsonProperty
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Info(
        @JsonProperty("icon64") @Json(name = "icon64") val icon64: String?,
        @JsonProperty("logo") @Json(name = "logo") val logo: String?,
        @JsonProperty("location") @Json(name = "location") val location: String?,
        @JsonProperty("social") @Json(name = "social") val social: Social?,
        @JsonProperty("company") @Json(name = "company") val company: Company?,
        @JsonProperty("about") @Json(name = "about") val about: About?,
        @JsonProperty("rss") @Json(name = "rss") val rss: String?
)