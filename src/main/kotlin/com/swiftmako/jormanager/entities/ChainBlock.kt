package com.swiftmako.jormanager.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table


@Entity
@Table(name = "chain")
data class ChainBlock(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "hibernate_generator")
    @SequenceGenerator(name = "hibernate_generator", sequenceName = "hibernate_sequence", allocationSize = 1)
    val id: Long? = null,
    @Column(name = "block_number")
    val blockNumber: Long,
    @Column(name = "slot_number")
    val slotNumber: Long,
    @Column(name = "hash")
    val hash: String,
    @Column(name = "prev_hash")
    val prevHash: String,
    @Column(name = "eta_v")
    val etaV: String,
    @Column(name = "pool_id")
    val poolId: String,
    @Column(name = "leader_vrf")
    val leaderVrf: String,
)