package com.swiftmako.jormanager.model


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigInteger

@JsonClass(generateAdapter = true)
data class EditPoolConfigRequest(
        @Json(name = "id")
        val id: Long,
        @Json(name = "spendingPassword")
        val spendingPassword: String,
        @Json(name = "registrationFeesAccount")
        val registrationFeesAccount: Long,
        @Json(name = "ownerStakingAccount")
        val ownerStakingAccount: Long,
        @Json(name = "rewardsStakingAccount")
        val rewardsStakingAccount: Long,
        @Json(name = "poolPledge")
        val poolPledge: BigInteger,
        @Json(name = "poolCost")
        val poolCost: BigInteger,
        @Json(name = "poolMargin")
        val poolMargin: String
)