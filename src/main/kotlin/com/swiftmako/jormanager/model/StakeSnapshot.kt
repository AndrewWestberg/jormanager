package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigInteger

@JsonClass(generateAdapter = true)
data class StakeSnapshot(
    @param:Json(name = "pools")
    val pools: Map<String, Snapshot>,
    @param:Json(name = "total")
    val total: Snapshot,
)

@JsonClass(generateAdapter = true)
data class Snapshot(
    @param:Json(name = "stakeMark")
    val stakeMark: BigInteger,
    @param:Json(name = "stakeSet")
    val stakeSet: BigInteger,
    @param:Json(name = "stakeGo")
    val stakeGo: BigInteger,
)
