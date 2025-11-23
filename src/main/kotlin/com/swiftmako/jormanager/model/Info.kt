package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty

data class Info(
    @param:JsonProperty("icon64") val icon64: String?,
    @param:JsonProperty("logo") val logo: String?,
    @param:JsonProperty("location") val location: String?,
    @param:JsonProperty("social") val social: Social?,
    @param:JsonProperty("company") val company: Company?,
    @param:JsonProperty("about") val about: About?,
    @param:JsonProperty("rss") val rss: String?
)
