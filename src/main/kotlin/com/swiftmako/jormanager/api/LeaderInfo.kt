package com.swiftmako.jormanager.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class LeaderInfo(
        @Json(name = "genesis") val genesis: Genesis
)
