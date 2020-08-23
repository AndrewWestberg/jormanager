package com.swiftmako.jormanager.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GenesisByron(
        val startTime: Long,
)