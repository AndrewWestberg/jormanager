package com.swiftmako.jormanager.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "network_configuration_bundles")
data class NetworkConfigurationBundle(
    @Id @Column(name = "bundle_id") val bundleId: String,
    @Column(name = "reset_id", nullable = false) val resetId: String,
    @Column(name = "catalog_prefix", nullable = false) val catalogPrefix: String,
    @Column(name = "network_magic", nullable = false) val networkMagic: Long,
    @Column(name = "system_start", nullable = false) val systemStart: String,
    @Column(nullable = false) val manifest: String,
    @Column(name = "assets_json", nullable = false) val assetsJson: String,
    @Column(name = "validation_state", nullable = false) val validationState: String,
    @Column(name = "compatible_tool_evidence") val compatibleToolEvidence: String? = null,
)
