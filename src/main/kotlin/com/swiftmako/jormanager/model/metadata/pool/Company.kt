package com.swiftmako.jormanager.model.metadata.pool


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Company(
    @Json(name = "name")
    val name: String? = null,
    @Json(name = "addr")
    val addr: String? = null,
    @Json(name = "city")
    val city: String? = null,
    @Json(name = "country")
    val country: String? = null,
    @Json(name = "company_id")
    val companyId: String? = null,
    @Json(name = "vat_id")
    val vatId: String? = null
)