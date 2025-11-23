package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

data class Metadata(
    @param:JsonProperty("ticker") val ticker: String?,
    @param:JsonProperty("name") val name: String?,
    @param:JsonProperty("description") val description: String?,
    @param:JsonProperty("homepage") val homepage: String?,
    @param:JsonProperty("extended") val extended: Extended?
)
