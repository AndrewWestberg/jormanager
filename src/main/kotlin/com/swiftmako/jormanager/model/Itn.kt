package com.swiftmako.jormanager.model


import com.fasterxml.jackson.annotation.JsonProperty
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Itn(
        @JsonProperty("publicKey") @Json(name = "publicKey") val publicKey: String?,
        @JsonProperty("privateKey") @Json(name = "privateKey") val privateKey: String?
)