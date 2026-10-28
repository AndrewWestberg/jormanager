package com.swiftmako.jormanager.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigInteger

@Entity
@Table(name = "authorized_operations")
data class AuthorizedOperation(
    @Id val id: String,
    @Column(name = "preview_id", nullable = false, unique = true) val previewId: String,
    @Column(name = "network_key", nullable = false) val networkKey: String,
    @Column(name = "node_id", nullable = false) val nodeId: Long,
    @Column(name = "host_id", nullable = false) val hostId: Long,
    @Column(nullable = false) val operation: String,
    @Column(nullable = false) val era: String,
    @Column(name = "era_generation", nullable = false) val eraGeneration: Long,
    @Column(name = "capability_fingerprint", nullable = false) val capabilityFingerprint: String,
    @Column(nullable = false) val state: String,
    @Column(name = "request_fingerprint", nullable = false) val requestFingerprint: String,
    @Column(name = "request_payload", nullable = false) val requestPayload: String,
    @Column(name = "parameter_fingerprint", nullable = false) val parameterFingerprint: String,
    @Column(name = "state_fingerprint", nullable = false) val stateFingerprint: String,
    @Column(name = "tx_id", nullable = false) val txId: String,
    @Column(name = "unsigned_body", nullable = false) val unsignedBody: String,
    @Column(name = "signed_batch") val signedBatch: String? = null,
    @Column(nullable = false) val fee: BigInteger,
    @Column(nullable = false) val deposit: BigInteger,
    @Column(nullable = false) val refund: BigInteger,
    @Column(name = "valid_from") val validFrom: Long? = null,
    @Column(name = "valid_until") val validUntil: Long? = null,
    @Column(name = "confirmation_hash") val confirmationHash: String? = null,
    @Column(name = "confirmation_slot") val confirmationSlot: Long? = null,
    @Column(name = "confirmation_height") val confirmationHeight: Long? = null,
)
