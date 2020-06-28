package com.swiftmako.jormanager.model.ekg


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class BlockFetchDecision(
    @Json(name = "peers")
    val peers: Peers = Peers()
)