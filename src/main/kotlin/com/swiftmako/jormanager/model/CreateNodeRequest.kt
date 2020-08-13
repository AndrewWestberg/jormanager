package com.swiftmako.jormanager.model


import com.fasterxml.jackson.annotation.JsonProperty
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CreateNodeRequest(
        @JsonProperty("color") @Json(name = "color") val color: String,
        @JsonProperty("host") @Json(name = "host") val hostId: Long,
        @JsonProperty("name") @Json(name = "name") val name: String,
        @JsonProperty("isDefault") @Json(name = "isDefault") val isDefault: Boolean,
        @JsonProperty("type") @Json(name = "type") val type: String,
        @JsonProperty("processorThreads") @Json(name = "processorThreads") val processorThreads: Int,
        @JsonProperty("listen") @Json(name = "listen") val listen: String,
        @JsonProperty("port") @Json(name = "port") val port: Int,
        @JsonProperty("genesisByron") @Json(name = "genesisByron") val genesisByronFileId: Long,
        @JsonProperty("genesisShelley") @Json(name = "genesisShelley") val genesisShelleyFileId: Long,
        @JsonProperty("generateColdKeys") @Json(name = "generateColdKeys") val generateColdKeys: Boolean,
        @JsonProperty("coldSKey") @Json(name = "coldSKey") val coldSKey: String?,
        @JsonProperty("coldVKey") @Json(name = "coldVKey") val coldVKey: String?,
        @JsonProperty("generateVRFKeys") @Json(name = "generateVRFKeys") val generateVRFKeys: Boolean,
        @JsonProperty("vrfSKey") @Json(name = "vrfSKey") val vrfSKey: String?,
        @JsonProperty("vrfVKey") @Json(name = "vrfVKey") val vrfVKey: String?,
        @JsonProperty("generateKESKeys") @Json(name = "generateKESKeys") val generateKESKeys: Boolean,
        @JsonProperty("kesSKey") @Json(name = "kesSKey") val kesSKey: String?,
        @JsonProperty("kesVKey") @Json(name = "kesVKey") val kesVKey: String?,
        @JsonProperty("registrationFeesAccount") @Json(name = "registrationFeesAccount") val registrationFeesAccount: Long?,
        @JsonProperty("ownerStakingAccount") @Json(name = "ownerStakingAccount") val ownerStakingAccount: Long?,
        @JsonProperty("rewardsStakingAccount") @Json(name = "rewardsStakingAccount") val rewardsStakingAccount: Long?,
        @JsonProperty("poolPledge") @Json(name = "poolPledge") val poolPledge: Long?,
        @JsonProperty("poolCost") @Json(name = "poolCost") val poolCost: Long?,
        @JsonProperty("poolMargin") @Json(name = "poolMargin") val poolMargin: String,
        @JsonProperty("relays") @Json(name = "relays") val relays: List<Relay>?,
        @JsonProperty("metadata") @Json(name = "metadata") val metadata: Metadata?,
        @JsonProperty("sudoPassword") @Json(name = "sudoPassword") val sudoPassword: String
)