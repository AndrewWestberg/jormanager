package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty

data class JorManagerVersion(
        @JsonProperty("version") val version: String,
        @JsonProperty("mp") val mp: Boolean,
        @JsonProperty("minUTxOValue") val minUTxOValue: Long,
)
