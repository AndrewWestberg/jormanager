package com.swiftmako.jormanager.model.ekg2

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Stat(
    @param:Json(name = "cputicks")
    val cputicks: Cputicks = Cputicks(),
    @param:Json(name = "threads")
    val threads: Threads = Threads()
)
