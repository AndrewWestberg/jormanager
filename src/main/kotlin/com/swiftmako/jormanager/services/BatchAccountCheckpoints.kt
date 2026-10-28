package com.swiftmako.jormanager.services

import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.DecodedAccountInterval
import com.swiftmako.jormanager.model.DecodedTransactionBatch
import java.math.BigInteger

/**
 * Checks facts derivable from an adopted batch without inventing rewards or executing the ledger.
 * Exact assertions establish a balance at their checkpoint; ranges alone do not establish a total.
 * The node remains authoritative for account existence, rewards and ledger/script acceptance.
 */
internal class BatchAccountCheckpoints(batch: DecodedTransactionBatch) {
    private val original = HashMap<String, BigInteger>()
    private val delta = HashMap<String, BigInteger>()
    private val withdrawn = HashMap<String, BigInteger>()

    init {
        batch.parent.startingAccountIntervals.forEach { interval -> interval.exact?.let { original[interval.address.toHexString()] = it } }
    }

    fun starting(interval: DecodedAccountInterval) {
        original[interval.address.toHexString()]?.let { checkInterval(interval, it) }
    }

    fun current(interval: DecodedAccountInterval) {
        val key = interval.address.toHexString()
        val change = delta[key] ?: BigInteger.ZERO
        interval.exact?.let { exact ->
            val inferred = exact - change
            require(inferred.signum() >= 0) { "Account checkpoint contradicts original balance" }
            original.putIfAbsent(key, inferred)
        }
        original[key]?.let {
            require((withdrawn[key] ?: BigInteger.ZERO) <= it) { "Withdrawal uses same-batch direct deposits" }
            checkInterval(interval, it + change)
        }
    }

    fun withdraw(address: ByteArray, amount: BigInteger) {
        val key = address.toHexString()
        val total = (withdrawn[key] ?: BigInteger.ZERO) + amount
        original[key]?.let { require(total <= it) { "Withdrawal uses same-batch direct deposits" } }
        withdrawn[key] = total
        delta[key] = (delta[key] ?: BigInteger.ZERO) - amount
    }

    fun deposit(address: ByteArray, amount: BigInteger) {
        val key = address.toHexString()
        delta[key] = (delta[key] ?: BigInteger.ZERO) + amount
    }

    private fun checkInterval(interval: DecodedAccountInterval, value: BigInteger) {
        require(interval.exact == null || value == interval.exact) { "Account equality checkpoint mismatch" }
        require(interval.lowerInclusive == null || value >= interval.lowerInclusive) { "Account lower checkpoint mismatch" }
        require(interval.upperExclusive == null || value < interval.upperExclusive) { "Account upper checkpoint mismatch" }
    }
}
