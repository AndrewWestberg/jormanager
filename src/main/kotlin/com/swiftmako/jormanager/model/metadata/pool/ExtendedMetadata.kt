package com.swiftmako.jormanager.model.metadata.pool


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ExtendedMetadata(
    @Json(name = "itn")
    val itn: Itn? = null,
    @Json(name = "info")
    val info: Info? = null,
    @Json(name = "telegram-admin-handle")
    val telegramAdminHandle: List<String>? = null,
    @Json(name = "my-pool-ids")
    val myPoolIds: List<String>? = null,
    @Json(name = "when-satured-then-recommend")
    val whenSaturedThenRecommend: List<String>? = null
)