package com.swiftmako.jormanager.utils

import java.math.BigInteger

/** E34 Data.Fixed arithmetic and NonIntegral operations used by Cardano node. */
internal object CardanoFixedPoint {
    val SCALE: BigInteger = BigInteger.TEN.pow(34)
    val EPSILON: BigInteger = BigInteger.TEN.pow(10)

    private val THREE = BigInteger.valueOf(3)
    private val E = exp(SCALE)

    enum class Comparison {
        ABOVE,
        BELOW,
        MAX_REACHED,
    }

    fun floorDiv(numerator: BigInteger, denominator: BigInteger): BigInteger {
        val (quotient, remainder) = numerator.divideAndRemainder(denominator)
        return if (remainder.signum() != 0 && numerator.signum() != denominator.signum()) {
            quotient - BigInteger.ONE
        } else {
            quotient
        }
    }

    fun fromRatio(numerator: BigInteger, denominator: BigInteger): BigInteger =
        floorDiv(numerator * SCALE, denominator)

    fun multiply(a: BigInteger, b: BigInteger): BigInteger = floorDiv(a * b, SCALE)

    fun divide(a: BigInteger, b: BigInteger): BigInteger = floorDiv(a * SCALE, b)

    fun exp(x: BigInteger): BigInteger {
        if (x.signum() < 0) return divide(SCALE, exp(-x))
        // Haskell's lazy ipow returns 1 at exponent 0 without evaluating scaleExp's 0/0.
        if (x.signum() == 0) return SCALE
        val exponent = -floorDiv(-x, SCALE)
        val scaledX = divide(x, exponent * SCALE)
        var lastX = SCALE
        var acc = SCALE
        var divisor = SCALE
        for (n in 1 until 1000) {
            val nextX = divide(multiply(lastX, scaledX), divisor)
            if (nextX.abs() < EPSILON) return integerPower(acc, exponent)
            lastX = nextX
            acc += nextX
            divisor += SCALE
        }
        return integerPower(acc, exponent)
    }

    fun ln(x: BigInteger): BigInteger {
        require(x.signum() > 0) { "ln input must be positive" }
        val exponent = findE(x)
        val remainder = divide(x, integerPower(E, exponent)) - SCALE
        return exponent * SCALE + lnContinuedFraction(remainder)
    }

    fun taylorExpCmp(q: BigInteger, x: BigInteger): Comparison {
        var acc = SCALE
        var err = x
        var divisor = SCALE
        repeat(1000) {
            val divisorNext = divisor + SCALE
            val errNext = divide(multiply(err, x), divisorNext)
            val accNext = acc + err
            val errorTerm = (errNext * THREE).abs()
            if (q >= accNext + errorTerm) return Comparison.ABOVE
            if (q < accNext - errorTerm) return Comparison.BELOW
            acc = accNext
            err = errNext
            divisor = divisorNext
        }
        return Comparison.MAX_REACHED
    }

    private fun integerPower(base: BigInteger, exponent: BigInteger): BigInteger =
        if (exponent.signum() < 0) {
            divide(SCALE, positiveIntegerPower(base, -exponent))
        } else {
            positiveIntegerPower(base, exponent)
        }

    // Preserve ipow' evaluation order: odd powers multiply by power(n-1), even powers square power(n/2).
    private fun positiveIntegerPower(base: BigInteger, exponent: BigInteger): BigInteger =
        when {
            exponent.signum() == 0 -> SCALE
            !exponent.testBit(0) -> {
                val half = positiveIntegerPower(base, exponent.shiftRight(1))
                multiply(half, half)
            }
            else -> multiply(base, positiveIntegerPower(base, exponent - BigInteger.ONE))
        }

    private fun findE(x: BigInteger): BigInteger {
        var lower = -BigInteger.ONE
        var upper = BigInteger.ONE
        var lowerValue = divide(SCALE, E)
        var upperValue = E
        while (lowerValue > x || x > upperValue) {
            lowerValue = multiply(lowerValue, lowerValue)
            upperValue = multiply(upperValue, upperValue)
            lower = lower.shiftLeft(1)
            upper = upper.shiftLeft(1)
        }
        while (lower + BigInteger.ONE != upper) {
            val mid = lower + (upper - lower).shiftRight(1)
            if (x < integerPower(E, mid)) {
                upper = mid
            } else {
                lower = mid
            }
        }
        return lower
    }

    private fun lnContinuedFraction(x: BigInteger): BigInteger {
        require(x.signum() >= 0) { "continued fraction input must be nonnegative" }
        var aNm2 = SCALE
        var bNm2 = BigInteger.ZERO
        var aNm1 = BigInteger.ZERO
        var bNm1 = SCALE
        var lastValue: BigInteger? = null
        // cf starts at depth 0 and still calculates its convergent at maxN == 1000.
        for (n in 0..1000) {
            val kRaw = BigInteger.valueOf(((n + 1) / 2).toLong()) * SCALE
            val an = if (n == 0) x else multiply(multiply(kRaw, kRaw), x)
            val bn = BigInteger.valueOf((n + 1).toLong()) * SCALE
            val aN = multiply(bn, aNm1) + multiply(an, aNm2)
            val bN = multiply(bn, bNm1) + multiply(an, bNm2)
            val value = divide(aN, bN)
            if (n == 1000 || (lastValue != null && (lastValue - value).abs() < EPSILON)) return value
            lastValue = value
            aNm2 = aNm1
            bNm2 = bNm1
            aNm1 = aN
            bNm1 = bN
        }
        error("unreachable continued fraction limit")
    }
}
