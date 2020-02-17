package com.swiftmako.jormanager.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PooltoolStatsResult(
        @Json(name = "majoritymax") val majoritymax: Long,
        @Json(name = "max") val max: Long
)
