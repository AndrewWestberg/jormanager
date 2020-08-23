package com.swiftmako.jormanager.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Genesis(
        val networkMagic: Int? = null,
        val slotLength: Long,
        val epochLength: Long,
        val slotsPerKESPeriod: Long,
        val systemStart: Long,
        val maxKESEvolutions: Long,
)