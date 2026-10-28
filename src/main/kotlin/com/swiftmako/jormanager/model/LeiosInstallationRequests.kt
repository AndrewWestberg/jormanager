package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.swiftmako.jormanager.controllers.LeiosController

class LeiosInstallPreviewRequest(val requestId: String, val target: LeiosController.Target, val keyId: Long)

class LeiosInstallRequest(
    val requestId: String,
    val target: LeiosController.Target,
    val keyId: Long,
    val previewId: String,
    val approved: Boolean,
    @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY) val spendingPassword: String,
    @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY) val sudoPassword: String? = null,
) {
    override fun toString(): String = "LeiosInstallRequest(requestId=$requestId, target=$target, keyId=$keyId, previewId=$previewId, approved=$approved, credentials=[REDACTED])"
}
