package com.swiftmako.jormanager.model


import com.fasterxml.jackson.annotation.JsonProperty

data class Social(
        @JsonProperty("twitter") val twitter: String?,
        @JsonProperty("telegram") val telegram: String?,
        @JsonProperty("facebook") val facebook: String?,
        @JsonProperty("youtube") val youtube: String?,
        @JsonProperty("discord") val discord: String?,
        @JsonProperty("github") val github: String?,
        @JsonProperty("twitch") val twitch: String?
)