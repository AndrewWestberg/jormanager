package com.swiftmako.jormanager.model.metadata.pool


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Info(
    @Json(name = "url_png_icon_64x64")
    val urlPngIcon64x64: String? = null,
    @Json(name = "url_png_logo")
    val urlPngLogo: String? = null,
    @Json(name = "location")
    val location: String? = null,
    @Json(name = "social")
    val social: Social? = null,
    @Json(name = "company")
    val company: Company? = null,
    @Json(name = "about")
    val about: About? = null,
    @Json(name = "rss")
    val rss: String? = null
)