package com.swiftmako.jormanager.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

/** A node/snapshot observation is not a chain projection or proof of producer readiness. */
@Entity
@Table(name = "pool_key_observations")
class PoolKeyObservation(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
    @Column(name = "network_key", nullable = false) val networkKey: String,
    @Column(name = "pool_id", nullable = false) val poolId: String,
    @Column(name = "observed_at", nullable = false) val observedAt: Instant,
    @Column(name = "chain_slot") val chainSlot: BigDecimal? = null,
    @Column(name = "chain_hash") val chainHash: ByteArray? = null,
    @Column(name = "current_key_id") val currentKeyId: Long? = null,
    @Column(name = "future_key_id") val futureKeyId: Long? = null,
    @Column(name = "current_public_key") val currentPublicKey: ByteArray? = null,
    @Column(name = "future_public_key") val futurePublicKey: ByteArray? = null,
    @Column(name = "registration_epoch") val registrationEpoch: BigDecimal? = null,
    @Column(name = "effective_epoch", precision = 20, scale = 0) val effectiveEpoch: BigDecimal? = null,
    @Column(name = "mark_epoch") val markEpoch: BigDecimal? = null,
    @Column(name = "set_epoch") val setEpoch: BigDecimal? = null,
    @Column(name = "eligible") val eligible: Boolean? = null,
    @Column(name = "selected") val selected: Boolean? = null,
    @Column(name = "expired") val expired: Boolean? = null,
    @Column(name = "scheduled_retirement") val scheduledRetirement: BigDecimal? = null,
)
