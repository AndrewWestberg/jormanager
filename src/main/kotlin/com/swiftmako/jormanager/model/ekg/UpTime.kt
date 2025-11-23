package com.swiftmako.jormanager.model.ekg

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class UpTime(
    @param:Json(name = "ns")
    val ns: Ns = Ns()
)
