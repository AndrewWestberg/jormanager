package com.swiftmako.jormanager.entities

import javax.persistence.*

@Entity
@Table(name = "files")
data class File(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    val id: Long? = null,
    @Column(name = "name")
    val name: String,
    @Column(name = "content")
    @Lob
    val content: String
)