package com.swiftmako.jormanager.model.ekg

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Cardano(
    @param:Json(name = "node")
    val node: Node = Node(),
    @param:Json(name = "node-metrics")
    val nodeMetrics: NodeMetrics = NodeMetrics()
)
