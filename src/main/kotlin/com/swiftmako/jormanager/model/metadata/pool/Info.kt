package com.swiftmako.jormanager.model.metadata.pool

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Info(
    @param:Json(name = "url_png_icon_64x64")
    val urlPngIcon64x64: String? = null,
    @param:Json(name = "url_png_logo")
    val urlPngLogo: String? = null,
    @param:Json(name = "location")
    val location: String? = null,
    @param:Json(name = "social")
    val social: Social? = null,
    @param:Json(name = "company")
    val company: Company? = null,
    @param:Json(name = "about")
    val about: About? = null,
    @param:Json(name = "rss")
    val rss: String? = null
)
