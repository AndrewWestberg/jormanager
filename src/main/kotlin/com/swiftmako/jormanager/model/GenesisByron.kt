package com.swiftmako.jormanager.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GenesisByron(
    val startTime: Long,
    val protocolConsts: ProtocolConsts,
    val blockVersionData: BlockVersionData,
)

@JsonClass(generateAdapter = true)
data class ProtocolConsts(
    val k: Long,
)

@JsonClass(generateAdapter = true)
data class BlockVersionData(
    val slotDuration: Long,
)
