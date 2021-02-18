package com.swiftmako.jormanager.model


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SmashExistsResponse(
        @Json(name = "code")
        val code: String = "",
        @Json(name = "description")
        val description: String = "",
        @Json(name = "poolId")
        val poolId: String = ""
) {
    fun poolExists(): Boolean = poolId.isNotBlank() && code.isBlank()
}