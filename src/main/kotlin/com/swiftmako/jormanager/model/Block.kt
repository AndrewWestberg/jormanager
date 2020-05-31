package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Block(
        @Json(name = "slot") val slot: Long,
        @Json(name = "block hash") val hash: String
) {
    /**
     * Returns just the raw hash string
     */
    fun rawHash() = hash.split(" ", "}").last { it.isNotBlank() }

    override fun toString(): String {
        return "Block(slot=$slot, hash='${rawHash()}')"
    }
}
