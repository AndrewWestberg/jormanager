package com.swiftmako.jormanager.model


import com.fasterxml.jackson.annotation.JsonProperty

data class About(
        @JsonProperty("me") val me: String?,
        @JsonProperty("server") val server: String?,
        @JsonProperty("company") val company: String?
)