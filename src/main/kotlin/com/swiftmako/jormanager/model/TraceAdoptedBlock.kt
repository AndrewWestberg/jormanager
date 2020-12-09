package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.swiftmako.jormanager.utils.toLocalTimeString
import org.joda.time.DateTime
import org.joda.time.DateTimeZone

@JsonClass(generateAdapter = true)
data class TraceAdoptedBlock(
    @Json(name = "at") val at: DateTime,
    @Json(name = "env") val env: String,
    @Json(name = "data") val data: AdoptedBlockVal,
    @Json(name = "host") val host: String
) {
    fun localTimeString(): String = at.toLocalTimeString()

    override fun toString(): String {
        return "AdoptedBlock(at=${localTimeString()}, env='$env', data=$data, host='$host')"
    }
}

@JsonClass(generateAdapter = true)
data class AdoptedBlockVal(
    @Json(name = "val") val block: Block
)

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