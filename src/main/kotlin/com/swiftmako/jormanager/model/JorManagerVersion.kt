package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty

data class JorManagerVersion(
    @param:JsonProperty("version") val version: String,
    @param:JsonProperty("mp") val mp: Boolean,
    @param:JsonProperty("minUTxOValue") val minUTxOValue: Long,
)
