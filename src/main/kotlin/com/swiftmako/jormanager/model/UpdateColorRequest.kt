package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty

data class UpdateColorRequest(
    @param:JsonProperty("id")
    @param:JsonAlias("nodeId")
    val id: Long,
    @param:JsonProperty("color") val color: String,
)
