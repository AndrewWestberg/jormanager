package com.swiftmako.jormanager.model.key


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Key(
        @Json(name = "type")
        val type: String,
        @Json(name = "description")
        val description: String,
        @Json(name = "cborHex")
        val cborHex: String
)