package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigInteger

data class CreateNodeRequest(
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
    @param:JsonProperty("ekgPort") val ekgPort: Int,
    @param:JsonProperty("promPort") val promPort: Int,
    @param:JsonProperty("genesisByron") val genesisByronFileId: Long,
    @param:JsonProperty("genesisShelley") val genesisShelleyFileId: Long,
    @param:JsonProperty("genesisAlonzo") val genesisAlonzoFileId: Long,
    @param:JsonProperty("genesisConway") val genesisConwayFileId: Long,
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
    @param:JsonProperty("sudoPassword") val sudoPassword: String
) {
    override fun toString(): String = "CreateNodeRequest(spendingPassword='************', color='$color', hostId=$hostId, parentId=$parentId, name='$name', isDefault=$isDefault, type='$type', processorThreads=$processorThreads, listen='$listen', port=$port, ekgPort=$ekgPort, promPort=$promPort, genesisByronFileId=$genesisByronFileId, genesisShelleyFileId=$genesisShelleyFileId, generateColdKeys=$generateColdKeys, coldSKey=***, coldVKey=***, coldCounter=***, generateVRFKeys=$generateVRFKeys, vrfSKey=***, vrfVKey=***, generateKESKeys=$generateKESKeys, kesSKey=***, kesVKey=***, registrationFeesAccount=$registrationFeesAccount, ownerStakingAccount=$ownerStakingAccount, rewardsStakingAccount=$rewardsStakingAccount, poolPledge=$poolPledge, poolCost=$poolCost, poolMargin='$poolMargin', relays=$relays, metadata=$metadata, sudoPassword='************')"
}
