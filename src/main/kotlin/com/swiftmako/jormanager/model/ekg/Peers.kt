package com.swiftmako.jormanager.model.ekg

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Peers(
    @param:Json(name = "connectedPeers")
    val connectedPeers: ConnectedPeers = ConnectedPeers()
)
