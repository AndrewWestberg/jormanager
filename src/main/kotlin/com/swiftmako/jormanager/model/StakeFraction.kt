package com.swiftmako.jormanager.model

import java.math.BigInteger

/** Keep stake rational until the ledger's E34 conversion boundary. */
data class StakeFraction(val poolStake: BigInteger, val activeStake: BigInteger) {
    init {
        require(activeStake.signum() > 0) { "Active stake must be positive" }
        require(poolStake.signum() >= 0 && poolStake <= activeStake) { "Pool stake must be between zero and active stake" }
    }
}
