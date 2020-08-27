package com.swiftmako.jormanager.model


import com.fasterxml.jackson.annotation.JsonProperty

data class CreateNodeRequest(
        @JsonProperty("spendingPassword") val spendingPassword: String,
        @JsonProperty("color") val color: String,
        @JsonProperty("host") val hostId: Long,
        @JsonProperty("name") val name: String,
        @JsonProperty("isDefault") val isDefault: Boolean,
        @JsonProperty("type") val type: String,
        @JsonProperty("processorThreads") val processorThreads: Int,
        @JsonProperty("listen") val listen: String,
        @JsonProperty("port") val port: Int,
        @JsonProperty("genesisByron") val genesisByronFileId: Long,
        @JsonProperty("genesisShelley") val genesisShelleyFileId: Long,
        @JsonProperty("generateColdKeys") val generateColdKeys: Boolean,
        @JsonProperty("coldSKey") val coldSKey: String?,
        @JsonProperty("coldVKey") val coldVKey: String?,
        @JsonProperty("coldCounter") val coldCounter: String?,
        @JsonProperty("generateVRFKeys") val generateVRFKeys: Boolean,
        @JsonProperty("vrfSKey") val vrfSKey: String?,
        @JsonProperty("vrfVKey") val vrfVKey: String?,
        @JsonProperty("generateKESKeys") val generateKESKeys: Boolean,
        @JsonProperty("kesSKey") val kesSKey: String?,
        @JsonProperty("kesVKey") val kesVKey: String?,
        @JsonProperty("registrationFeesAccount") val registrationFeesAccount: Long?,
        @JsonProperty("ownerStakingAccount") val ownerStakingAccount: Long?,
        @JsonProperty("rewardsStakingAccount") val rewardsStakingAccount: Long?,
        @JsonProperty("poolPledge") val poolPledge: Long?,
        @JsonProperty("poolCost") val poolCost: Long?,
        @JsonProperty("poolMargin") val poolMargin: String,
        @JsonProperty("relays") val relays: List<Relay>?,
        @JsonProperty("metadata") val metadata: Metadata?,
        @JsonProperty("sudoPassword") val sudoPassword: String
)