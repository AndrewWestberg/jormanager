package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty

data class Extended(
    @param:JsonProperty("itn") val itn: Itn?,
    @param:JsonProperty("info") val info: Info?,
    @param:JsonProperty("telegramAdminHandle") val telegramAdminHandle: String?,
    @param:JsonProperty("adapoolsVerify") val adapoolsVerify: String?,
)
