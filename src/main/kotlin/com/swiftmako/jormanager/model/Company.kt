package com.swiftmako.jormanager.model


import com.fasterxml.jackson.annotation.JsonProperty
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Company(
        @JsonProperty("name") @Json(name = "name") val name: String?,
        @JsonProperty("addr") @Json(name = "addr") val addr: String?,
        @JsonProperty("city") @Json(name = "city") val city: String?,
        @JsonProperty("country") @Json(name = "country") val country: String?,
        @JsonProperty("company_id") @Json(name = "company_id") val companyId: String?,
        @JsonProperty("vat_id") @Json(name = "vat_id") val vatId: String?
)