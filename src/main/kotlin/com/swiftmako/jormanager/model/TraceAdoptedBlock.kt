package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.swiftmako.jormanager.utils.toLocalTimeString
import org.joda.time.DateTime
import org.joda.time.DateTimeZone

@JsonClass(generateAdapter = true)
data class TraceAdoptedBlock(
    @param:Json(name = "at") val at: DateTime,
    @param:Json(name = "env") val env: String,
    @param:Json(name = "data") val data: AdoptedBlockVal,
    @param:Json(name = "host") val host: String
) {
    fun localTimeString(): String = at.toLocalTimeString()

    override fun toString(): String = "AdoptedBlock(at=${localTimeString()}, env='$env', data=$data, host='$host')"
}

@JsonClass(generateAdapter = true)
data class AdoptedBlockVal(
    @param:Json(name = "val") val block: Block
)

@JsonClass(generateAdapter = true)
data class Block(
    @param:Json(name = "slot") val slot: Long,
    @param:Json(name = "blockHash") val _hash: String? = null,
    @param:Json(name = "block hash") val _oldHash: String? = null
) {
    val hash: String
        get() = _hash ?: _oldHash!!

    /**
     * Returns just the raw hash string
     */
    fun rawHash() = hash.split(" ", "}").last { it.isNotBlank() }

    override fun toString(): String = "Block(slot=$slot, hash='${rawHash()}')"
}
