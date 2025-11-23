package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty

data class UpdateColorRequest(
    @param:JsonProperty("id") val id: Long,
    @param:JsonProperty("color") val color: String,
)
