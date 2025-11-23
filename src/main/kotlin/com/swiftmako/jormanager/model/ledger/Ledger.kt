package com.swiftmako.jormanager.model.ledger

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Ledger(
    @param:Json(name = "esSnapshots")
    val esSnapshots: EsSnapshots
)
