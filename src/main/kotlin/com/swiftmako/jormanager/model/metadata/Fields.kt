package com.swiftmako.jormanager.model.metadata

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Fields(
    @param:Json(name = "AWSAccessKeyId")
    val awsAccessKeyId: String,
    @param:Json(name = "key")
    val key: String,
    @param:Json(name = "policy")
    val policy: String,
    @param:Json(name = "signature")
    val signature: String,
    @param:Json(name = "x-amz-security-token")
    val securityToken: String
)
