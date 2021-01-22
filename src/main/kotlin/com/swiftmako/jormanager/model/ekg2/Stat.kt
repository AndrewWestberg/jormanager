package com.swiftmako.jormanager.model.ekg2


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Stat(
    @Json(name = "cputicks")
    val cputicks: Cputicks = Cputicks(),
    @Json(name = "threads")
    val threads: Threads = Threads()
)