package com.swiftmako.jormanager.model.metadata


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Fields(
        @Json(name = "AWSAccessKeyId")
        val awsAccessKeyId: String,
        @Json(name = "key")
        val key: String,
        @Json(name = "policy")
        val policy: String,
        @Json(name = "signature")
        val signature: String,
        @Json(name = "x-amz-security-token")
        val securityToken: String
)