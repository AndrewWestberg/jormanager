package com.swiftmako.jormanager.model.tx


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TxSigned(
    @Json(name = "cborHex")
    val cborHex: String
)