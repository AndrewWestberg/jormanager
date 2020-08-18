package com.swiftmako.jormanager.model


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class QueryTip(
    @Json(name = "blockNo")
    val blockNo: Long = 0,
    @Json(name = "headerHash")
    val headerHash: String = "",
    @Json(name = "slotNo")
    val slotNo: Long = 0
)