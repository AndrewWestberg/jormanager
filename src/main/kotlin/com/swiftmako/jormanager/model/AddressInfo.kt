package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AddressInfo(
    @param:Json(name = "address")
    val address: String = "",
    @param:Json(name = "base16")
    val base16: String = "",
    @param:Json(name = "encoding")
    val encoding: String = "",
    @param:Json(name = "era")
    val era: String = "",
    @param:Json(name = "type")
    val type: String = ""
)
