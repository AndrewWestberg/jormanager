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
)

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
        @Json(name = "platform")
        val platform: String = "JorManager",
)