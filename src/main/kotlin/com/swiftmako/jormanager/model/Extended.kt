package com.swiftmako.jormanager.model


import com.fasterxml.jackson.annotation.JsonProperty
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Extended(
        @JsonProperty("itn") @Json(name = "itn") val itn: Itn?,
        @JsonProperty("info") @Json(name = "info") val info: Info?,
        @JsonProperty("telegramAdminHandle") @Json(name = "telegramAdminHandle") val telegramAdminHandle: String?
)