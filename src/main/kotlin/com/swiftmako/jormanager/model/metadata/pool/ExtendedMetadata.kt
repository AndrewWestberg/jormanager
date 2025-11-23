package com.swiftmako.jormanager.model.metadata.pool

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ExtendedMetadata(
    @param:Json(name = "itn")
    val itn: Itn? = null,
    @param:Json(name = "info")
    val info: Info? = null,
    @param:Json(name = "telegram-admin-handle")
    val telegramAdminHandle: List<String>? = null,
    @param:Json(name = "my-pool-ids")
    val myPoolIds: List<String>? = null,
    @param:Json(name = "when-satured-then-recommend")
    val whenSaturedThenRecommend: List<String>? = null,
    @param:Json(name = "adapools-verify")
    val adapoolsVerify: String? = null
)
