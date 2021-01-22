package com.swiftmako.jormanager.model.ekg2


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Forge(
    @Json(name = "forge-about-to-lead")
    val forgeAboutToLead: ForgeAboutToLead = ForgeAboutToLead(),
    @Json(name = "node-not-leader")
    val nodeNotLeader: NodeNotLeader = NodeNotLeader()
)