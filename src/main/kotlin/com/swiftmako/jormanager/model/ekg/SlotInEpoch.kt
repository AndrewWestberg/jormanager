package com.swiftmako.jormanager.model.ekg


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SlotInEpoch(
    @Json(name = "int")
    val intX: IntX = IntX()
)