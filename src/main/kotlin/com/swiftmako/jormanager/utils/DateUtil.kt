package com.swiftmako.jormanager.utils

import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormatterBuilder

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

fun DateTime.toLocalTimeString(): String {
    return localDateTimeFormat.print(this.withZone(DateTimeZone.getDefault()))
}
