package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty

data class LeaderLogsRequest(
    @param:JsonProperty("spendingPassword") val spendingPassword: String,
    @param:JsonProperty("requestType") val requestType: String, // "currentEpoch", "futureEpoch"
)
