package com.swiftmako.jormanager.model.ekg2

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class EkgMetrics2(
    @param:Json(name = "cardano")
    val cardano: Cardano = Cardano()
)
