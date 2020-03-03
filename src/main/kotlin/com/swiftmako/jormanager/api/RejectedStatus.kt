package com.swiftmako.jormanager.api

import com.fasterxml.jackson.annotation.JsonProperty
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RejectedStatus(
        @JsonProperty("Rejected")
        @Json(name = "Rejected") val rejectedDetail: RejectedDetail
)