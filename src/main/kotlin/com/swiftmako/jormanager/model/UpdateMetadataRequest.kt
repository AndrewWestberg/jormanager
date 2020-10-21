package com.swiftmako.jormanager.model


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class UpdateMetadataRequest(
    @Json(name = "id")
    val id: Int,
    @Json(name = "spendingPassword")
    val spendingPassword: String,
    @Json(name = "registrationFeesAccount")
    val registrationFeesAccount: Long,
    @Json(name = "ticker")
    val ticker: String,
    @Json(name = "name")
    val name: String,
    @Json(name = "description")
    val description: String,
    @Json(name = "homepage")
    val homepage: String,
    @Json(name = "extended")
    val extended: Extended
)