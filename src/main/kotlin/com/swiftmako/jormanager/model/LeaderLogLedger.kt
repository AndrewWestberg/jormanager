package com.swiftmako.jormanager.model

import java.math.BigDecimal

data class LeaderLogLedger(
        val decentralizationParameter: Double,
        val futureDecentralizationParameter: Double,
        val poolIdToSigma: Map<String, BigDecimal>,
        val futurePoolIdToSigma: Map<String, BigDecimal>,
        val extraPraosEntropy: String?,
        val futureExtraPraosEntropy: String?,
)