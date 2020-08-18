package com.swiftmako.jormanager.model.pooltool


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PooltoolResponse(
        @Json(name = "success")
        val success: Boolean,
)