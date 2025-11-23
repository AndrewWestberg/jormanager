package com.swiftmako.jormanager.model

import com.fasterxml.jackson.annotation.JsonProperty

data class Itn(
    @param:JsonProperty("publicKey") val publicKey: String?,
    @param:JsonProperty("privateKey") val privateKey: String?
)
