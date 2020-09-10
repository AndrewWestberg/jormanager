package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty

data class UpdateColorRequest(
        @JsonProperty("id") val id: Long,
        @JsonProperty("color") val color: String,
)