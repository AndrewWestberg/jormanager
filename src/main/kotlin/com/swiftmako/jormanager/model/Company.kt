package com.swiftmako.jormanager.model


import com.fasterxml.jackson.annotation.JsonProperty

data class Company(
        @JsonProperty("name") val name: String?,
        @JsonProperty("addr") val addr: String?,
        @JsonProperty("city") val city: String?,
        @JsonProperty("country") val country: String?,
        @JsonProperty("company_id") val companyId: String?,
        @JsonProperty("vat_id") val vatId: String?
)