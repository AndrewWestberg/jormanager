package com.swiftmako.jormanager.api

import com.fasterxml.jackson.annotation.JsonProperty
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class OutputStats(
        @JsonProperty("pooltool")
        @Json(name = "pooltool")
        val pooltoolResult: PooltoolResult? = null,

        @JsonProperty("nodes")
        @Json(name = "nodes")
        val nodeStats: Map<Int, Stats>
)