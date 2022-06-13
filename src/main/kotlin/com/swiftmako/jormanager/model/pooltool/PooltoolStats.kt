package com.swiftmako.jormanager.model.pooltool

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PooltoolStats(
        @Json(name = "apiKey")
        val apiKey: String,
        @Json(name = "poolId")
        val poolId: String,
        @Json(name = "data")
        val data: Data,
) {
    override fun toString(): String {
        return "PooltoolStats(apiKey='${apiKey.substring(0..8)}...', poolId='${poolId.substring(0..8)}...', data=$data)"
    }
}

@JsonClass(generateAdapter = true)
data class Data(
        @Json(name = "nodeId")
        val nodeId: String,
        @Json(name = "version")
        val version: String,
        @Json(name = "at")
        val at: String,
        @Json(name = "blockNo")
        val blockNo: Long,
        @Json(name = "slotNo")
        val slotNo: Long,
        @Json(name = "blockHash")
        val blockHash: String,
        @Json(name = "parentHash")
        val parentHash: String,
        @Json(name = "leaderVrf")
        val leaderVrf: String,
        @Json(name = "blockVrf")
        val blockVrf: String,
        @Json(name = "blockVrfProof")
        val blockVrfProof: String,
        @Json(name = "platform")
        val platform: String = "JorManager",
        @Json(name = "nodeVKey")
        val nodeVKey: String,
) {
    override fun toString(): String {
        return "Data(nodeId='$nodeId', version='$version', at='$at', blockNo=$blockNo, slotNo=$slotNo, blockHash='${blockHash.substring(0..8)}...', parentHash='${parentHash.substring(0..8)}...', leaderVrf='${leaderVrf.substring(0..8)}...', nodeVKey='${nodeVKey.substring(0..8)}...', platform='$platform')"
    }
}
