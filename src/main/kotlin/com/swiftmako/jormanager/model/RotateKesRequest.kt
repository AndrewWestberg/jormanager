package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty

data class RotateKesRequest(
        @JsonProperty("id") val id: Long,
        @JsonProperty("spendingPassword") val spendingPassword: String,
)