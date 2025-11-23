package com.swiftmako.jormanager.model.pooltool

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PooltoolStats(
    @param:Json(name = "apiKey")
    val apiKey: String,
    @param:Json(name = "poolId")
    val poolId: String,
    @param:Json(name = "data")
    val data: Data,
) {
    override fun toString(): String = "PooltoolStats(apiKey='${apiKey.substring(0..8)}...', poolId='${poolId.substring(0..8)}...', data=$data)"
}

@JsonClass(generateAdapter = true)
data class Data(
    @param:Json(name = "nodeId")
    val nodeId: String,
    @param:Json(name = "version")
    val version: String,
    @param:Json(name = "at")
    val at: String,
    @param:Json(name = "blockNo")
    val blockNo: Long,
    @param:Json(name = "slotNo")
    val slotNo: Long,
    @param:Json(name = "blockHash")
    val blockHash: String,
    @param:Json(name = "parentHash")
    val parentHash: String,
    @param:Json(name = "leaderVrf")
    val leaderVrf: String,
    @param:Json(name = "blockVrf")
    val blockVrf: String,
    @param:Json(name = "blockVrfProof")
    val blockVrfProof: String,
    @param:Json(name = "platform")
    val platform: String = "JorManager",
    @param:Json(name = "nodeVKey")
    val nodeVKey: String,
) {
    override fun toString(): String = "Data(nodeId='$nodeId', version='$version', at='$at', blockNo=$blockNo, slotNo=$slotNo, blockHash='${blockHash.substring(0..8)}...', parentHash='${parentHash.substring(0..8)}...', leaderVrf='${leaderVrf.substring(0..8)}...', nodeVKey='${nodeVKey.substring(0..8)}...', platform='$platform')"
}
