package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Block(
        @Json(name = "slot") val slot: Long,
        @Json(name = "blockHash") val _hash: String? = null,
        @Json(name = "block hash") val _oldHash: String? = null
) {
    val hash: String
        get() = _hash ?: _oldHash!!

    /**
     * Returns just the raw hash string
     */
    fun rawHash() = hash.split(" ", "}").last { it.isNotBlank() }

    override fun toString(): String {
        return "Block(slot=$slot, hash='${rawHash()}')"
    }
}
