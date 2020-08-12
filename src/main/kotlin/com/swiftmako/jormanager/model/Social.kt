package com.swiftmako.jormanager.model


import com.fasterxml.jackson.annotation.JsonProperty
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Social(
        @JsonProperty("twitter") @Json(name = "twitter") val twitter: String?,
        @JsonProperty("telegram") @Json(name = "telegram") val telegram: String?,
        @JsonProperty("facebook") @Json(name = "facebook") val facebook: String?,
        @JsonProperty("youtube") @Json(name = "youtube") val youtube: String?,
        @JsonProperty("discord") @Json(name = "discord") val discord: String?,
        @JsonProperty("github") @Json(name = "github") val github: String?,
        @JsonProperty("twitch") @Json(name = "twitch") val twitch: String?
)