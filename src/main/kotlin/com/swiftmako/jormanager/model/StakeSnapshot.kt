package com.swiftmako.jormanager.model


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigInteger

@JsonClass(generateAdapter = true)
data class StakeSnapshot(
        @Json(name = "activeStakeMark")
        val activeStakeMark: BigInteger,
        @Json(name = "activeStakeSet")
        val activeStakeSet: BigInteger,
        @Json(name = "activeStakeGo")
        val activeStakeGo: BigInteger,
        @Json(name = "poolStakeMark")
        val poolStakeMark: BigInteger,
        @Json(name = "poolStakeSet")
        val poolStakeSet: BigInteger,
        @Json(name = "poolStakeGo")
        val poolStakeGo: BigInteger,
)