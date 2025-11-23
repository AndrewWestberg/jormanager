package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Config(
    @param:Json(name = "ShelleyGenesisHash")
    val shelleyGenesisHash: String
)
