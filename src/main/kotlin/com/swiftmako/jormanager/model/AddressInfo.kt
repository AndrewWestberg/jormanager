package com.swiftmako.jormanager.model


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AddressInfo(
    @Json(name = "address")
    val address: String = "",
    @Json(name = "base16")
    val base16: String = "",
    @Json(name = "encoding")
    val encoding: String = "",
    @Json(name = "era")
    val era: String = "",
    @Json(name = "type")
    val type: String = ""
)