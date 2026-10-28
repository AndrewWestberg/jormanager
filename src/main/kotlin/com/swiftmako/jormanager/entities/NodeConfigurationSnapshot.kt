package com.swiftmako.jormanager.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "node_configuration_snapshots")
data class NodeConfigurationSnapshot(
    @Id val id: String,
    @Column(name = "node_id", nullable = false) val nodeId: Long,
    @Column(name = "bundle_id") val bundleId: String?,
    @Column(nullable = false) val role: String,
    @Column(name = "assets_json", nullable = false) val assetsJson: String,
    @Column(name = "effective_config_digest") val effectiveConfigDigest: String?,
    @Column(name = "effective_topology_digest") val effectiveTopologyDigest: String?,
    @Column(name = "approved_overrides", nullable = false) val approvedOverrides: String,
    @Column(name = "public_fingerprints", nullable = false) val publicFingerprints: String,
    @Column(name = "installation_observation") val installationObservation: String?,
)
