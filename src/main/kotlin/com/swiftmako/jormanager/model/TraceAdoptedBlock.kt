package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormatterBuilder

@JsonClass(generateAdapter = true)
data class TraceAdoptedBlock(
        @Json(name = "at") val at: DateTime,
        @Json(name = "env") val env: String,
        @Json(name = "data") val block: Block,
        @Json(name = "host") val host: String
) {
    override fun toString(): String {
        return "AdoptedBlock(at=${localDateTimeFormat.print(at.withZone(DateTimeZone.getDefault()))}, env='$env', block=$block, host='$host')"
    }

    companion object {
        private val localDateTimeFormat = DateTimeFormatterBuilder()
                .appendYear(4, 4)
                .appendLiteral('-')
                .appendMonthOfYear(2)
                .appendLiteral('-')
                .appendDayOfMonth(2)
                .appendLiteral('T')
                .appendClockhourOfHalfday(2)
                .appendLiteral(':')
                .appendMinuteOfHour(2)
                .appendLiteral(':')
                .appendSecondOfMinute(2)
                .appendLiteral('.')
                .appendMillisOfSecond(3)
                .appendLiteral(' ')
                .appendHalfdayOfDayText()
                .appendLiteral(' ')
                .appendTimeZoneShortName()
                .toFormatter()
    }
}