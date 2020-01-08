package com.swiftmako.jormanager.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Genesis(
        @Json(name = "node_id") val nodeId: String,
        @Json(name = "vrf_key") val vrfKey: String,
        @Json(name = "sig_key") val sigKey: String
)