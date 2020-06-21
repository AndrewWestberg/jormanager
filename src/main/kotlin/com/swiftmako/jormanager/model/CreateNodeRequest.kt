package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CreateNodeRequest(
        @JsonProperty("host") @Json(name = "host") val hostId: Long,
        @JsonProperty("name") @Json(name = "name") val name: String,
        @JsonProperty("isDefault") @Json(name = "isDefault") val isDefault: Boolean,
        @JsonProperty("type") @Json(name = "type") val type: String,
        @JsonProperty("listen") @Json(name = "listen") val listen: String,
        @JsonProperty("port") @Json(name = "port") val port: Int,
        @JsonProperty("genesis") @Json(name = "genesis") val genesisFileId: Long,
        @JsonProperty("generateColdKeys") @Json(name = "generateColdKeys") val generateColdKeys: Boolean,
        @JsonProperty("coldSKey") @Json(name = "coldSKey") val coldSKey: FileUpload?,
        @JsonProperty("coldVKey") @Json(name = "coldVKey") val coldVKey: FileUpload?,
        @JsonProperty("generateVRFKeys") @Json(name = "generateVRFKeys") val generateVRFKeys: Boolean,
        @JsonProperty("vrfSKey") @Json(name = "vrfSKey") val vrfSKey: FileUpload?,
        @JsonProperty("vrfVKey") @Json(name = "vrfVKey") val vrfVKey: FileUpload?,
        @JsonProperty("generateKESKeys") @Json(name = "generateKESKeys") val generateKESKeys: Boolean,
        @JsonProperty("kesSKey") @Json(name = "kesSKey") val kesSKey: FileUpload?,
        @JsonProperty("kesVKey") @Json(name = "kesVKey") val kesVKey: FileUpload?,
        @JsonProperty("ownerStakingSKey") @Json(name = "ownerStakingSKey") val ownerStakingSKeyId: Long?,
        @JsonProperty("ownerStakingVKey") @Json(name = "ownerStakingVKey") val ownerStakingVKeyId: Long?,
        @JsonProperty("poolPledge") @Json(name = "poolPledge") val poolPledge: Long?,
        @JsonProperty("poolCost") @Json(name = "poolCost") val poolCost: Long?,
        @JsonProperty("poolMargin") @Json(name = "poolMargin") val poolMargin: String,
        @JsonProperty("sudoPassword") @Json(name = "sudoPassword") val sudoPassword: String?
)