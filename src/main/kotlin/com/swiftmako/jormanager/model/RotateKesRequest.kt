package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty

data class RotateKesRequest(
    @param:JsonProperty("id") val id: Long,
    @param:JsonProperty("spendingPassword") val spendingPassword: String,
) {
    override fun toString(): String = "RotateKesRequest(id=$id, spendingPassword='************')"
}
