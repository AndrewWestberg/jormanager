package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class FileUpload(
    @param:JsonProperty("name") @param:Json(name = "name") val name: String,
    @param:JsonProperty("content") @param:Json(name = "content") val content: String
)
