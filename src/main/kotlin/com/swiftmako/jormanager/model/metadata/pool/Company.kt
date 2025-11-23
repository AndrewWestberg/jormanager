package com.swiftmako.jormanager.model.metadata.pool

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Company(
    @param:Json(name = "name")
    val name: String? = null,
    @param:Json(name = "addr")
    val addr: String? = null,
    @param:Json(name = "city")
    val city: String? = null,
    @param:Json(name = "country")
    val country: String? = null,
    @param:Json(name = "company_id")
    val companyId: String? = null,
    @param:Json(name = "vat_id")
    val vatId: String? = null
)
