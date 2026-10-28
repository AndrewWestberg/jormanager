package com.swiftmako.jormanager.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** Host observations are independent of chain adoption and are never rolled back with it. */
@Entity
@Table(name = "key_installations")
class KeyInstallation(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
    @Column(name = "key_id", nullable = false) val keyId: Long,
    @Column(name = "node_id", nullable = false) val nodeId: Long,
    @Column(name = "host_id", nullable = false) val hostId: Long,
    @Column(name = "configuration_fingerprint", nullable = false) val configurationFingerprint: String,
    @Column(name = "installed_fingerprint") val installedFingerprint: String? = null,
    @Column(name = "loaded_fingerprint") val loadedFingerprint: String? = null,
    @Column(name = "permission_result") val permissionResult: String? = null,
    @Column(name = "state", nullable = false) val state: String,
    @Column(name = "observed_at", nullable = false) val observedAt: Instant,
    @Column(name = "sanitized_error") val sanitizedError: String? = null,
)
