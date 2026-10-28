package com.swiftmako.jormanager.model

import com.swiftmako.jormanager.utils.CborSpan
import java.math.BigInteger

/** Byte spans retain the source buffer; callers must not mutate the input bytes. */
data class DecodedLedgerBlock(
    val header: CborSpan,
    val body: CborSpan,
    val transactions: List<DecodedTransactionBatch>,
    val leiosCertificate: CborSpan?,
    val perasCertificate: CborSpan?
)

data class DecodedTransactionBatch(
    val raw: CborSpan,
    val parent: DecodedTransactionMember,
    val children: List<DecodedTransactionMember>,
    /** Null denotes submission, not a producer-confirmed success. */
    val isValid: Boolean?
) {
    /** Pending transactions have no adopted effects. */
    val ordinaryEffectsApplied: Boolean get() = isValid == true
    val collateralEffectsApplied: Boolean get() = isValid == false
}

data class DecodedTransactionMember(
    val bodyId: ByteArray,
    val body: CborSpan,
    val witnesses: CborSpan,
    val auxiliaryData: CborSpan?,
    val inputs: List<DecodedInput>,
    val outputs: List<DecodedOutput>,
    val collateralInputs: List<DecodedInput>,
    val referenceInputs: List<DecodedInput>,
    val collateralReturn: DecodedOutput?,
    val fee: BigInteger?,
    val totalCollateral: BigInteger?,
    val withdrawals: List<DecodedAccountAmount>,
    val directDeposits: List<DecodedAccountAmount>,
    val accountIntervals: List<DecodedAccountInterval>,
    val startingAccountIntervals: List<DecodedAccountInterval>,
    val mint: List<DecodedAsset>,
    val witnessData: DecodedWitnessData,
    val auxiliary: DecodedAuxiliaryData?,
    val fields: Map<Int, CborSpan>
)

data class DecodedInput(val transactionId: ByteArray, val index: Int)
data class DecodedAsset(val policy: ByteArray, val name: ByteArray, val quantity: BigInteger)
data class DecodedOutput(
    val raw: CborSpan,
    val address: ByteArray,
    val coin: BigInteger,
    val assets: List<DecodedAsset>,
    val datum: CborSpan?,
    val referenceScript: CborSpan?
)
data class DecodedAccountAmount(val address: ByteArray, val amount: BigInteger)
data class DecodedAccountInterval(
    val address: ByteArray,
    val lowerInclusive: BigInteger?,
    val upperExclusive: BigInteger?,
    val exact: BigInteger?
)

data class DecodedScript(val language: Int, val raw: CborSpan)
data class DecodedRedeemer(
    val purpose: Int,
    val index: BigInteger,
    val data: CborSpan,
    val memory: BigInteger,
    val steps: BigInteger
)
data class DecodedWitnessData(
    val scripts: List<DecodedScript>,
    val datums: List<CborSpan>,
    val redeemers: List<DecodedRedeemer>
)

data class DecodedAuxiliaryData(
    val metadata: CborSpan?,
    val scripts: List<DecodedScript>
)
