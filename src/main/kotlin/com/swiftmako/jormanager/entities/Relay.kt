package com.swiftmako.jormanager.entities

import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.Table

@Entity
@Table(name = "relays")
data class Relay(
        @Id
        @GeneratedValue(strategy = GenerationType.AUTO)
        val id: Long? = null,
        @Column(name = "node_id")
        val nodeId: Long,
        @Column(name = "addr")
        val addr: String,
        @Column(name = "port")
        val port: Int,
)