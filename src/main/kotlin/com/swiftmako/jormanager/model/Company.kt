package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty

data class Company(
    @param:JsonProperty("name") val name: String?,
    @param:JsonProperty("addr") val addr: String?,
    @param:JsonProperty("city") val city: String?,
    @param:JsonProperty("country") val country: String?,
    @param:JsonProperty("company_id") val companyId: String?,
    @param:JsonProperty("vat_id") val vatId: String?
)
