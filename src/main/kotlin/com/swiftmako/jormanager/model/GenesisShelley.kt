package com.swiftmako.jormanager.model

import com.squareup.moshi.JsonClass
import java.math.BigDecimal

@JsonClass(generateAdapter = true)
data class GenesisShelley(
    val activeSlotsCoeff: BigDecimal,
    val networkId: String,
    val networkMagic: Long? = null,
    val slotLength: Long,
    val epochLength: Long,
    val slotsPerKESPeriod: Long,
    val systemStart: String,
    val maxKESEvolutions: Long,
)
