package com.swiftmako.jormanager.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PooltoolResult(
        @Json(name = "success") val success: Boolean,
        @Json(name = "error") val error: String?,
        @Json(name = "pooltoolmax") val pooltoolmax: Long?,
        @Json(name = "confidence") val confidence: Boolean?
)
