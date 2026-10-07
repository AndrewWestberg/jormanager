package com.swiftmako.jormanager.moshi.adapters

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.ToJson
import java.math.BigDecimal

object BigDecimalAdapter {
    @FromJson
    fun fromJson(reader: JsonReader): BigDecimal = BigDecimal(reader.nextString())

    @ToJson
    fun toJson(writer: JsonWriter, value: BigDecimal) {
        writer.value(value)
    }
}
