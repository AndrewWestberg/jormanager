package com.swiftmako.jormanager.model

data class HostKeyMismatchAlert(
    val hostId: Long?,
    val hostname: String,
    val sshPort: Int,
    val expectedFingerprint: String,
    val receivedFingerprint: String,
    val message: String
)
