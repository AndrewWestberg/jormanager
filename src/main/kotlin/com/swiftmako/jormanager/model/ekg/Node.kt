package com.swiftmako.jormanager.model.ekg

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Node(
    @param:Json(name = "BlockFetchDecision")
    val blockFetchDecision: BlockFetchDecision = BlockFetchDecision(),
    @param:Json(name = "ChainDB")
    val chainDB: ChainDB = ChainDB(),
    @param:Json(name = "metrics")
    val metrics: MetricsX = MetricsX(),
    @param:Json(name = "Forge")
    val forge: Forge = Forge(),
)
