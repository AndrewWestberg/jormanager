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
)

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
