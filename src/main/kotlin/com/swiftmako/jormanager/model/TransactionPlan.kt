package com.swiftmako.jormanager.model

import java.math.BigInteger

data class RecipientOutputRequest(
    val destinationKey: String,
    val address: String,
    val lovelace: BigInteger,
    val nativeAssets: List<NativeAsset> = emptyList(),
    val includeAllSpentNativeAssets: Boolean = false,
)

data class WithdrawalItem(
    val stakingAddress: String,
    val lovelace: BigInteger,
)

data class PlannedOutput(
    val address: String,
    val lovelace: BigInteger,
    val nativeAssets: List<NativeAsset> = emptyList(),
) {
    fun toCliTxOutArg(): String {
        val sb = StringBuilder()
        sb.append("'").append(address).append("+").append(lovelace)
        nativeAssets
            .filter { it.amount > BigInteger.ZERO }
            .forEach { asset ->
                val currency = "${asset.policy}.${asset.name}".trimEnd('.')
                sb.append("+").append(asset.amount).append(" ").append(currency)
            }
        sb.append("'")
        return sb.toString()
    }
}

data class TransactionPlan(
    val selectedUtxos: List<Utxo>,
    val outputs: List<PlannedOutput>,
    val fee: BigInteger,
    val tokenFees: List<BigInteger> = emptyList(),
    val tokenKeepFee: BigInteger = BigInteger.ZERO,
    val tokenLocked: BigInteger = BigInteger.ZERO,
    val estimatedTxBytes: Int,
    val selectionMode: SelectionMode = SelectionMode.STANDARD,
    val usedFallback: Boolean = false,
)
