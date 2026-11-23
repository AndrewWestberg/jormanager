package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.swiftmako.jormanager.controllers.LeiosController

class LeiosKeyRequest(
    val requestId: String,
    val target: LeiosController.Target,
    val intent: String,
    @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY) val spendingPassword: String,
    @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY) val signingKey: String? = null,
    val publicKeyHex: String? = null,
    val proofOfPossessionHex: String? = null,
    val observerNodeId: Long? = null,
) {
    override fun toString(): String = "LeiosKeyRequest(requestId=$requestId, target=$target, intent=$intent, credentials=[REDACTED])"
}
