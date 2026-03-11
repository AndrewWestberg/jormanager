package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty

data class RetirePoolRequest(
    @param:JsonProperty("id") val id: Long,
    @param:JsonProperty("sudoPassword") val sudoPassword: String,
    @param:JsonProperty("spendingPassword") val spendingPassword: String,
    @param:JsonProperty("retireFeesAccount") val retireFeesAccount: Long,
    @param:JsonProperty("retireEpoch") val retireEpoch: Long,
) {
    override fun toString(): String = "RetirePoolRequest(id=$id, spendingPassword='********', retireFeesAccount=$retireFeesAccount, retireEpoch=$retireEpoch)"
}
