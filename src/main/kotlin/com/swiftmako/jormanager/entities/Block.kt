package com.swiftmako.jormanager.entities

import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.Table

@Entity
@Table(name = "blocks")
data class Block(
        @Id
        @GeneratedValue(strategy = GenerationType.SEQUENCE)
        val id: Long? = null,
        @Column(name = "at")
        val at: String,
        @Column(name = "pool")
        val pool: String,
        @Column(name = "host")
        val host: String,
        @Column(name = "slot")
        val slot: Long,
        @Column(name = "epoch")
        val epoch: Long,
        @Column(name = "slotInEpoch")
        val slotInEpoch: Long,
        @Column(name = "hash")
        val hash: String,
        @Column(name="status")
        val status:String, // (pending(default), missed, completed, forged, orphaned)
)