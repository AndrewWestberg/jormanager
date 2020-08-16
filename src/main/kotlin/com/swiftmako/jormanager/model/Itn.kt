package com.swiftmako.jormanager.model


import com.fasterxml.jackson.annotation.JsonProperty

data class Itn(
        @JsonProperty("publicKey") val publicKey: String?,
        @JsonProperty("privateKey") val privateKey: String?
)