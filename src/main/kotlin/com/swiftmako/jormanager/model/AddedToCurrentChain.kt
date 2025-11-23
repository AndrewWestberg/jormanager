package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AddedToCurrentChain(
    @param:Json(name = "at")
    val at: String,
    @param:Json(name = "env")
    val env: String,
)
