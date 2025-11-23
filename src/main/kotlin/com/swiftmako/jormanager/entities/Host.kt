package com.swiftmako.jormanager.entities

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import jakarta.persistence.Transient

@Entity
@Table(name = "hosts")
data class Host(
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "hibernate_generator")
    @SequenceGenerator(name = "hibernate_generator", sequenceName = "hibernate_sequence", allocationSize = 1)
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
    val nodeHomePath: String,
    @Column(name = "jcli_path")
    val jcliPath: String?
) {
    @get:JsonIgnore
    @get:Transient
    val isRemote: Boolean
        get() = this.type == "remote"
}
