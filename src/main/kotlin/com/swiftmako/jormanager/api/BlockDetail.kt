package com.swiftmako.jormanager.api

import com.fasterxml.jackson.annotation.JsonProperty
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BlockDetail(
        @JsonProperty("block")
        @Json(name = "block") val block: String,
        @JsonProperty("chain_length")
        @Json(name = "chain_length") val chainLength: Long
)