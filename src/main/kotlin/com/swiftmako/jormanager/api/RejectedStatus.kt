package com.swiftmako.jormanager.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RejectedStatus(
        @Json(name = "Rejected") val rejectedDetail: RejectedDetail
)