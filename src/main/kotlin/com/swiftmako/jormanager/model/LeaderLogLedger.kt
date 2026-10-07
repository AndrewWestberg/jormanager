package com.swiftmako.jormanager.model

import java.math.BigDecimal

data class LeaderLogLedger(
    val decentralizationParameter: BigDecimal,
    val futureDecentralizationParameter: BigDecimal,
    val poolIdToSigma: Map<String, StakeFraction>,
    val futurePoolIdToSigma: Map<String, StakeFraction>,
    val extraPraosEntropy: String?,
    val futureExtraPraosEntropy: String?,
)
