package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty

data class Social(
    @param:JsonProperty("twitter") val twitter: String?,
    @param:JsonProperty("telegram") val telegram: String?,
    @param:JsonProperty("facebook") val facebook: String?,
    @param:JsonProperty("youtube") val youtube: String?,
    @param:JsonProperty("discord") val discord: String?,
    @param:JsonProperty("github") val github: String?,
    @param:JsonProperty("twitch") val twitch: String?
)
