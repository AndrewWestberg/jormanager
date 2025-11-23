package com.swiftmako.jormanager.model.ekg

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class EkgMetrics(
    @param:Json(name = "cardano")
    val cardano: Cardano = Cardano()
)
