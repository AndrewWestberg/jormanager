package com.swiftmako.jormanager.model.ekg

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class MetricsY(
    @param:Json(name = "remainingKESPeriods")
    val remainingKESPeriods: RemainingKESPeriods = RemainingKESPeriods(),
)
