package com.swiftmako.jormanager.entities

import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.Table

@Entity
@Table(name = "nodes")
data class Node(
        @Id
        @GeneratedValue(strategy = GenerationType.AUTO)
        val id: Long? = null,
        @Column(name = "host_id")
        val hostId: Long,
        @Column(name="type")
        val type: String, // relay, core
        @Column(name = "name")
        val name:String, // ticker
        @Column(name="listen")
        val listen:String, // 127.0.0.1, 0.0.0.0
        @Column(name="port")
        val port:Int,
        @Column(name="genesis_file_id")
        val genesisFileId:Long,
        @Column(name="config_file_id")
        val configFileId:Long,
        @Column(name="cold_node_skey_file_id")
        val coldNodeSKeyFileId:Long,
        @Column(name="cold_node_vkey_file_id")
        val coldNodeVKeyFileId:Long,
        @Column(name="hot_node_kes_skey_file_id")
        val hotNodeKesSKeyFileId:Long,
        @Column(name="hot_node_vrf_skey_file_id")
        val hotNodeVrfSKeyFileId:Long,
        @Column(name="opcert_file_id")
        val opcertFileId:Long,
        @Column(name="opcert_expiration")
        val opcertExpiration: Long,
        @Column(name="is_default")
        val isDefault:Boolean
)