package com.swiftmako.jormanager.entities

import java.math.BigDecimal
import java.math.BigInteger
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
        @Column(name = "parent_id")
        val parentId: Long? = null,
        @Column(name = "color")
        val color: String, // the color of the node for charts/graphs
        @Column(name = "type")
        val type: String, // relay, core
        @Column(name = "processor_threads")
        val processorThreads: Int,
        @Column(name = "name")
        val name: String, // ticker
        @Column(name = "listen")
        val listen: String, // 127.0.0.1, 0.0.0.0
        @Column(name = "port")
        val port: Int,
        @Column(name = "ekg_port")
        val ekgPort: Int,
        @Column(name = "prom_port")
        val promPort: Int,
        @Column(name = "genesis_byron_file_id")
        val genesisByronFileId: Long,
        @Column(name = "genesis_shelley_file_id")
        val genesisShelleyFileId: Long,
        @Column(name = "genesis_alonzo_file_id")
        val genesisAlonzoFileId: Long,
        @Column(name = "config_file_id")
        val configFileId: Long,
        @Column(name = "pool_id")
        val poolId: String? = null,
        @Column(name = "pool_pledge")
        val poolPledge: BigInteger? = null,
        @Column(name = "pool_cost")
        val poolCost: BigInteger? = null,
        @Column(name = "pool_margin")
        val poolMargin: String? = null,
        @Column(name = "is_default")
        val isDefault: Boolean,
        @Column(name = "owner_staking_account_id")
        val ownerStakingAccountId: Long = -1,
        @Column(name = "rewards_staking_account_id")
        val rewardsStakingAccountId: Long = -1,
        @Column(name = "core_skey_id")
        val coreSKeyId: Long = -1,
        @Column(name = "core_vkey_id")
        val coreVKeyId: Long = -1,
        @Column(name = "core_counter_id")
        val coreCounterId: Long = -1,
        @Column(name = "vrf_skey_id")
        val vrfSKeyId: Long = -1,
        @Column(name = "vrf_vkey_id")
        val vrfVKeyId: Long = -1,
        @Column(name = "kes_skey_id")
        val kesSKeyId: Long = -1,
        @Column(name = "kes_vkey_id")
        val kesVKeyId: Long = -1,
        @Column(name = "kes_expire_time_sec")
        val kesExpireTimeSec: Long = -1,
        @Column(name = "kes_expire_date")
        val kesExpireDate: String = "---",
        @Column(name = "opcert_id")
        val opcertId: Long = -1,
        @Column(name = "itn_private_key_id")
        val itnPrivateKeyId: Long = -1,
        @Column(name = "itn_public_key_id")
        val itnPublicKeyId: Long = -1,
        @Column(name = "metadata_url")
        val metadataUrl: String? = null,
        @Column(name = "extended_metadata_url")
        val extendedMetadataUrl: String? = null,
        @Column(name = "deleted")
        val isDeleted: Boolean = false,
)