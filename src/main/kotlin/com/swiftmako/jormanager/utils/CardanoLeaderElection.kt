package com.swiftmako.jormanager.utils

import com.swiftmako.jormanager.model.StakeFraction
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Arrays

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
        internal val cutoff: BigInteger,
        internal val cutoffBytes: ByteArray?,
    ) {
        fun isLeader(certNat: BigInteger): Boolean {
            require(certNat.signum() >= 0 && certNat <= maximum) { "VRF natural must be between zero and $maximum" }
            return certNat < cutoff
        }

        fun isLeader(certNat: ByteArray): Boolean {
            require(certNat.size == vrfSizeBytes) { "VRF bytes must be $vrfSizeBytes bytes" }
            val limit = cutoffBytes ?: return true
            return Arrays.compareUnsigned(certNat, limit) < 0
        }

        internal fun qRaw(certNat: BigInteger): BigInteger {
            require(certNat.signum() >= 0 && certNat <= maximum) { "VRF natural must be between zero and $maximum" }
            return calculateQ(certNat, maximum)
        }
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
        val maximum = BigInteger.ONE.shiftLeft(8 * vrfSizeBytes)
        val sigma = CardanoFixedPoint.fromRatio(stake.poolStake, stake.activeStake)
        val c = activeSlots.cRaw
        // Negate after the FLOOR multiplication, not before it.
        val x = c?.let { -CardanoFixedPoint.multiply(sigma, it) }
        val cutoff = findCutoff(maximum, x)
        val cutoffBytes = if (cutoff >= maximum) null else toUnsignedBytes(cutoff, vrfSizeBytes)
        return LeaderThreshold(vrfSizeBytes, maximum, sigma, c, x, cutoff, cutoffBytes)
    }

    private fun findCutoff(
        maximum: BigInteger,
        x: BigInteger?,
    ): BigInteger {
        // Node accepts f = 1 before taking the logarithm, even when sigma = 0.
        val exponent = x ?: return maximum + BigInteger.ONE
        if (!isBelow(BigInteger.ZERO, maximum, exponent)) return BigInteger.ZERO
        if (isBelow(maximum, maximum, exponent)) return maximum + BigInteger.ONE
        var low = BigInteger.ZERO
        var high = maximum
        while (low + BigInteger.ONE < high) {
            val mid = (low + high).shiftRight(1)
            if (isBelow(mid, maximum, exponent)) {
                low = mid
            } else {
                high = mid
            }
        }
        return high
    }

    private fun isBelow(
        certNat: BigInteger,
        maximum: BigInteger,
        exponent: BigInteger,
    ): Boolean = CardanoFixedPoint.taylorExpCmp(calculateQ(certNat, maximum), exponent) == CardanoFixedPoint.Comparison.BELOW

    private fun calculateQ(
        certNat: BigInteger,
        maximum: BigInteger,
    ): BigInteger =
        if (certNat == maximum) {
            maximum * CardanoFixedPoint.SCALE
        } else {
            CardanoFixedPoint.fromRatio(maximum, maximum - certNat)
        }

    private fun toUnsignedBytes(
        value: BigInteger,
        width: Int,
    ): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == width -> raw
            raw.size == width + 1 -> {
                check(raw[0] == 0.toByte())
                raw.copyOfRange(1, raw.size)
            }
            else -> {
                check(raw.size < width)
                ByteArray(width).also { padded ->
                    System.arraycopy(raw, 0, padded, width - raw.size, raw.size)
                }
            }
        }
    }
}
