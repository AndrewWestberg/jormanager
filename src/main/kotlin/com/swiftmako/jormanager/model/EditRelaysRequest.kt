package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty

data class EditRelaysRequest(
    @param:JsonProperty("spendingPassword") val spendingPassword: String,
    @param:JsonProperty("id") val nodeId: Long,
    @param:JsonProperty("registrationFeesAccount") val registrationFeesAccount: Long,
    @param:JsonProperty("relays") val relays: List<Relay>,
) {
    override fun toString(): String = "EditRelaysRequest(spendingPassword='********', nodeId=$nodeId, registrationFeesAccount=$registrationFeesAccount, relays=$relays)"
}
