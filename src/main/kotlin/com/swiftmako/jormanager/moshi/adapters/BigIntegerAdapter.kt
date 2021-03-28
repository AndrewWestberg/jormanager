package com.swiftmako.jormanager.moshi.adapters

import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import java.math.BigInteger

object BigIntegerAdapter {
    @FromJson
    fun fromJson(string: String) = BigInteger(string.substringBefore('.'))

    @ToJson
    fun toJson(value: BigInteger) = value.toString()
}
