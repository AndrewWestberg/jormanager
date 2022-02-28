package com.swiftmako.jormanager.entities

import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.Table

@Entity
@Table(name = "chain")
data class ChainBlock(
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE)
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