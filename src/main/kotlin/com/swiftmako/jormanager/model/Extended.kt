package com.swiftmako.jormanager.model


import com.fasterxml.jackson.annotation.JsonProperty

data class Extended(
        @JsonProperty("itn") val itn: Itn?,
        @JsonProperty("info") val info: Info?,
        @JsonProperty("telegramAdminHandle") val telegramAdminHandle: String?
)