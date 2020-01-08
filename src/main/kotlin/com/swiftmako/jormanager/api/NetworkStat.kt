package com.swiftmako.jormanager.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class NetworkStat(
        @Json(name = "addr") val addr: String?,
        @Json(name = "establishedAt") val establishedAt: String?,
        @Json(name = "lastBlockReceived") val lastBlockReceived: String?,
        @Json(name = "lastFragmentReceived") val lastFragmentReceived: String?,
        @Json(name = "lastGossipReceived") val lastGossipReceived: String?,
        @Json(name = "nodeId") val nodeId: String?
)