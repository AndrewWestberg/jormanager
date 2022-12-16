package com.swiftmako.jormanager.entities

import com.swiftmako.jormanager.utils.toLocalTimeString
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import org.joda.time.DateTime


@Entity
@Table(name = "transactions")
data class Transaction(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "hibernate_generator")
    @SequenceGenerator(name = "hibernate_generator", sequenceName = "hibernate_sequence", allocationSize = 1)
    val id: Long? = null,
    @Column(name = "at")
    val at: String = DateTime.now().toLocalTimeString(),
    @Column(name = "txid")
    val txid: String
)