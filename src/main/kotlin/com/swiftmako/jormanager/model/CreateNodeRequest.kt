package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigInteger

data class CreateNodeRequest(
    @param:JsonProperty("requestId") val requestId: String,
    @param:JsonProperty("resumeNodeId") val resumeNodeId: Long? = null,
    @param:JsonProperty("keyId") val keyId: Long? = null,
    @param:JsonProperty("spendingPassword") val spendingPassword: String,
    @param:JsonProperty("color") val color: String,
    @param:JsonProperty("host") val hostId: Long,
    @param:JsonProperty("parentId") val parentId: Long?,
    @param:JsonProperty("name") val name: String,
    @param:JsonProperty("isDefault") val isDefault: Boolean,
    @param:JsonProperty("type") val type: String,
    @param:JsonProperty("processorThreads") val processorThreads: Int,
    @param:JsonProperty("listen") val listen: String,
    @param:JsonProperty("port") val port: Int,
    @param:JsonProperty("prometheusListen") val prometheusListen: String = "127.0.0.1",
    @param:JsonProperty("enableTracingListener") val enableTracingListener: Boolean = true,
    @param:JsonProperty("tracingListen") val tracingListen: String? = null,
    @param:JsonProperty("genesisByron") val genesisByronFileId: Long = 0,
    @param:JsonProperty("genesisShelley") val genesisShelleyFileId: Long = 0,
    @param:JsonProperty("genesisAlonzo") val genesisAlonzoFileId: Long = 0,
    @param:JsonProperty("genesisConway") val genesisConwayFileId: Long = 0,
    @param:JsonProperty("genesisDijkstra") val genesisDijkstraFileId: Long? = null,
    @param:JsonProperty("config") val configFileId: Long = 0,
    @param:JsonProperty("topology") val topologyFileId: Long = 0,
    @param:JsonProperty("peerSnapshot") val peerSnapshotFileId: Long? = null,
    @param:JsonProperty("generateColdKeys") val generateColdKeys: Boolean,
    @param:JsonProperty("coldSKey") val coldSKey: String?,
    @param:JsonProperty("coldVKey") val coldVKey: String?,
    @param:JsonProperty("coldCounter") val coldCounter: String?,
    @param:JsonProperty("generateVRFKeys") val generateVRFKeys: Boolean,
    @param:JsonProperty("vrfSKey") val vrfSKey: String?,
    @param:JsonProperty("vrfVKey") val vrfVKey: String?,
    @param:JsonProperty("generateKESKeys") val generateKESKeys: Boolean,
    @param:JsonProperty("kesSKey") val kesSKey: String?,
    @param:JsonProperty("kesVKey") val kesVKey: String?,
    @param:JsonProperty("registrationFeesAccount") val registrationFeesAccount: Long?,
    @param:JsonProperty("ownerStakingAccount") val ownerStakingAccount: Long?,
    @param:JsonProperty("rewardsStakingAccount") val rewardsStakingAccount: Long?,
    @param:JsonProperty("poolPledge") val poolPledge: BigInteger?,
    @param:JsonProperty("poolCost") val poolCost: BigInteger?,
    @param:JsonProperty("poolMargin") val poolMargin: String,
    @param:JsonProperty("relays") val relays: List<Relay>?,
    @param:JsonProperty("metadata") val metadata: Metadata?,
    @param:JsonProperty("sudoPassword") val sudoPassword: String,
) {
    override fun toString(): String = "CreateNodeRequest(hostId=$hostId, parentId=$parentId, name=$name, type=$type, credentials=[REDACTED], metadata=[REDACTED])"
}

data class CreateNodeResult(val requestId: String, val nodeId: Long, val message: String)
