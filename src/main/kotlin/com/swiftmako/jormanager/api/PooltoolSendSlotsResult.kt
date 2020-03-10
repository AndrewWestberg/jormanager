package com.swiftmako.jormanager.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PooltoolSendSlotsResult(
        @Json(name = "success") val success: Boolean,
        @Json(name = "message") val message: PooltoolMessage?
)