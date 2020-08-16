package com.swiftmako.jormanager.model


import com.fasterxml.jackson.annotation.JsonProperty
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

data class Metadata(
        @JsonProperty("ticker")  val ticker: String?,
        @JsonProperty("name")  val name: String?,
        @JsonProperty("description")  val description: String?,
        @JsonProperty("homepage")  val homepage: String?,
        @JsonProperty("extended") val extended: Extended?
)