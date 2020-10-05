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
        @GeneratedValue(strategy = GenerationType.AUTO)
        val id: Long? = null,
        @Column(name = "block_number")
        val blockNumber: Long = 0L,
        @Column(name = "slot_number")
        val slotNumber: Long = 0L,
        @Column(name = "hash")
        val hash: String? = null,
        @Column(name = "prev_hash")
        val prevHash: String = "",
        @Column(name = "node_vkey")
        val nodeVkey: String = "",
        @Column(name = "node_vrf_vkey")
        val nodeVrfVkey: String = "",
        @Column(name = "eta_vrf_first")
        val etaVrfFirstPart: String = "",
        @Column(name = "eta_vrf_second")
        val etaVrfSecondPart: String = "",
        @Column(name = "leader_vrf_first")
        val leaderVrfFirstPart: String = "",
        @Column(name = "leader_vrf_second")
        val leaderVrfSecondPart: String = "",
        @Column(name = "block_size")
        val blockSize: Long = 0L,
        @Column(name = "block_body_hash")
        val blockBodyHash: String = "",
        @Column(name = "opcert")
        val poolOpcert: String = "",
        @Column(name = "unknown1")
        val unknown1: Long = 0L,
        @Column(name = "kes_period")
        val kesPeriod: Long = 0L,
        @Column(name = "unknown2")
        val unknown2: String = "",
        @Column(name = "protocol_major_version")
        val protocolMajorVersion: Long = 0L,
        @Column(name = "protocol_minor_version")
        val protocolMinorVersion: Long = 0L
)