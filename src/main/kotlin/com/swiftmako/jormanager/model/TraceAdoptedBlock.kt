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
        @Json(name = "data") val block: Block,
        @Json(name = "host") val host: String
) {
    fun localTimeString(): String = at.toLocalTimeString()

    override fun toString(): String {
        return "AdoptedBlock(at=${localTimeString()}, env='$env', block=$block, host='$host')"
    }
}