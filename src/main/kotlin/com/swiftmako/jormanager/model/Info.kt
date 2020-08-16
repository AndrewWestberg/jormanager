package com.swiftmako.jormanager.model


import com.fasterxml.jackson.annotation.JsonProperty

data class Info(
        @JsonProperty("icon64") val icon64: String?,
        @JsonProperty("logo") val logo: String?,
        @JsonProperty("location") val location: String?,
        @JsonProperty("social") val social: Social?,
        @JsonProperty("company") val company: Company?,
        @JsonProperty("about") val about: About?,
        @JsonProperty("rss") val rss: String?
)