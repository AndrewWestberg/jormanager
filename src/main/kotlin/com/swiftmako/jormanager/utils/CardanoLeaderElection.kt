package com.swiftmako.jormanager.utils

import com.swiftmako.jormanager.model.StakeFraction
import java.math.BigDecimal
import java.math.BigInteger

/** Prepared, immutable inputs for Cardano node's E34 leader eligibility comparison. */
object CardanoLeaderElection {
    class ActiveSlotCoefficient internal constructor(
        val value: BigDecimal,
        internal val cRaw: BigInteger?,
    )

    class LeaderThreshold internal constructor(
        val vrfSizeBytes: Int,
        private val maximum: BigInteger,
        internal val sigmaRaw: BigInteger,
        internal val cRaw: BigInteger?,
        internal val xRaw: BigInteger?,
    ) {
        fun isLeader(certNat: BigInteger): Boolean {
            require(certNat.signum() >= 0 && certNat <= maximum) { "VRF natural must be between zero and $maximum" }
            // Node accepts f = 1 before taking the logarithm, even when sigma = 0.
            val exponent = xRaw ?: return true
            return CardanoFixedPoint.taylorExpCmp(calculateQ(certNat), exponent) == CardanoFixedPoint.Comparison.BELOW
        }

        internal fun qRaw(certNat: BigInteger): BigInteger {
            require(certNat.signum() >= 0 && certNat <= maximum) { "VRF natural must be between zero and $maximum" }
            return calculateQ(certNat)
        }

        private fun calculateQ(certNat: BigInteger): BigInteger =
            if (certNat == maximum) maximum * CardanoFixedPoint.SCALE else CardanoFixedPoint.fromRatio(maximum, maximum - certNat)
    }

    fun prepareActiveSlotCoefficient(f: BigDecimal): ActiveSlotCoefficient {
        require(f.signum() > 0 && f <= BigDecimal.ONE) { "Active slot coefficient must satisfy 0 < f <= 1" }
        require(f.stripTrailingZeros().scale() <= 19) { "Active slot coefficient must have at most 19 decimal places" }
        val c =
            if (f.compareTo(BigDecimal.ONE) == 0) {
                null
            } else {
                val fRaw = f.multiply(BigDecimal(CardanoFixedPoint.SCALE)).toBigIntegerExact()
                CardanoFixedPoint.ln(CardanoFixedPoint.SCALE - fRaw)
            }
        return ActiveSlotCoefficient(f, c)
    }

    fun prepare(
        activeSlots: ActiveSlotCoefficient,
        stake: StakeFraction,
        vrfSizeBytes: Int,
    ): LeaderThreshold {
        require(vrfSizeBytes == 32 || vrfSizeBytes == 64) { "VRF width must be 32 or 64 bytes" }
        val sigma = CardanoFixedPoint.fromRatio(stake.poolStake, stake.activeStake)
        val c = activeSlots.cRaw
        // Negate after the FLOOR multiplication, not before it.
        val x = c?.let { -CardanoFixedPoint.multiply(sigma, it) }
        return LeaderThreshold(vrfSizeBytes, BigInteger.ONE.shiftLeft(8 * vrfSizeBytes), sigma, c, x)
    }
}
