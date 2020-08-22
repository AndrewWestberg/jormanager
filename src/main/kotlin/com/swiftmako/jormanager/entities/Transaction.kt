package com.swiftmako.jormanager.entities

import com.swiftmako.jormanager.utils.toLocalTimeString
import org.joda.time.DateTime
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.Table


@Entity
@Table(name = "transactions")
data class Transaction(
        @Id
        @GeneratedValue(strategy = GenerationType.AUTO)
        val id: Long? = null,
        @Column(name = "at")
        val at: String = DateTime.now().toLocalTimeString(),
        @Column(name = "txid")
        val txid: String
)