package com.swiftmako.jormanager.model

import java.math.BigInteger

data class NativeAsset(
    val name: String,
    val policy: String,
    val amount: BigInteger,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as NativeAsset

        if (name != other.name) return false
        if (policy != other.policy) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + policy.hashCode()
        return result
    }
}
