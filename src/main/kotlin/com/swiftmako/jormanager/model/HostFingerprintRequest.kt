package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty

class HostFingerprintRequest(
    val requestId: String,
    val hostId: Long,
    val action: String,
    val fingerprint: String? = null,
    @get:JsonProperty(access = JsonProperty.Access.WRITE_ONLY) val spendingPassword: String
) {
    override fun toString(): String = "HostFingerprintRequest(requestId=$requestId, hostId=$hostId, action=$action, fingerprint=$fingerprint, credentials=[REDACTED])"
}

data class HostFingerprintResult(
    val requestId: String,
    val hostId: Long,
    val action: String,
    val sshFingerprint: String?,
    val message: String
)
