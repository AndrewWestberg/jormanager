package com.swiftmako.jormanager.entities

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table

@Entity
@Table(name = "files")
data class File(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "hibernate_generator")
    @SequenceGenerator(name = "hibernate_generator", sequenceName = "hibernate_sequence", allocationSize = 1)
    val id: Long? = null,
    @Column(name = "name")
    val name: String,
    @Column(name = "content")
    val content: String
)