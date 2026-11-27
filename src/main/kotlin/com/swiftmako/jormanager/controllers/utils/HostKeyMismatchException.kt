package com.swiftmako.jormanager.controllers.utils

import net.schmizz.sshj.common.SSHRuntimeException

class HostKeyMismatchException(
    val hostId: Long?,
    val hostname: String,
    val sshPort: Int,
    val expectedFingerprint: String,
    val receivedFingerprint: String,
    cause: Throwable? = null
) : SSHRuntimeException(
        buildMessage(hostname, sshPort, expectedFingerprint, receivedFingerprint),
        cause
    ) {
    companion object {
        fun buildMessage(
            hostname: String,
            sshPort: Int,
            expectedFingerprint: String,
            receivedFingerprint: String
        ): String = "SSH host key mismatch for $hostname:$sshPort! Stored fingerprint: $expectedFingerprint, received fingerprint: $receivedFingerprint. Connection blocked."
    }
}
