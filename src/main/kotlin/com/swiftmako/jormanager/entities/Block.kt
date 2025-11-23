package com.swiftmako.jormanager.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table

@Entity
@Table(name = "blocks")
data class Block(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "hibernate_generator")
    @SequenceGenerator(name = "hibernate_generator", sequenceName = "hibernate_sequence", allocationSize = 1)
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
    @Column(name = "status")
    val status: String, // (pending(default), missed, completed, forged, orphaned)
)
