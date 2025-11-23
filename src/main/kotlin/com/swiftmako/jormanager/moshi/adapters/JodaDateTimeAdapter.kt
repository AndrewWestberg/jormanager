package com.swiftmako.jormanager.moshi.adapters

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.ToJson
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat

class JodaDateTimeAdapter : JsonAdapter<DateTime>() {
    @FromJson
    override fun fromJson(reader: JsonReader): DateTime? {
        if (reader.peek() === JsonReader.Token.NULL) {
            return reader.nextNull()
        }
        val dateString = reader.nextString()
        return DateTime.parse(dateString)
    }

    @ToJson
    override fun toJson(
        writer: JsonWriter,
        value: DateTime?
    ) {
        if (value == null) {
            writer.nullValue()
        } else {
            writer.value(ISODateTimeFormat.basicDateTime().print(value))
        }
    }
}
