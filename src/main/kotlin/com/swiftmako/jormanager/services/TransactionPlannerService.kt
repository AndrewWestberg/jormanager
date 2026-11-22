package com.swiftmako.jormanager.services

import com.bloxbean.cardano.client.address.Address
import com.bloxbean.cardano.client.api.MinAdaCalculator
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier
import com.bloxbean.cardano.client.api.UtxoSupplier
import com.bloxbean.cardano.client.api.common.OrderEnum
import com.bloxbean.cardano.client.api.helper.impl.FeeCalculationServiceImpl
import com.bloxbean.cardano.client.api.model.Amount
import com.bloxbean.cardano.client.api.model.ProtocolParams
import com.bloxbean.cardano.client.api.model.Utxo as SdkUtxo
import com.bloxbean.cardano.client.api.util.AssetUtil
import com.bloxbean.cardano.client.coinselection.UtxoSelectionStrategy
import com.bloxbean.cardano.client.coinselection.impl.LargestFirstUtxoSelectionStrategy
import com.bloxbean.cardano.client.coinselection.impl.RandomImproveUtxoSelectionStrategy
import com.bloxbean.cardano.client.metadata.helper.JsonNoSchemaToMetadataConverter
import com.bloxbean.cardano.client.spec.Era
import com.bloxbean.cardano.client.transaction.spec.Asset
import com.bloxbean.cardano.client.transaction.spec.AuxiliaryData
import com.bloxbean.cardano.client.transaction.spec.MultiAsset
import com.bloxbean.cardano.client.transaction.spec.Transaction
import com.bloxbean.cardano.client.transaction.spec.TransactionBody
import com.bloxbean.cardano.client.transaction.spec.TransactionInput
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet
import com.bloxbean.cardano.client.transaction.spec.Value
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness
import com.bloxbean.cardano.client.transaction.spec.Withdrawal
import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.ktx.sumByBigInteger
import com.swiftmako.jormanager.model.NativeAsset
import com.swiftmako.jormanager.model.PlannedOutput
import com.swiftmako.jormanager.model.ProtocolParameters
import com.swiftmako.jormanager.model.SelectionMode
import com.swiftmako.jormanager.model.TransactionPlan
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.model.WithdrawalItem
import com.swiftmako.jormanager.model.toNativeAssetMap
import com.swiftmako.jormanager.model.tx.TxSigned
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Optional
import org.springframework.stereotype.Service

data class WalletAccountLineItem(
    val destinationKey: String,
    val address: String,
    val currency: String,
    val amount: BigInteger,
    val isPercent: Boolean = false,
    val percent: Int = 0,
    val isFeePayerClaimAccount: Boolean = false,
)

@Service
class TransactionPlannerService(
    private val randomImproveStrategyFactory: ((UtxoSupplier) -> UtxoSelectionStrategy)? = null,
    private val largestFirstStrategyFactory: ((UtxoSupplier) -> UtxoSelectionStrategy)? = null,
) {
    private val log = KotlinLogging.logger("TransactionPlannerService")
    private val txEnvelopeAdapter by lazy { Moshi.Builder().build().adapter(TxSigned::class.java) }

    companion object {
        const val LOVELACE_UNIT = "lovelace"
        const val NATIVE_ASSET_MAX_GROUP_SIZE = 20
        const val NATIVE_ASSET_MIN_GROUP_SIZE = 5
        const val DEFAULT_MAX_TX_SIZE = 16_384
        const val CONWAY_CBOR_SAFETY_BYTES = 16
        const val APPROX_INPUT_CBOR_BYTES = 37
        const val BASE_TX_OVERHEAD_BYTES = 320
        const val WITNESS_CBOR_BYTES = 104
        val DEFAULT_DUMMY_STAKE_ADDRESS: String =
            com.bloxbean.cardano.client.crypto.Bech32.encode(
                byteArrayOf(0xe0.toByte()) + ByteArray(28) { 1 },
                "stake_test",
            )
        val DEFRAGMENT_SPLIT_THRESHOLD_LOVELACE: BigInteger = BigInteger.valueOf(10_000_000L)
        val INITIAL_ESTIMATED_FEE: BigInteger = BigInteger.valueOf(200_000L)
        val MIN_PURE_ADA_FLOOR: BigInteger = BigInteger.valueOf(1_000_000L)
    }

    /**
     * Programmatically calculates the minimum lovelace required for an output carrying [nativeAssets]
     * at [address] using Bloxbean's [MinAdaCalculator] and [ProtocolParameters] without CLI dry-runs.
     */
    fun calculateMinAda(
        address: String,
        nativeAssets: List<NativeAsset>,
        protocolParameters: ProtocolParameters,
    ): BigInteger {
        val sdkParams = toSdkProtocolParams(protocolParameters)
        val minAdaCalculator = MinAdaCalculator(sdkParams)
        val sdkOutput =
            toSdkTransactionOutput(
                PlannedOutput(
                    address = address,
                    lovelace = MIN_PURE_ADA_FLOOR,
                    nativeAssets = nativeAssets,
                )
            )
        val calculated = minAdaCalculator.calculateMinAda(sdkOutput)
        val legacyFloor = protocolParameters.minUTxOValue?.let { BigInteger.valueOf(it) } ?: BigInteger.ZERO
        return calculated.max(legacyFloor)
    }

    /**
     * Programmatically calculates transaction size (bytes) and dynamic fee (lovelace) for a candidate
     * transaction without requiring signing keys or remote CLI dry-runs.
     */
    fun calculateFeeAndSize(
        selectedUtxos: List<Utxo>,
        outputs: List<PlannedOutput>,
        currentFeeEstimate: BigInteger,
        protocolParameters: ProtocolParameters,
        ttl: Long = 100_000_000L,
        withdrawals: List<WithdrawalItem> = emptyList(),
        metadataJson: String? = null,
        witnessCount: Int = 1,
        extraPayloadBytes: Int = 0,
        referenceScriptBytes: Long = 0L,
    ): Pair<BigInteger, Int> {
        val sdkParams = toSdkProtocolParams(protocolParameters)
        val serializedBytes =
            serializeDummySignedTransaction(
                selectedUtxos = selectedUtxos,
                outputs = outputs,
                fee = currentFeeEstimate.max(BigInteger.valueOf(155_381L)),
                ttl = ttl,
                withdrawals = withdrawals,
                metadataJson = metadataJson,
                witnessCount = witnessCount,
            )
        val estimatedTxBytes = serializedBytes.size + extraPayloadBytes + CONWAY_CBOR_SAFETY_BYTES
        val emptySupplier = InMemoryUtxoSupplier(emptyMap())
        val feeService = FeeCalculationServiceImpl(emptySupplier, ProtocolParamsSupplier { sdkParams })
        val linearFee = feeService.calculateFee(ByteArray(estimatedTxBytes), sdkParams)
        val refScriptFee =
            if (referenceScriptBytes > 0L) {
                val baseCost = sdkParams.minFeeRefScriptCostPerByte ?: BigDecimal.valueOf(15L)
                feeService.tierRefScriptFee(BigDecimal("1.2"), 25_600, baseCost, referenceScriptBytes)
            } else {
                BigInteger.ZERO
            }
        return (linearFee + refScriptFee) to estimatedTxBytes
    }

    /**
     * Plans administrative transactions (pool registration, pool config/metadata/relay updates,
     * pool retirement, governance voting, staking address registration/deregistration) using
     * Random-Improve coin selection by default with automatic Largest-First fallback.
     *
     * Balances: selectedInputs + depositRefund == deposit + fee + changeOutputs
     */
    fun planAdminTransaction(
        utxos: List<Utxo>,
        changeAddress: String,
        protocolParameters: ProtocolParameters,
        deposit: BigInteger = BigInteger.ZERO,
        depositRefund: BigInteger = BigInteger.ZERO,
        ttl: Long = 100_000_000L,
        witnessCount: Int = 1,
        extraPayloadBytes: Int = 0,
        referenceScriptBytes: Long = 0L,
        selectionMode: SelectionMode = SelectionMode.STANDARD,
    ): TransactionPlan {
        if (utxos.isEmpty()) {
            throw IOException("No unspent UTxOs available in fee payer account!")
        }

        val maxTxSize = resolveMaxTxSize(protocolParameters)
        val minPureAda = calculateMinAda(changeAddress, emptyList(), protocolParameters).max(MIN_PURE_ADA_FLOOR)

        if (selectionMode == SelectionMode.DEFRAGMENT) {
            return planDefragmentTransaction(
                utxos = utxos,
                changeAddress = changeAddress,
                recipientItems = emptyList(),
                deposit = deposit,
                depositRefund = depositRefund,
                withdrawals = emptyList(),
                metadataJson = null,
                ttl = ttl,
                witnessCount = witnessCount,
                extraPayloadBytes = extraPayloadBytes,
                referenceScriptBytes = referenceScriptBytes,
                protocolParameters = protocolParameters,
                isPreview = false,
            )
        }

        val maxInputs = computeMaxSafeInputCount(maxTxSize, witnessCount, extraPayloadBytes, 2)
        var estimatedFee = INITIAL_ESTIMATED_FEE
        var requiredTokenKeepAda = BigInteger.ZERO
        var selectedUtxos: List<Utxo> = emptyList()
        var usedFallback = false

        for (attempt in 0 until 6) {
            val netExternalNeed = (deposit - depositRefund).max(BigInteger.ZERO)
            val targetLovelace = netExternalNeed + estimatedFee + minPureAda + requiredTokenKeepAda
            val selectionOutcome =
                selectStandardUtxos(
                    utxos = utxos,
                    address = changeAddress,
                    requestedLovelace = targetLovelace,
                    requestedAssets = emptyMap(),
                    maxInputs = maxInputs,
                )
            selectedUtxos = selectionOutcome.first
            usedFallback = usedFallback || selectionOutcome.second

            val tokenChangeOutputs =
                buildGroupedTokenOutputs(
                    address = changeAddress,
                    assetMap = selectedUtxos.toNativeAssetMap(),
                    protocolParameters = protocolParameters,
                )
            val tokenMinAdaTotal = tokenChangeOutputs.sumByBigInteger { it.lovelace }
            val totalInputLovelace = selectedUtxos.sumByBigInteger { it.lovelace } + depositRefund

            val provisionalAvailablePureAda = totalInputLovelace - deposit - estimatedFee - tokenMinAdaTotal
            val provisionalOutputs =
                buildChangeOutputsWithResidualAda(
                    changeAddress = changeAddress,
                    tokenChangeOutputs = tokenChangeOutputs,
                    residualPureAda = provisionalAvailablePureAda.max(minPureAda),
                    minPureAda = minPureAda,
                    splitCleanAda = false,
                )

            val (newFee, estimatedBytes) =
                calculateFeeAndSize(
                    selectedUtxos = selectedUtxos,
                    outputs = provisionalOutputs,
                    currentFeeEstimate = estimatedFee,
                    protocolParameters = protocolParameters,
                    ttl = ttl,
                    witnessCount = witnessCount,
                    extraPayloadBytes = extraPayloadBytes,
                    referenceScriptBytes = referenceScriptBytes,
                )

            val finalResidualAda = totalInputLovelace - deposit - newFee - tokenMinAdaTotal
            val needsPureAdaFloor = if (tokenChangeOutputs.isEmpty()) minPureAda else BigInteger.ZERO
            if (finalResidualAda >= needsPureAdaFloor && newFee <= estimatedFee) {
                val finalOutputs =
                    buildChangeOutputsWithResidualAda(
                        changeAddress = changeAddress,
                        tokenChangeOutputs = tokenChangeOutputs,
                        residualPureAda = finalResidualAda,
                        minPureAda = minPureAda,
                        splitCleanAda = false,
                    )
                val (verifiedFee, verifiedBytes) =
                    calculateFeeAndSize(
                        selectedUtxos = selectedUtxos,
                        outputs = finalOutputs,
                        currentFeeEstimate = newFee,
                        protocolParameters = protocolParameters,
                        ttl = ttl,
                        witnessCount = witnessCount,
                        extraPayloadBytes = extraPayloadBytes,
                        referenceScriptBytes = referenceScriptBytes,
                    )
                val adjustedOutputs =
                    adjustOutputsForFeeDelta(
                        outputs = finalOutputs,
                        changeAddress = changeAddress,
                        feeDelta = verifiedFee - newFee,
                        minPureAda = minPureAda,
                    )
                val (_, finalBytes) =
                    calculateFeeAndSize(
                        selectedUtxos = selectedUtxos,
                        outputs = adjustedOutputs,
                        currentFeeEstimate = verifiedFee,
                        protocolParameters = protocolParameters,
                        ttl = ttl,
                        witnessCount = witnessCount,
                        extraPayloadBytes = extraPayloadBytes,
                        referenceScriptBytes = referenceScriptBytes,
                    )
                validateTransactionSize(finalBytes, maxTxSize)
                val unspentTokenLocked =
                    utxos
                        .filter { candidate -> candidate.nativeAssets.isNotEmpty() && candidate !in selectedUtxos }
                        .sumByBigInteger { it.lovelace }
                return TransactionPlan(
                    selectedUtxos = selectedUtxos,
                    outputs = adjustedOutputs,
                    fee = verifiedFee,
                    tokenKeepFee = tokenMinAdaTotal,
                    tokenLocked = unspentTokenLocked,
                    estimatedTxBytes = finalBytes,
                    selectionMode = SelectionMode.STANDARD,
                    usedFallback = usedFallback,
                )
            }

            estimatedFee = newFee.max(estimatedFee)
            requiredTokenKeepAda = tokenMinAdaTotal

            // If RandomImprove picked token-heavy UTxOs whose locked min-ADA left insufficient pure ADA,
            // escalate to unlocked-lovelace Largest-First on the final attempts.
            if (attempt >= 2 && finalResidualAda < needsPureAdaFloor) {
                val fallbackUtxos =
                    selectByNetUnlockedLovelace(
                        utxos = utxos,
                        changeAddress = changeAddress,
                        requiredNetLovelace = (deposit - depositRefund).max(BigInteger.ZERO) + estimatedFee + minPureAda,
                        requestedAssets = emptyMap(),
                        maxInputs = maxInputs,
                        protocolParameters = protocolParameters,
                    )
                if (fallbackUtxos.isNotEmpty()) {
                    selectedUtxos = fallbackUtxos
                    usedFallback = true
                    val fbTokens =
                        buildGroupedTokenOutputs(
                            address = changeAddress,
                            assetMap = selectedUtxos.toNativeAssetMap(),
                            protocolParameters = protocolParameters,
                        )
                    val fbTokenAda = fbTokens.sumByBigInteger { it.lovelace }
                    val fbInputsAda = selectedUtxos.sumByBigInteger { it.lovelace } + depositRefund
                    val (fbFee, _) =
                        calculateFeeAndSize(
                            selectedUtxos = selectedUtxos,
                            outputs =
                                buildChangeOutputsWithResidualAda(
                                    changeAddress = changeAddress,
                                    tokenChangeOutputs = fbTokens,
                                    residualPureAda = (fbInputsAda - deposit - estimatedFee - fbTokenAda).max(minPureAda),
                                    minPureAda = minPureAda,
                                    splitCleanAda = false,
                                ),
                            currentFeeEstimate = estimatedFee,
                            protocolParameters = protocolParameters,
                            ttl = ttl,
                            witnessCount = witnessCount,
                            extraPayloadBytes = extraPayloadBytes,
                            referenceScriptBytes = referenceScriptBytes,
                        )
                    val fbResidual = fbInputsAda - deposit - fbFee - fbTokenAda
                    val fbMinReq = if (fbTokens.isEmpty()) minPureAda else BigInteger.ZERO
                    if (fbResidual >= fbMinReq) {
                        val fbOutputs =
                            buildChangeOutputsWithResidualAda(
                                changeAddress = changeAddress,
                                tokenChangeOutputs = fbTokens,
                                residualPureAda = fbResidual,
                                minPureAda = minPureAda,
                                splitCleanAda = false,
                            )
                        val (verifiedFbFee, verifiedFbBytes) =
                            calculateFeeAndSize(
                                selectedUtxos = selectedUtxos,
                                outputs = fbOutputs,
                                currentFeeEstimate = fbFee,
                                protocolParameters = protocolParameters,
                                ttl = ttl,
                                witnessCount = witnessCount,
                                extraPayloadBytes = extraPayloadBytes,
                                referenceScriptBytes = referenceScriptBytes,
                            )
                        val adjustedFbOutputs =
                            adjustOutputsForFeeDelta(
                                outputs = fbOutputs,
                                changeAddress = changeAddress,
                                feeDelta = verifiedFbFee - fbFee,
                                minPureAda = minPureAda,
                            )
                        val (_, finalFbBytes) =
                            calculateFeeAndSize(
                                selectedUtxos = selectedUtxos,
                                outputs = adjustedFbOutputs,
                                currentFeeEstimate = verifiedFbFee,
                                protocolParameters = protocolParameters,
                                ttl = ttl,
                                witnessCount = witnessCount,
                                extraPayloadBytes = extraPayloadBytes,
                                referenceScriptBytes = referenceScriptBytes,
                            )
                        validateTransactionSize(finalFbBytes, maxTxSize)
                        return TransactionPlan(
                            selectedUtxos = selectedUtxos,
                            outputs = adjustedFbOutputs,
                            fee = verifiedFbFee,
                            tokenKeepFee = fbTokenAda,
                            tokenLocked =
                                utxos
                                    .filter { it.nativeAssets.isNotEmpty() && it !in selectedUtxos }
                                    .sumByBigInteger { it.lovelace },
                            estimatedTxBytes = finalFbBytes,
                            selectionMode = SelectionMode.STANDARD,
                            usedFallback = true,
                        )
                    }
                }
            }
        }

        throw IOException("Not enough funds to cover deposit ($deposit lovelace) and transaction fees!")
    }

    /**
     * Plans user wallet transactions (ADA transfers, multi-asset transfers, staking reward claims,
     * and explicit `DEFRAGMENT` UTxO cleanup workflows) locally without remote CLI dry-runs.
     */
    fun planWalletTransaction(
        utxos: List<Utxo>,
        changeAddress: String,
        toAccountItems: List<WalletAccountLineItem>,
        protocolParameters: ProtocolParameters,
        isClaim: Boolean = false,
        withdrawals: List<WithdrawalItem> = emptyList(),
        metadataJson: String? = null,
        ttl: Long = 100_000_000L,
        witnessCount: Int = 1,
        referenceScriptBytes: Long = 0L,
        selectionMode: SelectionMode = SelectionMode.STANDARD,
        fixedFeeOverride: BigInteger? = null,
        isPreview: Boolean = false,
    ): TransactionPlan {
        if (utxos.isEmpty()) {
            throw IOException("No unspent UTxOs available in wallet!")
        }

        if (isClaim) {
            return planClaimRewardsTransaction(
                feePayerUtxos = utxos,
                toAccountItems = toAccountItems,
                withdrawals = withdrawals,
                metadataJson = metadataJson,
                ttl = ttl,
                witnessCount = witnessCount,
                referenceScriptBytes = referenceScriptBytes,
                protocolParameters = protocolParameters,
                fixedFeeOverride = fixedFeeOverride,
                isPreview = isPreview,
            )
        }

        val tokenFees = computePerLineTokenFees(toAccountItems, protocolParameters)

        if (selectionMode == SelectionMode.DEFRAGMENT) {
            return planDefragmentTransaction(
                utxos = utxos,
                changeAddress = changeAddress,
                recipientItems = toAccountItems,
                perLineTokenFees = tokenFees,
                deposit = BigInteger.ZERO,
                depositRefund = BigInteger.ZERO,
                withdrawals = emptyList(),
                metadataJson = metadataJson,
                ttl = ttl,
                witnessCount = witnessCount,
                extraPayloadBytes = 0,
                referenceScriptBytes = referenceScriptBytes,
                protocolParameters = protocolParameters,
                fixedFeeOverride = fixedFeeOverride,
                isPreview = isPreview,
            )
        }

        val maxTxSize = resolveMaxTxSize(protocolParameters)
        val minPureAda = calculateMinAda(changeAddress, emptyList(), protocolParameters).max(MIN_PURE_ADA_FLOOR)

        val requestedAssets = sortedMapOf<String, BigInteger>()
        toAccountItems.forEach { item ->
            if (item.currency != "ada" && item.amount > BigInteger.ZERO) {
                val normalized = item.currency.trimEnd('.')
                requestedAssets[normalized] = (requestedAssets[normalized] ?: BigInteger.ZERO) + item.amount
            }
        }

        val explicitAdaRequested =
            toAccountItems
                .filter { it.currency == "ada" }
                .sumByBigInteger { item ->
                    if (isPreview) item.amount.max(MIN_PURE_ADA_FLOOR) else item.amount
                }
        val recipientTokenTopUpAda = tokenFees.sumByBigInteger { it }
        val totalRecipientAda = explicitAdaRequested + recipientTokenTopUpAda

        val uniqueDestinations = toAccountItems.map { it.destinationKey }.distinct().size.coerceAtLeast(1)
        val maxInputs = computeMaxSafeInputCount(maxTxSize, witnessCount, metadataJson?.length ?: 0, uniqueDestinations + 3)

        val isFullAdaSweep =
            toAccountItems
                .filter { it.currency == "ada" && it.isPercent }
                .sumOf { it.percent } >= 100

        // When user requests 100% ADA sweep, deterministically select all requested-asset UTxOs plus
        // largest pure-ADA UTxOs up to maxInputs so calculateTxFee and submitTransaction agree identically.
        if (isFullAdaSweep) {
            return planFullPercentStandardTransaction(
                utxos = utxos,
                changeAddress = changeAddress,
                toAccountItems = toAccountItems,
                perLineTokenFees = tokenFees,
                requestedAssets = requestedAssets,
                metadataJson = metadataJson,
                ttl = ttl,
                witnessCount = witnessCount,
                referenceScriptBytes = referenceScriptBytes,
                protocolParameters = protocolParameters,
                maxInputs = maxInputs,
                maxTxSize = maxTxSize,
                minPureAda = minPureAda,
                fixedFeeOverride = fixedFeeOverride,
                isPreview = isPreview,
            )
        }

        var estimatedFee = fixedFeeOverride ?: INITIAL_ESTIMATED_FEE
        var requiredTokenKeepAda = BigInteger.ZERO
        var selectedUtxos: List<Utxo>
        var usedFallback = false

        for (attempt in 0 until 6) {
            val targetAda = totalRecipientAda + estimatedFee + requiredTokenKeepAda
            val selectionResult =
                runCatching {
                    selectStandardUtxos(
                        utxos = utxos,
                        address = changeAddress,
                        requestedLovelace = targetAda,
                        requestedAssets = requestedAssets,
                        maxInputs = maxInputs,
                    )
                }.getOrElse { ex ->
                    if (!isPreview) throw ex
                    // During live UI typing preview, select largest spendable subset so exact fee & tokenLocked can be returned
                    selectPreviewFallbackUtxos(utxos, requestedAssets, maxInputs) to true
                }

            selectedUtxos = selectionResult.first
            usedFallback = usedFallback || selectionResult.second

            val remainingAssets = subtractAssetMaps(selectedUtxos.toNativeAssetMap(), requestedAssets)
            val tokenChangeOutputs =
                buildGroupedTokenOutputs(
                    address = changeAddress,
                    assetMap = remainingAssets,
                    protocolParameters = protocolParameters,
                )
            val tokenKeepFee = tokenChangeOutputs.sumByBigInteger { it.lovelace }
            val selectedLovelace = selectedUtxos.sumByBigInteger { it.lovelace }

            val recipientOutputs = buildRecipientOutputs(toAccountItems, tokenFees, isPreview = isPreview)
            val provisionalResidualAda = selectedLovelace - totalRecipientAda - estimatedFee - tokenKeepFee

            val provisionalOutputs =
                recipientOutputs +
                    buildChangeOutputsWithResidualAda(
                        changeAddress = changeAddress,
                        tokenChangeOutputs = tokenChangeOutputs,
                        residualPureAda = if (isPreview) provisionalResidualAda.max(BigInteger.ZERO) else provisionalResidualAda,
                        minPureAda = minPureAda,
                        splitCleanAda = false,
                    )

            val (computedFee, computedBytes) =
                calculateFeeAndSize(
                    selectedUtxos = selectedUtxos,
                    outputs = provisionalOutputs,
                    currentFeeEstimate = estimatedFee,
                    protocolParameters = protocolParameters,
                    ttl = ttl,
                    metadataJson = metadataJson,
                    witnessCount = witnessCount,
                    referenceScriptBytes = referenceScriptBytes,
                )
            val effectiveFee = fixedFeeOverride?.max(computedFee) ?: computedFee
            val finalResidualAda = selectedLovelace - totalRecipientAda - effectiveFee - tokenKeepFee

            if (isPreview && finalResidualAda < BigInteger.ZERO && attempt >= 1) {
                validateTransactionSize(computedBytes, maxTxSize)
                val tokenLocked = computeStandardTokenLocked(utxos, selectedUtxos, maxInputs)
                return TransactionPlan(
                    selectedUtxos = selectedUtxos,
                    outputs = provisionalOutputs,
                    fee = effectiveFee,
                    tokenFees = tokenFees,
                    tokenKeepFee = tokenKeepFee,
                    tokenLocked = tokenLocked,
                    estimatedTxBytes = computedBytes,
                    selectionMode = SelectionMode.STANDARD,
                    usedFallback = usedFallback,
                )
            }

            // Check if residual pure ADA is either 0, foldable into token change, or >= minPureAda
            val subMinResidualNeedsExtraInput =
                finalResidualAda > BigInteger.ZERO &&
                    finalResidualAda < minPureAda &&
                    tokenChangeOutputs.isEmpty() &&
                    selectedUtxos.size < utxos.count { it.nativeAssets.isEmpty() }

            if (finalResidualAda >= BigInteger.ZERO && !subMinResidualNeedsExtraInput && effectiveFee <= estimatedFee) {
                var absorbedDustFee = BigInteger.ZERO
                val changeOutputs =
                    if (finalResidualAda > BigInteger.ZERO && finalResidualAda < minPureAda && tokenChangeOutputs.isEmpty()) {
                        // Orphan sub-min-ADA dust when no more pure-ADA UTxOs exist: fold into fee to avoid OutputTooSmallUTxO
                        absorbedDustFee = finalResidualAda
                        emptyList()
                    } else {
                        buildChangeOutputsWithResidualAda(
                            changeAddress = changeAddress,
                            tokenChangeOutputs = tokenChangeOutputs,
                            residualPureAda = finalResidualAda,
                            minPureAda = minPureAda,
                            splitCleanAda = false,
                        )
                    }
                val finalOutputs = recipientOutputs + changeOutputs
                val candidateFee = effectiveFee + absorbedDustFee
                val (verifiedFeeRaw, verifiedBytes) =
                    calculateFeeAndSize(
                        selectedUtxos = selectedUtxos,
                        outputs = finalOutputs,
                        currentFeeEstimate = candidateFee,
                        protocolParameters = protocolParameters,
                        ttl = ttl,
                        metadataJson = metadataJson,
                        witnessCount = witnessCount,
                        referenceScriptBytes = referenceScriptBytes,
                    )
                val finalFee = candidateFee.max(verifiedFeeRaw)
                val adjustedOutputs =
                    if (finalFee > candidateFee) {
                        adjustOutputsForFeeDelta(
                            outputs = finalOutputs,
                            changeAddress = changeAddress,
                            feeDelta = finalFee - candidateFee,
                            minPureAda = minPureAda,
                        )
                    } else {
                        finalOutputs
                    }
                val (_, finalBytes) =
                    calculateFeeAndSize(
                        selectedUtxos = selectedUtxos,
                        outputs = adjustedOutputs,
                        currentFeeEstimate = finalFee,
                        protocolParameters = protocolParameters,
                        ttl = ttl,
                        metadataJson = metadataJson,
                        witnessCount = witnessCount,
                        referenceScriptBytes = referenceScriptBytes,
                    )
                validateTransactionSize(finalBytes, maxTxSize)
                val tokenLocked = computeStandardTokenLocked(utxos, selectedUtxos, maxInputs)
                return TransactionPlan(
                    selectedUtxos = selectedUtxos,
                    outputs = adjustedOutputs,
                    fee = finalFee,
                    tokenFees = tokenFees,
                    tokenKeepFee = tokenKeepFee,
                    tokenLocked = tokenLocked,
                    estimatedTxBytes = finalBytes,
                    selectionMode = SelectionMode.STANDARD,
                    usedFallback = usedFallback,
                )
            }

            estimatedFee = effectiveFee.max(estimatedFee)
            requiredTokenKeepAda =
                if (subMinResidualNeedsExtraInput) {
                    tokenKeepFee + minPureAda
                } else {
                    tokenKeepFee
                }

            if (attempt >= 2 && finalResidualAda < BigInteger.ZERO) {
                val netFallback =
                    selectByNetUnlockedLovelace(
                        utxos = utxos,
                        changeAddress = changeAddress,
                        requiredNetLovelace = totalRecipientAda + estimatedFee,
                        requestedAssets = requestedAssets,
                        maxInputs = maxInputs,
                        protocolParameters = protocolParameters,
                    )
                if (netFallback.isNotEmpty()) {
                    return finalizeDirectSelectionWalletPlan(
                        allUtxos = utxos,
                        selectedUtxos = netFallback,
                        changeAddress = changeAddress,
                        toAccountItems = toAccountItems,
                        tokenFees = tokenFees,
                        requestedAssets = requestedAssets,
                        totalRecipientAda = totalRecipientAda,
                        metadataJson = metadataJson,
                        ttl = ttl,
                        witnessCount = witnessCount,
                        referenceScriptBytes = referenceScriptBytes,
                        protocolParameters = protocolParameters,
                        maxInputs = maxInputs,
                        maxTxSize = maxTxSize,
                        minPureAda = minPureAda,
                        fixedFeeOverride = fixedFeeOverride,
                        selectionMode = SelectionMode.STANDARD,
                        usedFallback = true,
                    )
                }
            }
        }

        throw IOException("Insufficient unlocked balance to cover requested amount and transaction fees!")
    }

    /**
     * Explicit Defragmentation / Clean UTxO selection mode (inspired by unfrack.it):
     * - Sweeps small dust pure-ADA UTxOs and fragmented multi-asset UTxOs up to Cardano's 16KB protocol limit.
     * - Groups native tokens by policy ID into clean outputs (`<= 20` tokens per output) funded with exact min-ADA.
     * - Consolidates spendable pure ADA into 1–2 clean, well-funded outputs.
     */
    private fun planDefragmentTransaction(
        utxos: List<Utxo>,
        changeAddress: String,
        recipientItems: List<WalletAccountLineItem>,
        perLineTokenFees: List<BigInteger> = emptyList(),
        deposit: BigInteger = BigInteger.ZERO,
        depositRefund: BigInteger = BigInteger.ZERO,
        withdrawals: List<WithdrawalItem> = emptyList(),
        metadataJson: String? = null,
        ttl: Long = 100_000_000L,
        witnessCount: Int = 1,
        extraPayloadBytes: Int = 0,
        referenceScriptBytes: Long = 0L,
        protocolParameters: ProtocolParameters,
        fixedFeeOverride: BigInteger? = null,
        isPreview: Boolean = false,
    ): TransactionPlan {
        val maxTxSize = resolveMaxTxSize(protocolParameters)
        val minPureAda = calculateMinAda(changeAddress, emptyList(), protocolParameters).max(MIN_PURE_ADA_FLOOR)

        val requestedAssets = sortedMapOf<String, BigInteger>()
        recipientItems.forEach { item ->
            if (item.currency != "ada" && item.amount > BigInteger.ZERO) {
                val normalized = item.currency.trimEnd('.')
                requestedAssets[normalized] = (requestedAssets[normalized] ?: BigInteger.ZERO) + item.amount
            }
        }

        val isSelfConsolidation =
            recipientItems.isEmpty() ||
                recipientItems.all { it.address == changeAddress }
        val isFullPercentSweep =
            recipientItems.any { it.currency == "ada" && it.isPercent && it.percent >= 100 }

        val nonSweepAdaRequested =
            if (isFullPercentSweep) {
                BigInteger.ZERO
            } else {
                recipientItems
                    .filter { it.currency == "ada" }
                    .sumByBigInteger { if (isPreview) it.amount.max(MIN_PURE_ADA_FLOOR) else it.amount }
            }
        val recipientTokenTopUpAda = perLineTokenFees.sumByBigInteger { it }
        val mandatoryExternalAda = nonSweepAdaRequested + recipientTokenTopUpAda + (deposit - depositRefund).max(BigInteger.ZERO)

        // Order UTxOs to guarantee mandatory assets/ADA are satisfied first, then pack fragmented
        // multi-asset UTxOs and smallest dust pure-ADA UTxOs ascending up to maxTxSize.
        val orderedCandidates =
            orderUtxosForDefragmentation(
                utxos = utxos,
                changeAddress = changeAddress,
                mandatoryAda = mandatoryExternalAda + BigInteger.valueOf(2_000_000L),
                requestedAssets = requestedAssets,
                protocolParameters = protocolParameters,
            )

        val withdrawalTotal = withdrawals.sumByBigInteger { it.lovelace }

        fun evaluatePrefix(count: Int): TransactionPlan? {
            val candidateUtxos = orderedCandidates.subList(0, count)
            val candidateAssetMap = candidateUtxos.toNativeAssetMap()
            for ((assetKey, reqAmt) in requestedAssets) {
                if ((candidateAssetMap[assetKey] ?: BigInteger.ZERO) < reqAmt) {
                    return null
                }
            }
            val remainingAssets = subtractAssetMaps(candidateAssetMap, requestedAssets)
            val tokenOutputs =
                buildGroupedTokenOutputs(
                    address = changeAddress,
                    assetMap = remainingAssets,
                    protocolParameters = protocolParameters,
                    defragmentByPolicy = true,
                )
            val tokenKeepAda = tokenOutputs.sumByBigInteger { it.lovelace }
            val totalInputAda = candidateUtxos.sumByBigInteger { it.lovelace } + depositRefund + withdrawalTotal

            var feeEstimate = fixedFeeOverride ?: INITIAL_ESTIMATED_FEE
            var finalOutputs: List<PlannedOutput> = emptyList()
            var finalBytes = 0

            for (iter in 0 until 4) {
                val netAvailablePureAda = totalInputAda - deposit - feeEstimate - tokenKeepAda - recipientTokenTopUpAda
                val candidateOutputs =
                    buildDefragmentOutputs(
                        changeAddress = changeAddress,
                        recipientItems = recipientItems,
                        perLineTokenFees = perLineTokenFees,
                        tokenOutputs = tokenOutputs,
                        netAvailablePureAda = netAvailablePureAda,
                        nonSweepAdaRequested = nonSweepAdaRequested,
                        minPureAda = minPureAda,
                        isSelfConsolidation = isSelfConsolidation,
                        isFullPercentSweep = isFullPercentSweep,
                        isPreview = isPreview,
                    ) ?: if (isPreview) {
                        buildRecipientOutputs(recipientItems, perLineTokenFees, isPreview = true) + tokenOutputs
                    } else {
                        return null
                    }

                val (calcFee, calcBytes) =
                    calculateFeeAndSize(
                        selectedUtxos = candidateUtxos,
                        outputs = candidateOutputs,
                        currentFeeEstimate = feeEstimate,
                        protocolParameters = protocolParameters,
                        ttl = ttl,
                        withdrawals = withdrawals,
                        metadataJson = metadataJson,
                        witnessCount = witnessCount,
                        extraPayloadBytes = extraPayloadBytes,
                        referenceScriptBytes = referenceScriptBytes,
                    )
                if (calcBytes > maxTxSize) {
                    return null
                }
                val nextFee = fixedFeeOverride?.max(calcFee) ?: calcFee
                finalOutputs = candidateOutputs
                finalBytes = calcBytes
                if (nextFee == feeEstimate) {
                    break
                }
                feeEstimate = nextFee
            }

            // Final pass with converged fee
            val convergedPureAda = totalInputAda - deposit - feeEstimate - tokenKeepAda - recipientTokenTopUpAda
            val convergedOutputs =
                buildDefragmentOutputs(
                    changeAddress = changeAddress,
                    recipientItems = recipientItems,
                    perLineTokenFees = perLineTokenFees,
                    tokenOutputs = tokenOutputs,
                    netAvailablePureAda = convergedPureAda,
                    nonSweepAdaRequested = nonSweepAdaRequested,
                    minPureAda = minPureAda,
                    isSelfConsolidation = isSelfConsolidation,
                    isFullPercentSweep = isFullPercentSweep,
                    isPreview = isPreview,
                )
            if (convergedOutputs == null && !isPreview) {
                return null
            }
            if (convergedOutputs != null) {
                finalOutputs = convergedOutputs
            }
            val (verifiedFeeRaw, verifiedBytes) =
                calculateFeeAndSize(
                    selectedUtxos = candidateUtxos,
                    outputs = finalOutputs,
                    currentFeeEstimate = feeEstimate,
                    protocolParameters = protocolParameters,
                    ttl = ttl,
                    withdrawals = withdrawals,
                    metadataJson = metadataJson,
                    witnessCount = witnessCount,
                    extraPayloadBytes = extraPayloadBytes,
                    referenceScriptBytes = referenceScriptBytes,
                )
            if (verifiedBytes > maxTxSize) {
                return null
            }
            val verifiedFee = feeEstimate.max(verifiedFeeRaw)
            if (verifiedFee > feeEstimate && !isPreview) {
                finalOutputs =
                    adjustOutputsForFeeDelta(
                        outputs = finalOutputs,
                        changeAddress = changeAddress,
                        feeDelta = verifiedFee - feeEstimate,
                        minPureAda = minPureAda,
                    )
            }
            val unspentLovelace =
                utxos.sumByBigInteger { it.lovelace } - candidateUtxos.sumByBigInteger { it.lovelace }
            return TransactionPlan(
                selectedUtxos = candidateUtxos,
                outputs = finalOutputs,
                fee = verifiedFee,
                tokenFees = perLineTokenFees,
                tokenKeepFee = tokenKeepAda,
                tokenLocked = unspentLovelace.max(BigInteger.ZERO),
                estimatedTxBytes = verifiedBytes,
                selectionMode = SelectionMode.DEFRAGMENT,
                usedFallback = false,
            )
        }

        // Fast upper bound from CBOR input byte size, then binary search for exact largest prefix <= maxTxSize
        val upperBoundCount =
            orderedCandidates.size.coerceAtMost(
                ((maxTxSize - BASE_TX_OVERHEAD_BYTES) / 30).coerceAtLeast(1)
            )

        // Check full upperBoundCount first (common case when wallet has < ~350 UTxOs: 1 evaluation!)
        evaluatePrefix(upperBoundCount)?.let { return it }

        // Find minimum feasible prefix (mandatory assets/ADA)
        var minFeasible = 1
        while (minFeasible <= upperBoundCount && evaluatePrefix(minFeasible) == null) {
            minFeasible = if (minFeasible < 8) minFeasible + 1 else (minFeasible * 2).coerceAtMost(upperBoundCount + 1)
        }
        if (minFeasible > upperBoundCount) {
            throw IOException("Unable to construct a valid defragmentation transaction within ${maxTxSize}B protocol limit!")
        }
        // Step back if exponential jump skipped earlier feasible prefix
        if (minFeasible > 8) {
            var loMin = minFeasible / 2
            var hiMin = minFeasible
            while (loMin < hiMin) {
                val mid = (loMin + hiMin) ushr 1
                if (evaluatePrefix(mid) != null) {
                    hiMin = mid
                } else {
                    loMin = mid + 1
                }
            }
            minFeasible = loMin
        }

        var low = minFeasible
        var high = upperBoundCount - 1
        var bestPlan: TransactionPlan =
            evaluatePrefix(minFeasible)
                ?: throw IOException("Insufficient funds or transaction exceeds ${maxTxSize}B limit!")

        while (low <= high) {
            val mid = (low + high) ushr 1
            val plan = evaluatePrefix(mid)
            if (plan != null) {
                bestPlan = plan
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        validateTransactionSize(bestPlan.estimatedTxBytes, maxTxSize)
        return bestPlan
    }

    /**
     * Validates that [txSizeBytes] does not exceed Cardano's protocol `maxTxSize` (typically 16,384 bytes).
     */
    fun validateTransactionSize(
        txSizeBytes: Int,
        maxTxSize: Int = DEFAULT_MAX_TX_SIZE,
    ) {
        if (txSizeBytes > maxTxSize) {
            throw IOException(
                "Transaction size ($txSizeBytes bytes) exceeds Cardano protocol maxTxSize limit ($maxTxSize bytes)!"
            )
        }
    }

    /**
     * Parses a cardano-cli text envelope JSON (`{"cborHex": "..."}`) and validates that its decoded
     * CBOR byte length does not exceed `protocolParameters.maxTxSize` prior to node dispatch.
     */
    fun validateSignedEnvelopeSize(
        txSignedJson: String,
        protocolParameters: ProtocolParameters,
    ): Int {
        val maxTxSize = resolveMaxTxSize(protocolParameters)
        val cborHex =
            runCatching { txEnvelopeAdapter.fromJson(txSignedJson)?.cborHex }
                .getOrNull()
                ?.trim()
                .orEmpty()
        if (cborHex.isEmpty()) {
            return 0
        }
        val actualBytes = cborHex.length / 2
        validateTransactionSize(actualBytes, maxTxSize)
        return actualBytes
    }

    /**
     * Estimates extra CBOR payload bytes contributed by CLI text-envelope `.cert` or `.vote` JSON contents
     * (or falls back to conservative byte estimates per certificate/vote when mocked in unit tests).
     */
    fun estimateEnvelopePayloadBytes(
        envelopeJsons: List<String>,
        fallbackBytesPerItem: Int = 180,
    ): Int {
        if (envelopeJsons.isEmpty()) return 0
        var total = 8 // CBOR map key + array/map header overhead
        envelopeJsons.forEach { json ->
            val hexLen =
                runCatching { txEnvelopeAdapter.fromJson(json)?.cborHex?.trim()?.length }
                    .getOrNull()
                    ?: 0
            total += if (hexLen > 0) (hexLen / 2) + 4 else fallbackBytesPerItem
        }
        return total
    }

    private fun planClaimRewardsTransaction(
        feePayerUtxos: List<Utxo>,
        toAccountItems: List<WalletAccountLineItem>,
        withdrawals: List<WithdrawalItem>,
        metadataJson: String?,
        ttl: Long,
        witnessCount: Int,
        referenceScriptBytes: Long,
        protocolParameters: ProtocolParameters,
        fixedFeeOverride: BigInteger?,
        isPreview: Boolean,
    ): TransactionPlan {
        val maxTxSize = resolveMaxTxSize(protocolParameters)
        val feePayerAddress =
            toAccountItems.firstOrNull { it.isFeePayerClaimAccount }?.address
                ?: toAccountItems.firstOrNull()?.address
                ?: MinAdaCalculator.DUMMY_ADDRESS
        val minPureAda = calculateMinAda(feePayerAddress, emptyList(), protocolParameters).max(MIN_PURE_ADA_FLOOR)
        val maxInputs =
            computeMaxSafeInputCount(
                maxTxSize = maxTxSize,
                witnessCount = witnessCount,
                extraBytes = withdrawals.size * 40 + (metadataJson?.length ?: 0),
                outputCount = toAccountItems.map { it.destinationKey }.distinct().size + 1,
            )

        var estimatedFee = fixedFeeOverride ?: INITIAL_ESTIMATED_FEE
        var selectedUtxos: List<Utxo> = emptyList()
        var usedFallback = false

        for (attempt in 0 until 5) {
            val selection =
                selectStandardUtxos(
                    utxos = feePayerUtxos,
                    address = feePayerAddress,
                    requestedLovelace = estimatedFee + minPureAda,
                    requestedAssets = emptyMap(),
                    maxInputs = maxInputs,
                )
            selectedUtxos = selection.first
            usedFallback = usedFallback || selection.second

            val inputLovelace = selectedUtxos.sumByBigInteger { it.lovelace }
            val withdrawalLovelace = withdrawals.sumByBigInteger { it.lovelace }
            val inputAssets = selectedUtxos.toNativeAssetMap()

            val outputs =
                buildClaimOutputs(
                    toAccountItems = toAccountItems,
                    feePayerInputLovelace = inputLovelace,
                    feePayerInputAssets = inputAssets,
                    totalWithdrawalLovelace = withdrawalLovelace,
                    fee = estimatedFee,
                    isPreview = isPreview,
                )

            val (calcFee, calcBytes) =
                calculateFeeAndSize(
                    selectedUtxos = selectedUtxos,
                    outputs = outputs,
                    currentFeeEstimate = estimatedFee,
                    protocolParameters = protocolParameters,
                    ttl = ttl,
                    withdrawals = withdrawals,
                    metadataJson = metadataJson,
                    witnessCount = witnessCount,
                    referenceScriptBytes = referenceScriptBytes,
                )
            val effectiveFee = fixedFeeOverride?.max(calcFee) ?: calcFee
            if (effectiveFee <= estimatedFee && inputLovelace >= effectiveFee + MIN_PURE_ADA_FLOOR) {
                val finalOutputs =
                    buildClaimOutputs(
                        toAccountItems = toAccountItems,
                        feePayerInputLovelace = inputLovelace,
                        feePayerInputAssets = inputAssets,
                        totalWithdrawalLovelace = withdrawalLovelace,
                        fee = effectiveFee,
                        isPreview = isPreview,
                    )
                val (_, finalBytes) =
                    calculateFeeAndSize(
                        selectedUtxos = selectedUtxos,
                        outputs = finalOutputs,
                        currentFeeEstimate = effectiveFee,
                        protocolParameters = protocolParameters,
                        ttl = ttl,
                        withdrawals = withdrawals,
                        metadataJson = metadataJson,
                        witnessCount = witnessCount,
                        referenceScriptBytes = referenceScriptBytes,
                    )
                validateTransactionSize(finalBytes, maxTxSize)
                return TransactionPlan(
                    selectedUtxos = selectedUtxos,
                    outputs = finalOutputs,
                    fee = effectiveFee,
                    tokenFees = toAccountItems.map { BigInteger.ZERO },
                    tokenKeepFee = BigInteger.ZERO,
                    tokenLocked = BigInteger.ZERO,
                    estimatedTxBytes = finalBytes,
                    selectionMode = SelectionMode.STANDARD,
                    usedFallback = usedFallback,
                )
            }
            estimatedFee = effectiveFee.max(estimatedFee)
        }

        throw IOException("Fee payer account does not have enough unlocked ADA to claim staking rewards!")
    }

    private fun buildClaimOutputs(
        toAccountItems: List<WalletAccountLineItem>,
        feePayerInputLovelace: BigInteger,
        feePayerInputAssets: Map<String, BigInteger>,
        totalWithdrawalLovelace: BigInteger,
        fee: BigInteger,
        isPreview: Boolean,
    ): List<PlannedOutput> {
        val grouped = toAccountItems.groupBy { it.destinationKey }
        val rawAdaSum =
            toAccountItems
                .filter { it.currency == "ada" }
                .sumByBigInteger { if (isPreview) it.amount.max(MIN_PURE_ADA_FLOOR) else it.amount }
        val feeNotYetDeductedFromRewards = !isPreview && rawAdaSum > totalWithdrawalLovelace - fee

        return grouped.map { (_, items) ->
            val first = items.first()
            val isFeePayerDest = items.any { it.isFeePayerClaimAccount }
            var adaAmount =
                items
                    .filter { it.currency == "ada" }
                    .sumByBigInteger { if (isPreview) it.amount.max(MIN_PURE_ADA_FLOOR) else it.amount }

            if (isFeePayerDest) {
                adaAmount +=
                    if (isPreview || !feeNotYetDeductedFromRewards) {
                        feePayerInputLovelace
                    } else {
                        // Percent-based claim where client sent reward share before subtracting fee
                        feePayerInputLovelace
                    }
                if (feeNotYetDeductedFromRewards) {
                    adaAmount = (adaAmount - fee).max(MIN_PURE_ADA_FLOOR)
                }
            }

            val assets =
                if (isFeePayerDest) {
                    feePayerInputAssets.map { (currency, amt) -> currencyToNativeAsset(currency, amt) }
                } else {
                    emptyList()
                }

            PlannedOutput(
                address = first.address,
                lovelace = adaAmount.max(MIN_PURE_ADA_FLOOR),
                nativeAssets = assets,
            )
        }
    }

    private fun planFullPercentStandardTransaction(
        utxos: List<Utxo>,
        changeAddress: String,
        toAccountItems: List<WalletAccountLineItem>,
        perLineTokenFees: List<BigInteger>,
        requestedAssets: Map<String, BigInteger>,
        metadataJson: String?,
        ttl: Long,
        witnessCount: Int,
        referenceScriptBytes: Long,
        protocolParameters: ProtocolParameters,
        maxInputs: Int,
        maxTxSize: Int,
        minPureAda: BigInteger,
        fixedFeeOverride: BigInteger?,
        isPreview: Boolean,
    ): TransactionPlan {
        val selected = mutableListOf<Utxo>()
        val remainingAssetReq = requestedAssets.toMutableMap()

        // 1. Select UTxOs required for any explicit native asset transfers
        if (remainingAssetReq.isNotEmpty()) {
            utxos
                .filter { it.nativeAssets.isNotEmpty() }
                .sortedByDescending { utxo ->
                    utxo.nativeAssets.sumByBigInteger { asset ->
                        val cur = "${asset.policy}.${asset.name}".trimEnd('.')
                        if ((remainingAssetReq[cur] ?: BigInteger.ZERO) > BigInteger.ZERO) BigInteger.ONE else BigInteger.ZERO
                    }
                }.forEach { utxo ->
                    if (remainingAssetReq.values.any { it > BigInteger.ZERO } && selected.size < maxInputs) {
                        val contributes =
                            utxo.nativeAssets.any { asset ->
                                val cur = "${asset.policy}.${asset.name}".trimEnd('.')
                                (remainingAssetReq[cur] ?: BigInteger.ZERO) > BigInteger.ZERO
                            }
                        if (contributes) {
                            selected.add(utxo)
                            utxo.nativeAssets.forEach { asset ->
                                val cur = "${asset.policy}.${asset.name}".trimEnd('.')
                                val prev = remainingAssetReq[cur]
                                if (prev != null) {
                                    remainingAssetReq[cur] = (prev - asset.amount).max(BigInteger.ZERO)
                                }
                            }
                        }
                    }
                }
        }

        // 2. Select pure-ADA UTxOs (largest first to maximize spendable balance within 16KB cap)
        utxos
            .filter { it.nativeAssets.isEmpty() && it !in selected }
            .sortedByDescending { it.lovelace }
            .forEach { utxo ->
                if (selected.size < maxInputs) {
                    selected.add(utxo)
                }
            }

        // 3. If no pure-ADA UTxOs existed, fall back to multi-asset UTxOs with surplus unlocked ADA
        if (selected.isEmpty()) {
            utxos
                .sortedByDescending { it.lovelace }
                .take(maxInputs)
                .forEach { selected.add(it) }
        }

        val leftoverAssets = subtractAssetMaps(selected.toNativeAssetMap(), requestedAssets)
        val tokenChangeOutputs =
            buildGroupedTokenOutputs(
                address = changeAddress,
                assetMap = leftoverAssets,
                protocolParameters = protocolParameters,
            )
        val tokenKeepFee = tokenChangeOutputs.sumByBigInteger { it.lovelace }
        val selectedLovelace = selected.sumByBigInteger { it.lovelace }
        val tokenLocked = utxos.sumByBigInteger { it.lovelace } - selectedLovelace
        val recipientTokenTopUpAda = perLineTokenFees.sumByBigInteger { it }

        var feeEstimate = fixedFeeOverride ?: INITIAL_ESTIMATED_FEE
        var outputs: List<PlannedOutput> = emptyList()
        var estimatedBytes = 0

        for (iter in 0 until 5) {
            val distributableAda = (selectedLovelace - feeEstimate - tokenKeepFee - recipientTokenTopUpAda).max(BigInteger.ZERO)
            val adjustedItems = rescaleFullPercentItems(toAccountItems, distributableAda, isPreview)
            outputs = buildRecipientOutputs(adjustedItems, perLineTokenFees, isPreview = isPreview) + tokenChangeOutputs
            val (calcFee, calcBytes) =
                calculateFeeAndSize(
                    selectedUtxos = selected,
                    outputs = outputs,
                    currentFeeEstimate = feeEstimate,
                    protocolParameters = protocolParameters,
                    ttl = ttl,
                    metadataJson = metadataJson,
                    witnessCount = witnessCount,
                    referenceScriptBytes = referenceScriptBytes,
                )
            val nextFee = fixedFeeOverride?.max(calcFee) ?: calcFee
            estimatedBytes = calcBytes
            if (nextFee == feeEstimate) break
            feeEstimate = nextFee
        }

        val finalDistributableAda = selectedLovelace - feeEstimate - tokenKeepFee - recipientTokenTopUpAda
        if (!isPreview && finalDistributableAda < minPureAda) {
            throw IOException("Not enough unlocked ADA to complete 100% transfer after fees!")
        }
        val finalAdjustedItems = rescaleFullPercentItems(toAccountItems, finalDistributableAda.max(BigInteger.ZERO), isPreview)
        val finalOutputs = buildRecipientOutputs(finalAdjustedItems, perLineTokenFees, isPreview = isPreview) + tokenChangeOutputs
        val (_, finalBytes) =
            calculateFeeAndSize(
                selectedUtxos = selected,
                outputs = finalOutputs,
                currentFeeEstimate = feeEstimate,
                protocolParameters = protocolParameters,
                ttl = ttl,
                metadataJson = metadataJson,
                witnessCount = witnessCount,
                referenceScriptBytes = referenceScriptBytes,
            )
        validateTransactionSize(finalBytes, maxTxSize)

        return TransactionPlan(
            selectedUtxos = selected,
            outputs = finalOutputs,
            fee = feeEstimate,
            tokenFees = perLineTokenFees,
            tokenKeepFee = tokenKeepFee,
            tokenLocked = tokenLocked.max(BigInteger.ZERO),
            estimatedTxBytes = finalBytes,
            selectionMode = SelectionMode.STANDARD,
            usedFallback = false,
        )
    }

    private fun rescaleFullPercentItems(
        items: List<WalletAccountLineItem>,
        distributableAda: BigInteger,
        isPreview: Boolean,
    ): List<WalletAccountLineItem> {
        val fixedAda =
            items
                .filter { it.currency == "ada" && !it.isPercent }
                .sumByBigInteger { it.amount }
        var remainingPool = (distributableAda - fixedAda).max(if (isPreview) MIN_PURE_ADA_FLOOR else BigInteger.ZERO)
        var spentPercent = 0
        return items.map { item ->
            if (item.currency == "ada" && item.isPercent && item.percent > 0) {
                val denominator = (100 - spentPercent).coerceAtLeast(1)
                val share =
                    if (spentPercent + item.percent >= 100) {
                        remainingPool
                    } else {
                        BigDecimal(remainingPool)
                            .multiply(BigDecimal.valueOf(item.percent.toLong()))
                            .divide(BigDecimal.valueOf(denominator.toLong()), 0, java.math.RoundingMode.HALF_UP)
                            .toBigInteger()
                    }
                remainingPool = (remainingPool - share).max(BigInteger.ZERO)
                spentPercent += item.percent
                item.copy(amount = share.max(if (isPreview) MIN_PURE_ADA_FLOOR else BigInteger.ZERO))
            } else {
                item
            }
        }
    }

    private fun finalizeDirectSelectionWalletPlan(
        allUtxos: List<Utxo>,
        selectedUtxos: List<Utxo>,
        changeAddress: String,
        toAccountItems: List<WalletAccountLineItem>,
        tokenFees: List<BigInteger>,
        requestedAssets: Map<String, BigInteger>,
        totalRecipientAda: BigInteger,
        metadataJson: String?,
        ttl: Long,
        witnessCount: Int,
        referenceScriptBytes: Long,
        protocolParameters: ProtocolParameters,
        maxInputs: Int,
        maxTxSize: Int,
        minPureAda: BigInteger,
        fixedFeeOverride: BigInteger?,
        selectionMode: SelectionMode,
        usedFallback: Boolean,
    ): TransactionPlan {
        val remainingAssets = subtractAssetMaps(selectedUtxos.toNativeAssetMap(), requestedAssets)
        val tokenChangeOutputs =
            buildGroupedTokenOutputs(
                address = changeAddress,
                assetMap = remainingAssets,
                protocolParameters = protocolParameters,
            )
        val tokenKeepFee = tokenChangeOutputs.sumByBigInteger { it.lovelace }
        val selectedLovelace = selectedUtxos.sumByBigInteger { it.lovelace }
        val recipientOutputs = buildRecipientOutputs(toAccountItems, tokenFees, isPreview = false)

        var feeEstimate = fixedFeeOverride ?: INITIAL_ESTIMATED_FEE
        for (iter in 0 until 4) {
            val residual = (selectedLovelace - totalRecipientAda - feeEstimate - tokenKeepFee).max(BigInteger.ZERO)
            val candidateOutputs =
                recipientOutputs +
                    buildChangeOutputsWithResidualAda(
                        changeAddress = changeAddress,
                        tokenChangeOutputs = tokenChangeOutputs,
                        residualPureAda = residual,
                        minPureAda = minPureAda,
                        splitCleanAda = false,
                    )
            val (calcFee, _) =
                calculateFeeAndSize(
                    selectedUtxos = selectedUtxos,
                    outputs = candidateOutputs,
                    currentFeeEstimate = feeEstimate,
                    protocolParameters = protocolParameters,
                    ttl = ttl,
                    metadataJson = metadataJson,
                    witnessCount = witnessCount,
                    referenceScriptBytes = referenceScriptBytes,
                )
            val nextFee = fixedFeeOverride?.max(calcFee) ?: calcFee
            if (nextFee == feeEstimate) break
            feeEstimate = nextFee
        }

        val finalResidual = selectedLovelace - totalRecipientAda - feeEstimate - tokenKeepFee
        if (finalResidual < BigInteger.ZERO) {
            throw IOException("Insufficient unlocked balance after fee calculation!")
        }
        var absorbedDust = BigInteger.ZERO
        val changeOutputs =
            if (finalResidual > BigInteger.ZERO && finalResidual < minPureAda && tokenChangeOutputs.isEmpty()) {
                absorbedDust = finalResidual
                emptyList()
            } else {
                buildChangeOutputsWithResidualAda(
                    changeAddress = changeAddress,
                    tokenChangeOutputs = tokenChangeOutputs,
                    residualPureAda = finalResidual,
                    minPureAda = minPureAda,
                    splitCleanAda = false,
                )
            }
        val finalOutputs = recipientOutputs + changeOutputs
        val finalFee = feeEstimate + absorbedDust
        val (_, finalBytes) =
            calculateFeeAndSize(
                selectedUtxos = selectedUtxos,
                outputs = finalOutputs,
                currentFeeEstimate = finalFee,
                protocolParameters = protocolParameters,
                ttl = ttl,
                metadataJson = metadataJson,
                witnessCount = witnessCount,
                referenceScriptBytes = referenceScriptBytes,
            )
        validateTransactionSize(finalBytes, maxTxSize)

        return TransactionPlan(
            selectedUtxos = selectedUtxos,
            outputs = finalOutputs,
            fee = finalFee,
            tokenFees = tokenFees,
            tokenKeepFee = tokenKeepFee,
            tokenLocked = computeStandardTokenLocked(allUtxos, selectedUtxos, maxInputs),
            estimatedTxBytes = finalBytes,
            selectionMode = selectionMode,
            usedFallback = usedFallback,
        )
    }

    /**
     * Runs Bloxbean's [RandomImproveUtxoSelectionStrategy] as the default standard coin selector,
     * preferring pure-ADA UTxOs when no native assets are requested so multi-asset UTxOs aren't
     * needlessly disturbed, and automatically falling back to [LargestFirstUtxoSelectionStrategy]
     * whenever primary selection fails or hits input limits.
     */
    private fun selectStandardUtxos(
        utxos: List<Utxo>,
        address: String,
        requestedLovelace: BigInteger,
        requestedAssets: Map<String, BigInteger>,
        maxInputs: Int,
    ): Pair<List<Utxo>, Boolean> {
        val safeAddr = toSdkSafeAddress(address)
        val requestedAmounts = buildRequestedAmounts(requestedLovelace, requestedAssets)

        // Prefer pure-ADA UTxOs first when only ADA is needed and pure-ADA UTxOs have enough balance;
        // otherwise evaluate the full wallet UTxO pool.
        val pureAdaPool = utxos.filter { it.nativeAssets.isEmpty() }
        val candidatePools =
            if (requestedAssets.isEmpty() && pureAdaPool.sumByBigInteger { it.lovelace } >= requestedLovelace) {
                listOf(pureAdaPool, utxos)
            } else {
                listOf(utxos)
            }

        var lastError: Throwable? = null
        for ((poolIndex, pool) in candidatePools.withIndex()) {
            val (supplier, lookupMap) = buildSupplierAndMap(safeAddr, pool)
            val primaryStrategy =
                randomImproveStrategyFactory?.invoke(supplier)
                    ?: RandomImproveUtxoSelectionStrategy(supplier, false)

            val primaryResult =
                runCatching {
                    primaryStrategy.select(safeAddr, requestedAmounts, emptySet(), maxInputs)
                }
            if (primaryResult.isSuccess && !primaryResult.getOrNull().isNullOrEmpty()) {
                val mapped = primaryResult.getOrThrow().mapNotNull { lookupMap[it.txHash to it.outputIndex] }
                if (mapped.isNotEmpty() && coversRequestedAmounts(mapped, requestedLovelace, requestedAssets)) {
                    return mapped to (poolIndex > 0)
                }
            } else {
                lastError = primaryResult.exceptionOrNull()
            }

            // Explicit automatic fallback to LargestFirstUtxoSelectionStrategy
            log.debug { "Primary Random-Improve selection did not satisfy target (${lastError?.message}); applying Largest-First fallback." }
            val fallbackStrategy =
                largestFirstStrategyFactory?.invoke(supplier)
                    ?: runCatching { primaryStrategy.fallback() }.getOrNull()
                    ?: LargestFirstUtxoSelectionStrategy(supplier, false)

            val fallbackResult =
                runCatching {
                    fallbackStrategy.select(safeAddr, requestedAmounts, emptySet(), maxInputs)
                }
            if (fallbackResult.isSuccess && !fallbackResult.getOrNull().isNullOrEmpty()) {
                val mapped = fallbackResult.getOrThrow().mapNotNull { lookupMap[it.txHash to it.outputIndex] }
                if (mapped.isNotEmpty() && coversRequestedAmounts(mapped, requestedLovelace, requestedAssets)) {
                    return mapped to true
                }
            } else {
                lastError = fallbackResult.exceptionOrNull()
            }
        }

        throw IOException(
            "Unable to select sufficient UTxOs within $maxInputs inputs: ${lastError?.message ?: "insufficient balance"}",
            lastError,
        )
    }

    private fun selectByNetUnlockedLovelace(
        utxos: List<Utxo>,
        changeAddress: String,
        requiredNetLovelace: BigInteger,
        requestedAssets: Map<String, BigInteger>,
        maxInputs: Int,
        protocolParameters: ProtocolParameters,
    ): List<Utxo> {
        val selected = mutableListOf<Utxo>()
        val remainingAssets = requestedAssets.toMutableMap()

        // First satisfy requested native assets
        if (remainingAssets.isNotEmpty()) {
            utxos
                .filter { it.nativeAssets.isNotEmpty() }
                .sortedByDescending { utxo ->
                    utxo.nativeAssets.sumByBigInteger { asset ->
                        val cur = "${asset.policy}.${asset.name}".trimEnd('.')
                        if ((remainingAssets[cur] ?: BigInteger.ZERO) > BigInteger.ZERO) asset.amount else BigInteger.ZERO
                    }
                }.forEach { utxo ->
                    if (remainingAssets.values.any { it > BigInteger.ZERO } && selected.size < maxInputs) {
                        val matches =
                            utxo.nativeAssets.any { asset ->
                                val cur = "${asset.policy}.${asset.name}".trimEnd('.')
                                (remainingAssets[cur] ?: BigInteger.ZERO) > BigInteger.ZERO
                            }
                        if (matches) {
                            selected.add(utxo)
                            utxo.nativeAssets.forEach { asset ->
                                val cur = "${asset.policy}.${asset.name}".trimEnd('.')
                                remainingAssets[cur]?.let { rem ->
                                    remainingAssets[cur] = (rem - asset.amount).max(BigInteger.ZERO)
                                }
                            }
                        }
                    }
                }
        }
        if (remainingAssets.values.any { it > BigInteger.ZERO }) {
            return emptyList()
        }

        // Next sort remaining UTxOs by net unlocked lovelace contribution descending
        val remainingCandidates =
            utxos
                .filter { it !in selected }
                .sortedByDescending { utxo ->
                    if (utxo.nativeAssets.isEmpty()) {
                        utxo.lovelace
                    } else {
                        val minLock = calculateMinAda(changeAddress, utxo.nativeAssets, protocolParameters)
                        utxo.lovelace - minLock
                    }
                }

        for (candidate in remainingCandidates) {
            val currentKeepAda =
                buildGroupedTokenOutputs(
                    address = changeAddress,
                    assetMap = subtractAssetMaps(selected.toNativeAssetMap(), requestedAssets),
                    protocolParameters = protocolParameters,
                ).sumByBigInteger { it.lovelace }
            val currentNetAda = selected.sumByBigInteger { it.lovelace } - currentKeepAda
            if (currentNetAda >= requiredNetLovelace && selected.isNotEmpty()) {
                break
            }
            if (selected.size >= maxInputs) {
                break
            }
            selected.add(candidate)
        }

        val finalKeepAda =
            buildGroupedTokenOutputs(
                address = changeAddress,
                assetMap = subtractAssetMaps(selected.toNativeAssetMap(), requestedAssets),
                protocolParameters = protocolParameters,
            ).sumByBigInteger { it.lovelace }
        val finalNetAda = selected.sumByBigInteger { it.lovelace } - finalKeepAda
        return if (finalNetAda >= requiredNetLovelace) selected else emptyList()
    }

    private fun selectPreviewFallbackUtxos(
        utxos: List<Utxo>,
        requestedAssets: Map<String, BigInteger>,
        maxInputs: Int,
    ): List<Utxo> {
        val selected = mutableListOf<Utxo>()
        if (requestedAssets.isNotEmpty()) {
            utxos
                .filter { utxo ->
                    utxo.nativeAssets.any { asset ->
                        requestedAssets.containsKey("${asset.policy}.${asset.name}".trimEnd('.'))
                    }
                }.take(maxInputs)
                .forEach { selected.add(it) }
        }
        utxos
            .filter { it.nativeAssets.isEmpty() && it !in selected }
            .sortedByDescending { it.lovelace }
            .take((maxInputs - selected.size).coerceAtLeast(0))
            .forEach { selected.add(it) }
        if (selected.isEmpty()) {
            utxos
                .sortedByDescending { it.lovelace }
                .take(maxInputs)
                .forEach { selected.add(it) }
        }
        return selected
    }

    private fun orderUtxosForDefragmentation(
        utxos: List<Utxo>,
        changeAddress: String,
        mandatoryAda: BigInteger,
        requestedAssets: Map<String, BigInteger>,
        protocolParameters: ProtocolParameters,
    ): List<Utxo> {
        val mandatory = mutableListOf<Utxo>()
        val remAssets = requestedAssets.toMutableMap()

        // 1. Ensure any explicitly requested native assets are included first
        if (remAssets.isNotEmpty()) {
            utxos
                .filter { it.nativeAssets.isNotEmpty() }
                .forEach { utxo ->
                    val needed =
                        utxo.nativeAssets.any { asset ->
                            val cur = "${asset.policy}.${asset.name}".trimEnd('.')
                            (remAssets[cur] ?: BigInteger.ZERO) > BigInteger.ZERO
                        }
                    if (needed) {
                        mandatory.add(utxo)
                        utxo.nativeAssets.forEach { asset ->
                            val cur = "${asset.policy}.${asset.name}".trimEnd('.')
                            remAssets[cur]?.let { rem -> remAssets[cur] = (rem - asset.amount).max(BigInteger.ZERO) }
                        }
                    }
                }
        }

        // 2. Ensure at least one funding UTxO covers mandatoryAda + fees so small prefixes are immediately valid
        var unlockedSoFar =
            mandatory.sumByBigInteger { utxo ->
                if (utxo.nativeAssets.isEmpty()) {
                    utxo.lovelace
                } else {
                    (utxo.lovelace - calculateMinAda(changeAddress, utxo.nativeAssets, protocolParameters)).max(BigInteger.ZERO)
                }
            }
        if (unlockedSoFar < mandatoryAda) {
            utxos
                .filter { it !in mandatory }
                .sortedByDescending { utxo ->
                    if (utxo.nativeAssets.isEmpty()) {
                        utxo.lovelace
                    } else {
                        utxo.lovelace - calculateMinAda(changeAddress, utxo.nativeAssets, protocolParameters)
                    }
                }.forEach { utxo ->
                    if (unlockedSoFar < mandatoryAda) {
                        mandatory.add(utxo)
                        unlockedSoFar +=
                            if (utxo.nativeAssets.isEmpty()) {
                                utxo.lovelace
                            } else {
                                (utxo.lovelace - calculateMinAda(changeAddress, utxo.nativeAssets, protocolParameters)).max(BigInteger.ZERO)
                            }
                    }
                }
        }

        // 3. Sort remaining UTxOs per unfrack.it principles:
        //    - Smallest pure-ADA dust UTxOs ascending by lovelace, interleaved/followed by
        //    - Fragmented multi-asset UTxOs (asset count < MIN_GROUP_SIZE or > MAX_GROUP_SIZE or duplicate policies)
        //    - Remaining pure-ADA UTxOs ascending by lovelace
        val policyFrequency = mutableMapOf<String, Int>()
        utxos.forEach { utxo ->
            utxo.nativeAssets.map { it.policy }.distinct().forEach { policy ->
                policyFrequency[policy] = (policyFrequency[policy] ?: 0) + 1
            }
        }

        val remaining = utxos.filter { it !in mandatory }
        val dustPureAda =
            remaining
                .filter { it.nativeAssets.isEmpty() && it.lovelace < DEFRAGMENT_SPLIT_THRESHOLD_LOVELACE }
                .sortedBy { it.lovelace }
        val fragmentedTokens =
            remaining
                .filter { utxo ->
                    utxo.nativeAssets.isNotEmpty() &&
                        (
                            utxo.nativeAssets.size < NATIVE_ASSET_MIN_GROUP_SIZE ||
                                utxo.nativeAssets.size > NATIVE_ASSET_MAX_GROUP_SIZE ||
                                utxo.nativeAssets.any { (policyFrequency[it.policy] ?: 0) > 1 }
                        )
                }.sortedWith(compareBy<Utxo> { it.nativeAssets.size }.thenBy { it.lovelace })
        val largerPureAda =
            remaining
                .filter { it.nativeAssets.isEmpty() && it.lovelace >= DEFRAGMENT_SPLIT_THRESHOLD_LOVELACE }
                .sortedBy { it.lovelace }
        val otherTokenUtxos =
            remaining
                .filter { it !in dustPureAda && it !in fragmentedTokens && it !in largerPureAda }
                .sortedBy { it.lovelace }

        return mandatory + dustPureAda + fragmentedTokens + largerPureAda + otherTokenUtxos
    }

    private fun buildDefragmentOutputs(
        changeAddress: String,
        recipientItems: List<WalletAccountLineItem>,
        perLineTokenFees: List<BigInteger>,
        tokenOutputs: List<PlannedOutput>,
        netAvailablePureAda: BigInteger,
        nonSweepAdaRequested: BigInteger,
        minPureAda: BigInteger,
        isSelfConsolidation: Boolean,
        isFullPercentSweep: Boolean,
        isPreview: Boolean,
    ): List<PlannedOutput>? {
        if (!isPreview && netAvailablePureAda < nonSweepAdaRequested) {
            return null
        }

        // Self-consolidation ("Defragment UTxOs" workflow): create 1–2 clean pure-ADA outputs + grouped token outputs
        if (isSelfConsolidation && (recipientItems.isEmpty() || isFullPercentSweep)) {
            if (netAvailablePureAda < BigInteger.ZERO && !isPreview) {
                return null
            }
            if (netAvailablePureAda == BigInteger.ZERO && tokenOutputs.isNotEmpty()) {
                return tokenOutputs
            }
            if (netAvailablePureAda < minPureAda && !isPreview) {
                return if (tokenOutputs.isNotEmpty() && netAvailablePureAda > BigInteger.ZERO) {
                    val boostedFirst = tokenOutputs.first().copy(lovelace = tokenOutputs.first().lovelace + netAvailablePureAda)
                    listOf(boostedFirst) + tokenOutputs.drop(1)
                } else {
                    null
                }
            }
            val safePureAda = netAvailablePureAda.max(minPureAda)
            val pureAdaOutputs =
                if (safePureAda >= DEFRAGMENT_SPLIT_THRESHOLD_LOVELACE && safePureAda >= minPureAda.multiply(BigInteger.TWO)) {
                    val half = safePureAda.divide(BigInteger.TWO)
                    val secondHalf = safePureAda - half
                    listOf(
                        PlannedOutput(address = changeAddress, lovelace = half),
                        PlannedOutput(address = changeAddress, lovelace = secondHalf),
                    )
                } else {
                    listOf(PlannedOutput(address = changeAddress, lovelace = safePureAda))
                }
            return pureAdaOutputs + tokenOutputs
        }

        // External recipient(s) with defragmentation enabled
        val adjustedItems =
            if (isFullPercentSweep) {
                rescaleFullPercentItems(recipientItems, netAvailablePureAda.max(BigInteger.ZERO), isPreview)
            } else {
                recipientItems
            }
        val recipientOutputs = buildRecipientOutputs(adjustedItems, perLineTokenFees, isPreview = isPreview)
        val explicitAda =
            adjustedItems
                .filter { it.currency == "ada" }
                .sumByBigInteger { if (isPreview) it.amount.max(MIN_PURE_ADA_FLOOR) else it.amount }
        val residualPureAda = netAvailablePureAda - explicitAda
        if (!isPreview && residualPureAda < BigInteger.ZERO) {
            return null
        }
        val changeOutputs =
            buildChangeOutputsWithResidualAda(
                changeAddress = changeAddress,
                tokenChangeOutputs = tokenOutputs,
                residualPureAda = residualPureAda.max(BigInteger.ZERO),
                minPureAda = minPureAda,
                splitCleanAda = residualPureAda >= DEFRAGMENT_SPLIT_THRESHOLD_LOVELACE,
            )
        return recipientOutputs + changeOutputs
    }

    private fun buildGroupedTokenOutputs(
        address: String,
        assetMap: Map<String, BigInteger>,
        protocolParameters: ProtocolParameters,
        defragmentByPolicy: Boolean = false,
    ): List<PlannedOutput> {
        val positiveAssets =
            assetMap
                .filterValues { it > BigInteger.ZERO }
                .map { (currency, amount) -> currencyToNativeAsset(currency, amount) }
                // Group by policy ID first so tokens under the same policy share a single 28-byte policy header in CBOR
                .sortedWith(compareBy<NativeAsset> { it.policy }.thenBy { it.name })

        if (positiveAssets.isEmpty()) {
            return emptyList()
        }

        val chunks: List<List<NativeAsset>> =
            if (!defragmentByPolicy) {
                positiveAssets.chunked(NATIVE_ASSET_MAX_GROUP_SIZE)
            } else {
                // unfrack.it style: isolate policies with >= NATIVE_ASSET_MIN_GROUP_SIZE tokens into balanced
                // policy-pure bundles (<= 20 tokens each), and roll up smaller policies together into compact bundles.
                val byPolicy = positiveAssets.groupBy { it.policy }
                val dedicatedChunks = mutableListOf<List<NativeAsset>>()
                val smallPolicyAssets = mutableListOf<NativeAsset>()

                for ((_, policyAssets) in byPolicy) {
                    if (policyAssets.size >= NATIVE_ASSET_MIN_GROUP_SIZE) {
                        val bucketCount =
                            ((policyAssets.size + NATIVE_ASSET_MAX_GROUP_SIZE - 1) / NATIVE_ASSET_MAX_GROUP_SIZE)
                                .coerceAtLeast(1)
                        val baseSize = policyAssets.size / bucketCount
                        val remainder = policyAssets.size % bucketCount
                        var offset = 0
                        for (b in 0 until bucketCount) {
                            val takeCount = baseSize + if (b < remainder) 1 else 0
                            dedicatedChunks.add(policyAssets.subList(offset, offset + takeCount))
                            offset += takeCount
                        }
                    } else {
                        smallPolicyAssets.addAll(policyAssets)
                    }
                }
                dedicatedChunks + smallPolicyAssets.chunked(NATIVE_ASSET_MAX_GROUP_SIZE)
            }

        return chunks.map { chunk ->
            val minAda = calculateMinAda(address, chunk, protocolParameters)
            PlannedOutput(
                address = address,
                lovelace = minAda,
                nativeAssets = chunk,
            )
        }
    }

    private fun buildChangeOutputsWithResidualAda(
        changeAddress: String,
        tokenChangeOutputs: List<PlannedOutput>,
        residualPureAda: BigInteger,
        minPureAda: BigInteger,
        splitCleanAda: Boolean,
    ): List<PlannedOutput> {
        if (residualPureAda <= BigInteger.ZERO) {
            return tokenChangeOutputs
        }
        if (residualPureAda < minPureAda) {
            return if (tokenChangeOutputs.isNotEmpty()) {
                val first = tokenChangeOutputs.first()
                listOf(first.copy(lovelace = first.lovelace + residualPureAda)) + tokenChangeOutputs.drop(1)
            } else {
                listOf(PlannedOutput(address = changeAddress, lovelace = residualPureAda))
            }
        }
        val pureOutputs =
            if (splitCleanAda && residualPureAda >= DEFRAGMENT_SPLIT_THRESHOLD_LOVELACE && residualPureAda >= minPureAda.multiply(BigInteger.TWO)) {
                val firstHalf = residualPureAda.divide(BigInteger.TWO)
                listOf(
                    PlannedOutput(address = changeAddress, lovelace = firstHalf),
                    PlannedOutput(address = changeAddress, lovelace = residualPureAda - firstHalf),
                )
            } else {
                listOf(PlannedOutput(address = changeAddress, lovelace = residualPureAda))
            }
        return tokenChangeOutputs + pureOutputs
    }

    private fun adjustOutputsForFeeDelta(
        outputs: List<PlannedOutput>,
        changeAddress: String,
        feeDelta: BigInteger,
        minPureAda: BigInteger,
    ): List<PlannedOutput> {
        if (feeDelta <= BigInteger.ZERO) return outputs
        val idx =
            outputs.indexOfLast {
                it.address == changeAddress &&
                    it.nativeAssets.isEmpty() &&
                    it.lovelace - feeDelta >= minPureAda
            }.takeIf { it >= 0 }
                ?: outputs.indexOfLast {
                    it.address == changeAddress && it.lovelace - feeDelta >= minPureAda
                }
        if (idx < 0) return outputs
        return outputs.mapIndexed { i, out ->
            if (i == idx) out.copy(lovelace = out.lovelace - feeDelta) else out
        }
    }

    private fun computePerLineTokenFees(
        toAccountItems: List<WalletAccountLineItem>,
        protocolParameters: ProtocolParameters,
    ): List<BigInteger> {
        val adaByDestination = mutableMapOf<String, BigInteger>()
        toAccountItems.forEach { item ->
            if (item.currency == "ada") {
                adaByDestination[item.destinationKey] =
                    (adaByDestination[item.destinationKey] ?: BigInteger.ZERO) + item.amount
            }
        }

        val prevTokenFeeByDest = mutableMapOf<String, BigInteger>()
        val accumulatedAssetsByDest = mutableMapOf<String, MutableList<NativeAsset>>()

        return toAccountItems.map { item ->
            if (item.currency == "ada") {
                BigInteger.ZERO
            } else {
                val list = accumulatedAssetsByDest.getOrPut(item.destinationKey) { mutableListOf() }
                val assetAmount = item.amount.max(BigInteger.ONE)
                list.add(currencyToNativeAsset(item.currency.trimEnd('.'), assetAmount))
                val mergedAssets = mergeAssetsList(list)
                val minRequired = calculateMinAda(item.address, mergedAssets, protocolParameters)
                val explicitAda = adaByDestination[item.destinationKey] ?: BigInteger.ZERO
                val totalTokenFeeForDest = (minRequired - explicitAda).max(BigInteger.ZERO)
                val previous = prevTokenFeeByDest[item.destinationKey] ?: BigInteger.ZERO
                prevTokenFeeByDest[item.destinationKey] = totalTokenFeeForDest
                (totalTokenFeeForDest - previous).max(BigInteger.ZERO)
            }
        }
    }

    private fun buildRecipientOutputs(
        toAccountItems: List<WalletAccountLineItem>,
        perLineTokenFees: List<BigInteger>,
        isPreview: Boolean,
    ): List<PlannedOutput> {
        if (toAccountItems.isEmpty()) return emptyList()
        val indexedItems = toAccountItems.mapIndexed { idx, item -> idx to item }
        val grouped = indexedItems.groupBy { (_, item) -> item.destinationKey }

        return grouped.map { (_, group) ->
            val address = group.first().second.address
            val totalLovelace =
                group.sumByBigInteger { (idx, item) ->
                    if (item.currency == "ada") {
                        if (isPreview) item.amount.max(MIN_PURE_ADA_FLOOR) else item.amount
                    } else {
                        perLineTokenFees.getOrElse(idx) { BigInteger.ZERO }
                    }
                }
            val nativeAssets =
                mergeAssetsList(
                    group.mapNotNull { (_, item) ->
                        if (item.currency != "ada") {
                            val amt = if (isPreview) item.amount.max(BigInteger.ONE) else item.amount
                            if (amt > BigInteger.ZERO) currencyToNativeAsset(item.currency.trimEnd('.'), amt) else null
                        } else {
                            null
                        }
                    }
                )
            PlannedOutput(
                address = address,
                lovelace = totalLovelace.max(if (isPreview) MIN_PURE_ADA_FLOOR else BigInteger.ZERO),
                nativeAssets = nativeAssets,
            )
        }
    }

    private fun computeStandardTokenLocked(
        allUtxos: List<Utxo>,
        selectedUtxos: List<Utxo>,
        maxInputs: Int,
    ): BigInteger {
        // Lovelace sitting on unselected multi-asset UTxOs cannot be freely spent as pure ADA
        val unselectedMultiAssetAda =
            allUtxos
                .filter { it.nativeAssets.isNotEmpty() && it !in selectedUtxos }
                .sumByBigInteger { it.lovelace }

        // If pure-ADA UTxO count exceeds the 16KB single-tx input limit, cap spendable pure ADA as well
        val unselectedPureAda =
            allUtxos
                .filter { it.nativeAssets.isEmpty() && it !in selectedUtxos }
                .sortedByDescending { it.lovelace }
        val remainingInputSlots = (maxInputs - selectedUtxos.size).coerceAtLeast(0)
        val unspendablePureAdaOverflow =
            if (unselectedPureAda.size > remainingInputSlots) {
                unselectedPureAda.drop(remainingInputSlots).sumByBigInteger { it.lovelace }
            } else {
                BigInteger.ZERO
            }

        return unselectedMultiAssetAda + unspendablePureAdaOverflow
    }

    private fun computeMaxSafeInputCount(
        maxTxSize: Int,
        witnessCount: Int,
        extraBytes: Int,
        outputCount: Int,
    ): Int {
        val reservedBytes =
            BASE_TX_OVERHEAD_BYTES +
                (witnessCount.coerceAtLeast(1) * WITNESS_CBOR_BYTES) +
                extraBytes +
                (outputCount.coerceAtLeast(1) * 90)
        val availableForInputs = (maxTxSize - reservedBytes).coerceAtLeast(APPROX_INPUT_CBOR_BYTES)
        return (availableForInputs / APPROX_INPUT_CBOR_BYTES).coerceIn(1, 400)
    }

    private fun resolveMaxTxSize(protocolParameters: ProtocolParameters): Int =
        protocolParameters.maxTxSize.toInt().takeIf { it > 0 } ?: DEFAULT_MAX_TX_SIZE

    private fun serializeDummySignedTransaction(
        selectedUtxos: List<Utxo>,
        outputs: List<PlannedOutput>,
        fee: BigInteger,
        ttl: Long,
        withdrawals: List<WithdrawalItem>,
        metadataJson: String?,
        witnessCount: Int,
    ): ByteArray {
        val inputs =
            selectedUtxos.map { utxo ->
                TransactionInput
                    .builder()
                    .transactionId(normalizeTxHash(utxo.hash))
                    .index(utxo.ix.toInt())
                    .build()
            }
        val sdkOutputs = outputs.map { toSdkTransactionOutput(it) }
        val sdkWithdrawals =
            withdrawals.mapIndexed { idx, w ->
                Withdrawal(toSdkSafeStakeAddress(w.stakingAddress, idx), w.lovelace)
            }

        var auxiliaryData: AuxiliaryData? = null
        var auxHash: ByteArray? = null
        var rawMetadataFallbackBytes = 0
        if (!metadataJson.isNullOrBlank()) {
            runCatching {
                val cborMeta = JsonNoSchemaToMetadataConverter.jsonToCborMetadata(metadataJson)
                auxiliaryData = AuxiliaryData.builder().metadata(cborMeta).build()
                auxHash = auxiliaryData?.getAuxiliaryDataHash(Era.Conway)
            }.onFailure {
                rawMetadataFallbackBytes = metadataJson.toByteArray(Charsets.UTF_8).size + 36
            }
        }

        val bodyBuilder =
            TransactionBody
                .builder()
                .inputs(inputs)
                .outputs(sdkOutputs)
                .fee(fee.max(BigInteger.ONE))
                .ttl(if (ttl > 0) ttl else 100_000_000L)

        if (sdkWithdrawals.isNotEmpty()) {
            bodyBuilder.withdrawals(sdkWithdrawals)
        }
        if (auxHash != null) {
            bodyBuilder.auxiliaryDataHash(auxHash)
        }

        val dummyWitnesses =
            (1..witnessCount.coerceAtLeast(1)).map { idx ->
                VkeyWitness
                    .builder()
                    .vkey(ByteArray(32) { (idx + it).toByte() })
                    .signature(ByteArray(64) { (idx * 3 + it).toByte() })
                    .build()
            }

        val tx =
            Transaction
                .builder()
                .era(Era.Conway)
                .body(bodyBuilder.build())
                .witnessSet(TransactionWitnessSet.builder().vkeyWitnesses(dummyWitnesses).build())
                .isValid(true)
                .auxiliaryData(auxiliaryData)
                .build()

        val cbor = tx.serialize()
        return if (rawMetadataFallbackBytes > 0) ByteArray(cbor.size + rawMetadataFallbackBytes) else cbor
    }

    private fun toSdkProtocolParams(params: ProtocolParameters): ProtocolParams {
        val coinsPerByte =
            params.utxoCostPerByte
                ?: params.utxoCostPerWord?.let { it / 8L }
                ?: 4310L
        return ProtocolParams
            .builder()
            .minFeeA(params.txFeePerByte.toInt())
            .minFeeB(params.txFeeFixed.toInt())
            .maxTxSize(params.maxTxSize.toInt())
            .maxBlockSize(params.maxBlockBodySize.toInt())
            .maxBlockHeaderSize(params.maxBlockHeaderSize.toInt())
            .keyDeposit(params.stakeAddressDeposit.toString())
            .poolDeposit(params.stakePoolDeposit.toString())
            .minPoolCost(params.minPoolCost.toString())
            .minUtxo(params.minUTxOValue?.toString() ?: "1000000")
            .coinsPerUtxoSize(coinsPerByte.toString())
            .protocolMajorVer(params.protocolVersion.major)
            .protocolMinorVer(params.protocolVersion.minor)
            .minFeeRefScriptCostPerByte(params.minFeeRefScriptCostPerByte ?: BigDecimal.valueOf(15L))
            .build()
    }

    private fun toSdkTransactionOutput(output: PlannedOutput): TransactionOutput {
        val multiAssets = toSdkMultiAssets(output.nativeAssets)
        val value =
            Value
                .builder()
                .coin(output.lovelace.max(MIN_PURE_ADA_FLOOR))
                .multiAssets(multiAssets)
                .build()
        return TransactionOutput
            .builder()
            .address(toSdkSafeAddress(output.address))
            .value(value)
            .build()
    }

    private fun toSdkMultiAssets(nativeAssets: List<NativeAsset>): List<MultiAsset> {
        if (nativeAssets.isEmpty()) return emptyList()
        return nativeAssets
            .filter { it.amount > BigInteger.ZERO }
            .groupBy { normalizePolicyId(it.policy) }
            .map { (policyHex, assets) ->
                MultiAsset
                    .builder()
                    .policyId(policyHex)
                    .assets(assets.map { toSdkAsset(it) })
                    .build()
            }
    }

    private fun toSdkAsset(nativeAsset: NativeAsset): Asset {
        val rawName = nativeAsset.name
        val encodedName =
            when {
                rawName.isEmpty() -> ""
                isEvenHexString(rawName, maxChars = 64) -> "0x${rawName.lowercase()}"
                rawName.toByteArray(Charsets.UTF_8).size <= 32 -> rawName
                else ->
                    "0x" +
                        rawName
                            .toByteArray(Charsets.UTF_8)
                            .take(32)
                            .joinToString("") { "%02x".format(it) }
            }
        return Asset(encodedName, nativeAsset.amount)
    }

    private fun toSdkUnit(nativeAsset: NativeAsset): String =
        AssetUtil.getUnit(normalizePolicyId(nativeAsset.policy), toSdkAsset(nativeAsset))

    private fun buildRequestedAmounts(
        requestedLovelace: BigInteger,
        requestedAssets: Map<String, BigInteger>,
    ): List<Amount> {
        val amounts = mutableListOf<Amount>()
        if (requestedLovelace > BigInteger.ZERO) {
            amounts.add(Amount.lovelace(requestedLovelace))
        }
        requestedAssets.forEach { (currency, qty) ->
            if (qty > BigInteger.ZERO) {
                val asset = currencyToNativeAsset(currency, qty)
                amounts.add(Amount(toSdkUnit(asset), qty))
            }
        }
        if (amounts.isEmpty()) {
            amounts.add(Amount.lovelace(MIN_PURE_ADA_FLOOR))
        }
        return amounts
    }

    private fun buildSupplierAndMap(
        safeAddress: String,
        utxos: List<Utxo>,
    ): Pair<UtxoSupplier, Map<Pair<String, Int>, Utxo>> {
        val lookup = LinkedHashMap<Pair<String, Int>, Utxo>(utxos.size)
        val sdkUtxos =
            utxos.mapIndexed { idx, utxo ->
                val txHash = normalizeTxHash("${utxo.hash}_$idx".takeIf { lookup.containsKey(normalizeTxHash(utxo.hash) to utxo.ix.toInt()) } ?: utxo.hash)
                val outIdx = utxo.ix.toInt()
                lookup[txHash to outIdx] = utxo
                val amounts = mutableListOf(Amount.lovelace(utxo.lovelace))
                utxo.nativeAssets.forEach { asset ->
                    if (asset.amount > BigInteger.ZERO) {
                        amounts.add(Amount(toSdkUnit(asset), asset.amount))
                    }
                }
                SdkUtxo
                    .builder()
                    .txHash(txHash)
                    .outputIndex(outIdx)
                    .address(safeAddress)
                    .amount(amounts)
                    .build()
            }
        return InMemoryUtxoSupplier(mapOf(safeAddress to sdkUtxos)) to lookup
    }

    private fun coversRequestedAmounts(
        selected: List<Utxo>,
        requestedLovelace: BigInteger,
        requestedAssets: Map<String, BigInteger>,
    ): Boolean {
        if (selected.sumByBigInteger { it.lovelace } < requestedLovelace) {
            return false
        }
        if (requestedAssets.isNotEmpty()) {
            val selectedMap = selected.toNativeAssetMap()
            for ((currency, reqQty) in requestedAssets) {
                if ((selectedMap[currency] ?: BigInteger.ZERO) < reqQty) {
                    return false
                }
            }
        }
        return true
    }

    private fun subtractAssetMaps(
        source: Map<String, BigInteger>,
        toSubtract: Map<String, BigInteger>,
    ): Map<String, BigInteger> {
        val result = sortedMapOf<String, BigInteger>()
        source.forEach { (currency, amount) ->
            val rem = amount - (toSubtract[currency] ?: BigInteger.ZERO)
            if (rem > BigInteger.ZERO) {
                result[currency] = rem
            }
        }
        return result
    }

    private fun mergeAssetsList(assets: List<NativeAsset>): List<NativeAsset> {
        val map = LinkedHashMap<Pair<String, String>, BigInteger>()
        assets.forEach { asset ->
            val key = asset.policy to asset.name
            map[key] = (map[key] ?: BigInteger.ZERO) + asset.amount
        }
        return map.map { (k, v) -> NativeAsset(name = k.second, policy = k.first, amount = v) }
    }

    fun currencyToNativeAsset(
        currency: String,
        amount: BigInteger,
    ): NativeAsset {
        val trimmed = currency.trimEnd('.')
        val dotIndex = trimmed.indexOf('.')
        return if (dotIndex >= 0) {
            NativeAsset(
                policy = trimmed.substring(0, dotIndex),
                name = trimmed.substring(dotIndex + 1),
                amount = amount,
            )
        } else {
            NativeAsset(
                policy = trimmed,
                name = "",
                amount = amount,
            )
        }
    }

    private fun toSdkSafeAddress(address: String): String =
        runCatching {
            val parsed = Address(address)
            if (parsed.bytes != null && parsed.bytes.isNotEmpty()) parsed.toBech32() else MinAdaCalculator.DUMMY_ADDRESS
        }.getOrDefault(MinAdaCalculator.DUMMY_ADDRESS)

    private fun toSdkSafeStakeAddress(
        stakeAddress: String,
        index: Int = 0,
    ): String {
        val fallback by lazy {
            com.bloxbean.cardano.client.crypto.Bech32.encode(
                byteArrayOf(0xe0.toByte()) + ByteArray(28) { ((index + 1 + it) and 0xff).toByte() },
                "stake_test",
            )
        }
        return runCatching {
            val parsed = Address(stakeAddress)
            if (parsed.bytes != null && parsed.bytes.size == 29) parsed.toBech32() else fallback
        }.getOrDefault(fallback)
    }

    private fun normalizePolicyId(policy: String): String =
        if (policy.length == 56 && isEvenHexString(policy, maxChars = 56)) {
            policy.lowercase()
        } else {
            sha256Hex(policy).substring(0, 56)
        }

    private fun normalizeTxHash(hash: String): String =
        if (hash.length == 64 && isEvenHexString(hash, maxChars = 64)) {
            hash.lowercase()
        } else {
            sha256Hex(hash)
        }

    private fun isEvenHexString(
        value: String,
        maxChars: Int,
    ): Boolean =
        value.isNotEmpty() &&
            value.length % 2 == 0 &&
            value.length <= maxChars &&
            value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun sha256Hex(input: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private class InMemoryUtxoSupplier(
        private val utxosByAddress: Map<String, List<SdkUtxo>>,
    ) : UtxoSupplier {
        override fun getPage(
            address: String,
            nrOfItems: Int?,
            page: Int?,
            order: OrderEnum?,
        ): List<SdkUtxo> {
            val all = utxosByAddress[address].orEmpty()
            val pageNum = page ?: 0
            val pageSize = nrOfItems?.takeIf { it > 0 } ?: UtxoSupplier.DEFAULT_NR_OF_ITEMS_TO_FETCH
            val fromIndex = pageNum * pageSize
            if (fromIndex >= all.size) return emptyList()
            val ordered = if (order == OrderEnum.desc) all.asReversed() else all
            return ordered.subList(fromIndex, (fromIndex + pageSize).coerceAtMost(ordered.size))
        }

        override fun getTxOutput(
            txHash: String,
            outputIndex: Int,
        ): Optional<SdkUtxo> =
            Optional.ofNullable(
                utxosByAddress.values
                    .asSequence()
                    .flatten()
                    .firstOrNull { it.txHash == txHash && it.outputIndex == outputIndex }
            )

        override fun getAll(address: String): List<SdkUtxo> = utxosByAddress[address].orEmpty()
    }
}
