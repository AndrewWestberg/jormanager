package com.swiftmako.jormanager.model.ekg


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Node(
    @Json(name = "BlockFetchDecision")
    val blockFetchDecision: BlockFetchDecision = BlockFetchDecision(),
    @Json(name = "ChainDB")
    val chainDB: ChainDB = ChainDB(),
    @Json(name = "metrics")
    val metrics: MetricsX = MetricsX(),
    @Json(name = "Forge")
    val forge: Forge = Forge(),
)