package com.swiftmako.jormanager.model.ekg2

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class OperationalCertificateExpiryKESPeriod(
    @param:Json(name = "int")
    val int: IntX = IntX()
)
