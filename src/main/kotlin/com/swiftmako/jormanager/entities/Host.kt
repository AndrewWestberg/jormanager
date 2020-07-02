package com.swiftmako.jormanager.entities

import com.fasterxml.jackson.annotation.JsonIgnore
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.GeneratedValue
import javax.persistence.GenerationType
import javax.persistence.Id
import javax.persistence.Table
import javax.persistence.Transient

@Entity
@Table(name = "hosts")
data class Host(
        @Id
        @GeneratedValue(strategy = GenerationType.AUTO)
        val id: Long? = null,
        @Column(name = "type")
        val type: String, // local, remote
        @Column(name = "cardano_cli_path")
        val cardanoCliPath: String,
        @Column(name = "cardano_node_path")
        val cardanoNodePath: String,
        @Column(name = "hostname")
        val hostname: String,
        @Column(name = "ssh_user")
        val sshUser: String,
        @Column(name = "ssh_port")
        val sshPort: Int = 22,
        @Column(name = "ssh_pem_path")
        val sshPemPath: String = "",
        @Column(name = "node_home_path")
        val nodeHomePath: String
) {
    @get:JsonIgnore
    @get:Transient
    val isRemote: Boolean
        get() = this.type == "remote"
}