package com.swiftmako.jormanager.model.ekg2

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ConnectionManager(
    @param:Json(name = "duplexConns")
    val duplexConns: IntX = IntX(),
    @param:Json(name = "incomingConns")
    val incomingConns: IntX = IntX(),
    @param:Json(name = "outgoingConns")
    val outgoingConns: IntX = IntX(),
    @param:Json(name = "prunableConns")
    val prunableConns: IntX = IntX(),
    @param:Json(name = "unidirectionalConns")
    val unidirectionalConns: IntX = IntX(),
)
