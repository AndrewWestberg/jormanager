package com.swiftmako.jormanager.model


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigInteger

@JsonClass(generateAdapter = true)
data class StakeSnapshot(
    @Json(name = "pools")
    val pools: Map<String, Snapshot>,
    @Json(name = "total")
    val total: Snapshot,
)

@JsonClass(generateAdapter = true)
data class Snapshot(
    @Json(name = "stakeMark")
    val stakeMark: BigInteger,
    @Json(name = "stakeSet")
    val stakeSet: BigInteger,
    @Json(name = "stakeGo")
    val stakeGo: BigInteger,
)