package com.swiftmako.jormanager.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** Local material is optional: a historical public key need not have a signing file. */
@Entity
@Table(name = "leios_voting_keys")
class LeiosVotingKey(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "network_key", nullable = false) val networkKey: String,
    @Column(name = "pool_id", nullable = false) val poolId: String,
    @Column(name = "public_key", nullable = false) val publicKey: ByteArray,
    @Column(name = "proof", nullable = false) val proof: ByteArray,
    @Column(name = "signing_file_id") val signingFileId: Long? = null,
    @Column(name = "prepared_at", nullable = false) val preparedAt: Instant,
    @Column(name = "validation_provenance", nullable = false) val validationProvenance: String,
)
