package com.swiftmako.jormanager.model.ekg2


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Mem(
    @Json(name = "resident")
    val resident: Resident = Resident()
)