package com.swiftmako.jormanager.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class OutputStats(
        @Json(name = "pooltool") val pooltoolResult: PooltoolResult? = null,
        @Json(name = "nodes") val nodeStats: Map<Int, Stats>
)