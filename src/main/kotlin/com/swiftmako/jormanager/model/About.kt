package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty

data class About(
    @param:JsonProperty("me") val me: String?,
    @param:JsonProperty("server") val server: String?,
    @param:JsonProperty("company") val company: String?
)
