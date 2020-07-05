package com.swiftmako.jormanager.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Genesis(
        val networkMagic: Int? = null
)