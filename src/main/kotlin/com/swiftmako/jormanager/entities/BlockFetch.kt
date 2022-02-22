package com.swiftmako.jormanager.entities

import javax.persistence.*

@Entity
@Table(name = "blockfetch")
data class BlockFetch(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    val id: Long? = null,
    @Column(name = "block_number")
    val blockNumber: Long,
    @Column(name = "slot_number")
    val slotNumber: Long,
    @Column(name = "hash")
    val hash: String,
    @Column(name = "prev_hash")
    val prev_hash: String,
)
