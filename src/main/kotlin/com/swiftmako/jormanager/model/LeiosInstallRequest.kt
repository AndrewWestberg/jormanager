package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.swiftmako.jormanager.controllers.LeiosController

class LeiosInstallRequest(
    val requestId: String,
    val target: LeiosController.Target,
    val keyId: Long,
    @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY) val spendingPassword: String,
    @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY) val sudoPassword: String? = null,
) {
    override fun toString(): String = "LeiosInstallRequest(requestId=$requestId, target=$target, keyId=$keyId, credentials=[REDACTED])"
}
