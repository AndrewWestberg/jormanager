package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class UpdateMetadataRequest(
    @param:Json(name = "id")
    val id: Long,
    @param:Json(name = "spendingPassword")
    val spendingPassword: String,
    @param:Json(name = "registrationFeesAccount")
    val registrationFeesAccount: Long,
    @param:Json(name = "custom")
    val custom: Boolean,
    @param:Json(name = "metadataUrl")
    val metadataUrl: String = "",
    @param:Json(name = "extendedMetadataUrl")
    val extendedMetadataUrl: String = "",
    @param:Json(name = "ticker")
    val ticker: String = "",
    @param:Json(name = "name")
    val name: String = "",
    @param:Json(name = "description")
    val description: String = "",
    @param:Json(name = "homepage")
    val homepage: String = "",
    @param:Json(name = "extended")
    val extended: Extended?
)
