package com.swiftmako.jormanager.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Genesis(
        val activeSlotsCoeff: Double,
        val networkId: String,
        val networkMagic: Long? = null,
        val slotLength: Long,
        val epochLength: Long,
        val slotsPerKESPeriod: Long,
        val systemStart: String,
        val maxKESEvolutions: Long,
)