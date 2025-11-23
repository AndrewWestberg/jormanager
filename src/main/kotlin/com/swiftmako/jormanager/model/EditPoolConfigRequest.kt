package com.swiftmako.jormanager.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.math.BigInteger

@JsonClass(generateAdapter = true)
data class EditPoolConfigRequest(
    @param:Json(name = "id")
    val id: Long,
    @param:Json(name = "spendingPassword")
    val spendingPassword: String,
    @param:Json(name = "registrationFeesAccount")
    val registrationFeesAccount: Long,
    @param:Json(name = "ownerStakingAccount")
    val ownerStakingAccount: Long,
    @param:Json(name = "rewardsStakingAccount")
    val rewardsStakingAccount: Long,
    @param:Json(name = "poolPledge")
    val poolPledge: BigInteger,
    @param:Json(name = "poolCost")
    val poolCost: BigInteger,
    @param:Json(name = "poolMargin")
    val poolMargin: String
)
