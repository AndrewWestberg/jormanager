package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty

data class EditRelaysRequest(
    @JsonProperty("spendingPassword") val spendingPassword: String,
    @JsonProperty("id") val nodeId: Long,
    @JsonProperty("registrationFeesAccount") val registrationFeesAccount: Long,
    @JsonProperty("relays") val relays: List<Relay>,
) {
    override fun toString(): String {
        return "EditRelaysRequest(spendingPassword='********', nodeId=$nodeId, registrationFeesAccount=$registrationFeesAccount, relays=$relays)"
    }
}