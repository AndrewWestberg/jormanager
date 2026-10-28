package com.swiftmako.jormanager.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "network_reconfiguration_reviews")
data class NetworkReconfigurationReview(
    @Id val id: String,
    @Column(name = "source_node_id", nullable = false) val sourceNodeId: Long,
    @Column(name = "source_host_id", nullable = false) val sourceHostId: Long,
    @Column(name = "target_host_id", nullable = false) val targetHostId: Long,
    @Column(name = "prior_bundle_id", nullable = false) val priorBundleId: String,
    @Column(name = "prior_reset_id", nullable = false) val priorResetId: String,
    @Column(name = "target_bundle_id", nullable = false) val targetBundleId: String,
    @Column(name = "target_reset_id", nullable = false) val targetResetId: String,
    @Column(name = "source_fingerprint", nullable = false) val sourceFingerprint: String,
    @Column(name = "target_host_fingerprint", nullable = false) val targetHostFingerprint: String,
    @Column(name = "request_fingerprint", nullable = false) val requestFingerprint: String,
    @Column(name = "reviewed_request", nullable = false) val reviewedRequest: String,
    @Column(nullable = false) val consequences: String,
    @Column(nullable = false) val state: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "authorized_at") val authorizedAt: Instant? = null,
    @Column(name = "consumed_at") val consumedAt: Instant? = null,
)
