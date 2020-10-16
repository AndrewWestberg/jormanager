package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty

data class LeaderLogsRequest(
        @JsonProperty("spendingPassword") val spendingPassword: String,
        @JsonProperty("requestType") val requestType: String, // "currentEpoch", "futureEpoch"
)