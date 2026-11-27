package com.swiftmako.jormanager.controllers.utils

import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import net.schmizz.sshj.common.Buffer

object SshFingerprints {
    private val sha256Pattern = Regex("^SHA256:[A-Za-z0-9+/]{43}$")

    fun sha256(publicKey: PublicKey): String {
        val wireEncoded = Buffer.PlainBuffer().putPublicKey(publicKey).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(wireEncoded)
        return "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(digest)}"
    }

    fun matches(
        expected: String,
        actual: String
    ): Boolean {
        val expectedBytes = expected.trim().toByteArray(Charsets.UTF_8)
        val actualBytes = actual.trim().toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(expectedBytes, actualBytes)
    }

    fun isValidSha256Fingerprint(value: String): Boolean {
        val trimmed = value.trim()
        if (!sha256Pattern.matches(trimmed)) {
            return false
        }
        return runCatching {
            val rawBase64 = trimmed.removePrefix("SHA256:") + "="
            Base64.getDecoder().decode(rawBase64).size == 32
        }.getOrDefault(false)
    }
}
