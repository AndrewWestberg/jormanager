package com.swiftmako.jormanager.entities

import javax.persistence.*

@Entity
@Table(name = "files")
data class File(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    val id: Long? = null,
    @Column(name = "name")
    val name: String,
    @Column(name = "content")
    val content: String
)