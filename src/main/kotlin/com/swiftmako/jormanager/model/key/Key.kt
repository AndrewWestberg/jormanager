package com.swiftmako.jormanager.model.key

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Key(
    @param:Json(name = "type")
    val type: String,
    @param:Json(name = "description")
    val description: String,
    @param:Json(name = "cborHex")
    val cborHex: String
)
