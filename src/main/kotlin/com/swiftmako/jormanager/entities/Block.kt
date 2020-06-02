package com.swiftmako.jormanager.entities

import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id

@Entity(name = "blocks")
data class Block(
        @Id
        @GeneratedValue(strategy = GenerationType.AUTO)
        val id: Long? = null,
        val at: String,
        val pool: String,
        val host: String,
        val slot: Long,
        val hash: String
)