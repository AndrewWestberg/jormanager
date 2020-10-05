package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty

data class LeaderLogsRequest(
        @JsonProperty("spendingPassword") val spendingPassword: String,
        @JsonProperty("epochNonce") val epochNonce: String,
)