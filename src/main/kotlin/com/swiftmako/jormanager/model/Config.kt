package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Config(
    @param:Json(name = "ShelleyGenesisHash")
    val shelleyGenesisHash: String,
    @param:Json(name = "ByronGenesisHash")
    val byronGenesisHash: String? = null,
    @param:Json(name = "RequiresNetworkMagic")
    val requiresNetworkMagic: String? = null,
    @param:Json(name = "AlonzoGenesisHash")
    val alonzoGenesisHash: String? = null,
    @param:Json(name = "ConwayGenesisHash")
    val conwayGenesisHash: String? = null,
    @param:Json(name = "DijkstraGenesisHash")
    val dijkstraGenesisHash: String? = null,
)
