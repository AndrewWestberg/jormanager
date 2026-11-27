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
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil
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
import java.util.TreeMap
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
        val ZERO_MULTI_ASSET_CBOR_BYTES: Int =
            CborSerializationUtil
                .serialize(
                    Value
                        .builder()
                        .coin(MIN_PURE_ADA_FLOOR)
                        .build()
                        .serialize()
                ).size
    }

    /**
     * Programmatically calculates the minimum lovelace required for an output carrying [nativeAssets]
     * at [address] using Bloxbean's [MinAdaCalculator] and [ProtocolParameters] without CLI dry-runs.
     */
    fun calculateMinAda(
        address: String,
        nativeAssets: List<NativeAsset>,
        protocolParameters: ProtocolParameters,
    ): BigInteger = calculateMinAda(address, nativeAssets, PlanContext(protocolParameters))

    private fun calculateMinAda(
        address: String,
        nativeAssets: List<NativeAsset>,
        ctx: PlanContext,
    ): BigInteger = calculateMinAdaWithSafeAddress(ctx.safeAddress(address), nativeAssets, ctx)

    private fun calculateMinAdaWithSafeAddress(
        safeAddress: String,
        nativeAssets: List<NativeAsset>,
        ctx: PlanContext,
    ): BigInteger {
        if (nativeAssets.isEmpty()) {
            return ctx.pureAdaMinLovelace(safeAddress)
        }
        val multiAssets = toSdkMultiAssets(nativeAssets, ctx)
        if (multiAssets.isEmpty()) {
            return ctx.pureAdaMinLovelace(safeAddress)
        }
        val valueBytes =
            CborSerializationUtil
                .serialize(
                    Value
                        .builder()
                        .coin(MIN_PURE_ADA_FLOOR)
                        .multiAssets(multiAssets)
                        .build()
                        .serialize()
                ).size
        val outputBytes = ctx.addressWrapperBytes(safeAddress) + valueBytes
        val calculated = ctx.coinsPerUtxoSize.multiply(BigInteger.valueOf((outputBytes + 160).toLong()))
        return calculated.max(ctx.legacyMinUtxoFloor)
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
    ): Pair<BigInteger, Int> =
        calculateFeeAndSize(
            selectedUtxos = selectedUtxos,
            outputs = outputs,
            currentFeeEstimate = currentFeeEstimate,
            ctx = PlanContext(protocolParameters, selectedUtxos),
            ttl = ttl,
            withdrawals = withdrawals,
            metadataJson = metadataJson,
            witnessCount = witnessCount,
            extraPayloadBytes = extraPayloadBytes,
            referenceScriptBytes = referenceScriptBytes,
        )

    private fun calculateFeeAndSize(
        selectedUtxos: List<Utxo>,
        outputs: List<PlannedOutput>,
        currentFeeEstimate: BigInteger,
        ctx: PlanContext,
        ttl: Long = 100_000_000L,
        withdrawals: List<WithdrawalItem> = emptyList(),
        metadataJson: String? = null,
        witnessCount: Int = 1,
        extraPayloadBytes: Int = 0,
        referenceScriptBytes: Long = 0L,
    ): Pair<BigInteger, Int> {
        val sdkParams = ctx.sdkParams
        val serializedBytes =
            serializeDummySignedTransaction(
                selectedUtxos = selectedUtxos,
                outputs = outputs,
                fee = currentFeeEstimate.max(BigInteger.valueOf(155_381L)),
                ttl = ttl,
                withdrawals = withdrawals,
                metadataJson = metadataJson,
                witnessCount = witnessCount,
                ctx = ctx,
            )
        val estimatedTxBytes = serializedBytes.size + extraPayloadBytes + CONWAY_CBOR_SAFETY_BYTES
        val feeService = ctx.feeService
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

        val ctx = PlanContext(protocolParameters, utxos)
        val maxTxSize = ctx.maxTxSize
        val minPureAda = calculateMinAda(changeAddress, emptyList(), ctx).max(MIN_PURE_ADA_FLOOR)

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
                ctx = ctx,
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
                    ctx = ctx,
                )
            selectedUtxos = selectionOutcome.first
            usedFallback = usedFallback || selectionOutcome.second

            val tokenChangeOutputs =
                buildGroupedTokenOutputs(
                    address = changeAddress,
                    assetMap = selectedUtxos.toNativeAssetMap(),
                    ctx = ctx,
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

            val (newFee, _) =
                calculateFeeAndSize(
                    selectedUtxos = selectedUtxos,
                    outputs = provisionalOutputs,
                    currentFeeEstimate = estimatedFee,
                    ctx = ctx,
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
                val (verifiedFee, _) =
                    calculateFeeAndSize(
                        selectedUtxos = selectedUtxos,
                        outputs = finalOutputs,
                        currentFeeEstimate = newFee,
                        ctx = ctx,
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
                        ctx = ctx,
                        ttl = ttl,
                        witnessCount = witnessCount,
                        extraPayloadBytes = extraPayloadBytes,
                        referenceScriptBytes = referenceScriptBytes,
                    )
                validateTransactionSize(finalBytes, maxTxSize)
                val unspentTokenLocked = sumUnselectedMultiAssetLovelace(utxos, selectedUtxos, ctx)
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
                        ctx = ctx,
                    )
                if (fallbackUtxos.isNotEmpty()) {
                    selectedUtxos = fallbackUtxos
                    usedFallback = true
                    val fbTokens =
                        buildGroupedTokenOutputs(
                            address = changeAddress,
                            assetMap = selectedUtxos.toNativeAssetMap(),
                            ctx = ctx,
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
                            ctx = ctx,
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
                        val (verifiedFbFee, _) =
                            calculateFeeAndSize(
                                selectedUtxos = selectedUtxos,
                                outputs = fbOutputs,
                                currentFeeEstimate = fbFee,
                                ctx = ctx,
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
                                ctx = ctx,
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
                            tokenLocked = sumUnselectedMultiAssetLovelace(utxos, selectedUtxos, ctx),
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

        val ctx = PlanContext(protocolParameters, utxos)

        if (isClaim) {
            return planClaimRewardsTransaction(
                feePayerUtxos = utxos,
                toAccountItems = toAccountItems,
                withdrawals = withdrawals,
                metadataJson = metadataJson,
                ttl = ttl,
                witnessCount = witnessCount,
                referenceScriptBytes = referenceScriptBytes,
                ctx = ctx,
                fixedFeeOverride = fixedFeeOverride,
                isPreview = isPreview,
            )
        }

        val tokenFees = computePerLineTokenFees(toAccountItems, ctx)

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
                ctx = ctx,
                fixedFeeOverride = fixedFeeOverride,
                isPreview = isPreview,
            )
        }

        val maxTxSize = ctx.maxTxSize
        val minPureAda = calculateMinAda(changeAddress, emptyList(), ctx).max(MIN_PURE_ADA_FLOOR)

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

        val uniqueDestinations = toAccountItems
            .map { it.destinationKey }
            .distinct()
            .size
            .coerceAtLeast(1)
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
                ctx = ctx,
                maxInputs = maxInputs,
                maxTxSize = maxTxSize,
                minPureAda = minPureAda,
                fixedFeeOverride = fixedFeeOverride,
                isPreview = isPreview,
            )
        }

        val pureAdaUtxoCount = utxos.count { it.nativeAssets.isEmpty() }
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
                        ctx = ctx,
                    )
                }.getOrElse { ex ->
                    if (!isPreview) throw ex
                    // During live UI typing preview, select largest spendable subset so exact fee & tokenLocked can be returned
                    selectPreviewFallbackUtxos(utxos, requestedAssets, maxInputs, ctx) to true
                }

            selectedUtxos = selectionResult.first
            usedFallback = usedFallback || selectionResult.second

            val remainingAssets = subtractAssetMaps(selectedUtxos.toNativeAssetMap(), requestedAssets)
            val tokenChangeOutputs =
                buildGroupedTokenOutputs(
                    address = changeAddress,
                    assetMap = remainingAssets,
                    ctx = ctx,
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
                    ctx = ctx,
                    ttl = ttl,
                    metadataJson = metadataJson,
                    witnessCount = witnessCount,
                    referenceScriptBytes = referenceScriptBytes,
                )
            val effectiveFee = fixedFeeOverride?.max(computedFee) ?: computedFee
            val finalResidualAda = selectedLovelace - totalRecipientAda - effectiveFee - tokenKeepFee

            if (isPreview && finalResidualAda < BigInteger.ZERO && attempt >= 1) {
                validateTransactionSize(computedBytes, maxTxSize)
                val tokenLocked = computeStandardTokenLocked(utxos, selectedUtxos, maxInputs, ctx)
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
                    selectedUtxos.size < pureAdaUtxoCount

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
                val (verifiedFeeRaw, _) =
                    calculateFeeAndSize(
                        selectedUtxos = selectedUtxos,
                        outputs = finalOutputs,
                        currentFeeEstimate = candidateFee,
                        ctx = ctx,
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
                        ctx = ctx,
                        ttl = ttl,
                        metadataJson = metadataJson,
                        witnessCount = witnessCount,
                        referenceScriptBytes = referenceScriptBytes,
                    )
                validateTransactionSize(finalBytes, maxTxSize)
                val tokenLocked = computeStandardTokenLocked(utxos, selectedUtxos, maxInputs, ctx)
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
                        ctx = ctx,
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
                        ctx = ctx,
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
        ctx: PlanContext,
        fixedFeeOverride: BigInteger? = null,
        isPreview: Boolean = false,
    ): TransactionPlan {
        val maxTxSize = ctx.maxTxSize
        val minPureAda = calculateMinAda(changeAddress, emptyList(), ctx).max(MIN_PURE_ADA_FLOOR)

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
                ctx = ctx,
            )

        val withdrawalTotal = withdrawals.sumByBigInteger { it.lovelace }
        val totalWalletLovelace = utxos.sumByBigInteger { it.lovelace }

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
                    ctx = ctx,
                    defragmentByPolicy = true,
                )
            val tokenKeepAda = tokenOutputs.sumByBigInteger { it.lovelace }
            val candidateLovelace = candidateUtxos.sumByBigInteger { it.lovelace }
            val totalInputAda = candidateLovelace + depositRefund + withdrawalTotal

            var feeEstimate = fixedFeeOverride ?: INITIAL_ESTIMATED_FEE
            var finalOutputs: List<PlannedOutput> = emptyList()

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
                        inputCount = candidateUtxos.size,
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
                        ctx = ctx,
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
                    inputCount = candidateUtxos.size,
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
                    ctx = ctx,
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
            val unspentLovelace = totalWalletLovelace - candidateLovelace
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

    private fun planClaimRewardsTransaction(
        feePayerUtxos: List<Utxo>,
        toAccountItems: List<WalletAccountLineItem>,
        withdrawals: List<WithdrawalItem>,
        metadataJson: String?,
        ttl: Long,
        witnessCount: Int,
        referenceScriptBytes: Long,
        ctx: PlanContext,
        fixedFeeOverride: BigInteger?,
        isPreview: Boolean,
    ): TransactionPlan {
        val maxTxSize = ctx.maxTxSize
        val feePayerAddress =
            toAccountItems.firstOrNull { it.isFeePayerClaimAccount }?.address
                ?: toAccountItems.firstOrNull()?.address
                ?: MinAdaCalculator.DUMMY_ADDRESS
        val minPureAda = calculateMinAda(feePayerAddress, emptyList(), ctx).max(MIN_PURE_ADA_FLOOR)
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
                    ctx = ctx,
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

            val (calcFee, _) =
                calculateFeeAndSize(
                    selectedUtxos = selectedUtxos,
                    outputs = outputs,
                    currentFeeEstimate = estimatedFee,
                    ctx = ctx,
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
                        ctx = ctx,
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
        ctx: PlanContext,
        maxInputs: Int,
        maxTxSize: Int,
        minPureAda: BigInteger,
        fixedFeeOverride: BigInteger?,
        isPreview: Boolean,
    ): TransactionPlan {
        val selected = mutableListOf<Utxo>()
        val selectedSet = UtxoMembershipSet.create(ctx.uniqueUtxoRefs, maxInputs.coerceAtMost(utxos.size))
        val remainingAssetReq = requestedAssets.toMutableMap()

        // 1. Select UTxOs required for any explicit native asset transfers
        if (remainingAssetReq.isNotEmpty()) {
            val scoredAssetUtxos = ArrayList<Pair<Utxo, BigInteger>>()
            for (utxo in utxos) {
                if (utxo.nativeAssets.isEmpty()) continue
                val score =
                    utxo.nativeAssets.sumByBigInteger { asset ->
                        val cur = "${asset.policy}.${asset.name}".trimEnd('.')
                        if ((remainingAssetReq[cur] ?: BigInteger.ZERO) > BigInteger.ZERO) BigInteger.ONE else BigInteger.ZERO
                    }
                scoredAssetUtxos.add(utxo to score)
            }
            scoredAssetUtxos.sortByDescending { it.second }
            for ((utxo, _) in scoredAssetUtxos) {
                if (remainingAssetReq.values.any { it > BigInteger.ZERO } && selected.size < maxInputs) {
                    val contributes =
                        utxo.nativeAssets.any { asset ->
                            val cur = "${asset.policy}.${asset.name}".trimEnd('.')
                            (remainingAssetReq[cur] ?: BigInteger.ZERO) > BigInteger.ZERO
                        }
                    if (contributes) {
                        selected.add(utxo)
                        selectedSet.add(utxo)
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
        val unselectedPureAda = ArrayList<Utxo>()
        for (utxo in utxos) {
            if (utxo.nativeAssets.isEmpty() && utxo !in selectedSet) {
                unselectedPureAda.add(utxo)
            }
        }
        unselectedPureAda.sortByDescending { it.lovelace }
        for (utxo in unselectedPureAda) {
            if (selected.size >= maxInputs) break
            selected.add(utxo)
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
                ctx = ctx,
            )
        val tokenKeepFee = tokenChangeOutputs.sumByBigInteger { it.lovelace }
        val selectedLovelace = selected.sumByBigInteger { it.lovelace }
        val tokenLocked = utxos.sumByBigInteger { it.lovelace } - selectedLovelace
        val recipientTokenTopUpAda = perLineTokenFees.sumByBigInteger { it }

        var feeEstimate = fixedFeeOverride ?: INITIAL_ESTIMATED_FEE
        var outputs: List<PlannedOutput>

        for (iter in 0 until 5) {
            val distributableAda = (selectedLovelace - feeEstimate - tokenKeepFee - recipientTokenTopUpAda).max(BigInteger.ZERO)
            val adjustedItems = rescaleFullPercentItems(toAccountItems, distributableAda, isPreview)
            outputs = buildRecipientOutputs(adjustedItems, perLineTokenFees, isPreview = isPreview) + tokenChangeOutputs
            val (calcFee, _) =
                calculateFeeAndSize(
                    selectedUtxos = selected,
                    outputs = outputs,
                    currentFeeEstimate = feeEstimate,
                    ctx = ctx,
                    ttl = ttl,
                    metadataJson = metadataJson,
                    witnessCount = witnessCount,
                    referenceScriptBytes = referenceScriptBytes,
                )
            val nextFee = fixedFeeOverride?.max(calcFee) ?: calcFee
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
                ctx = ctx,
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
        ctx: PlanContext,
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
                ctx = ctx,
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
                    ctx = ctx,
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
                ctx = ctx,
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
            tokenLocked = computeStandardTokenLocked(allUtxos, selectedUtxos, maxInputs, ctx),
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
        ctx: PlanContext,
    ): Pair<List<Utxo>, Boolean> {
        val safeAddr = ctx.safeAddress(address)
        val requestedAmounts = buildRequestedAmounts(requestedLovelace, requestedAssets)

        // Prefer pure-ADA UTxOs first when only ADA is needed and pure-ADA UTxOs have enough balance;
        // otherwise evaluate the full wallet UTxO pool.
        val candidatePools =
            if (requestedAssets.isEmpty() && ctx.pureAdaPoolLovelace(utxos) >= requestedLovelace) {
                listOf(ctx.pureAdaPool(utxos), utxos)
            } else {
                listOf(utxos)
            }

        var lastError: Throwable? = null
        for ((poolIndex, pool) in candidatePools.withIndex()) {
            val (supplier, lookupMap) = ctx.supplierAndMap(safeAddr, pool)
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
        ctx: PlanContext,
    ): List<Utxo> {
        val selected = mutableListOf<Utxo>()
        val selectedSet = UtxoMembershipSet.create(ctx.uniqueUtxoRefs, maxInputs.coerceAtMost(utxos.size))
        val remainingAssets = requestedAssets.toMutableMap()
        val safeChangeAddress = ctx.safeAddress(changeAddress)
        val tokenTracker = IncrementalTokenKeepTracker(safeChangeAddress, requestedAssets, ctx)
        var selectedLovelace = BigInteger.ZERO

        // First satisfy requested native assets
        if (remainingAssets.isNotEmpty()) {
            val scoredAssetUtxos = ArrayList<Pair<Utxo, BigInteger>>()
            for (utxo in utxos) {
                if (utxo.nativeAssets.isEmpty()) continue
                val score =
                    utxo.nativeAssets.sumByBigInteger { asset ->
                        val cur = "${asset.policy}.${asset.name}".trimEnd('.')
                        if ((remainingAssets[cur] ?: BigInteger.ZERO) > BigInteger.ZERO) asset.amount else BigInteger.ZERO
                    }
                scoredAssetUtxos.add(utxo to score)
            }
            scoredAssetUtxos.sortByDescending { it.second }
            for ((utxo, _) in scoredAssetUtxos) {
                if (remainingAssets.values.any { it > BigInteger.ZERO } && selected.size < maxInputs) {
                    val matches =
                        utxo.nativeAssets.any { asset ->
                            val cur = "${asset.policy}.${asset.name}".trimEnd('.')
                            (remainingAssets[cur] ?: BigInteger.ZERO) > BigInteger.ZERO
                        }
                    if (matches) {
                        selected.add(utxo)
                        selectedSet.add(utxo)
                        selectedLovelace += utxo.lovelace
                        tokenTracker.addUtxo(utxo)
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

        // Next precompute net unlocked lovelace once per multi-asset UTxO before sorting
        val scoredCandidates = ArrayList<Pair<Utxo, BigInteger>>(utxos.size)
        for (utxo in utxos) {
            if (utxo in selectedSet) continue
            val netUnlocked =
                if (utxo.nativeAssets.isEmpty()) {
                    utxo.lovelace
                } else {
                    val minLock = calculateMinAdaWithSafeAddress(safeChangeAddress, utxo.nativeAssets, ctx)
                    utxo.lovelace - minLock
                }
            scoredCandidates.add(utxo to netUnlocked)
        }
        scoredCandidates.sortByDescending { it.second }

        for ((candidate, _) in scoredCandidates) {
            val currentKeepAda = tokenTracker.currentKeepAda()
            val currentNetAda = selectedLovelace - currentKeepAda
            if (currentNetAda >= requiredNetLovelace && selected.isNotEmpty()) {
                break
            }
            if (selected.size >= maxInputs) {
                break
            }
            selected.add(candidate)
            selectedLovelace += candidate.lovelace
            tokenTracker.addUtxo(candidate)
        }

        val finalKeepAda = tokenTracker.currentKeepAda()
        val finalNetAda = selectedLovelace - finalKeepAda
        return if (finalNetAda >= requiredNetLovelace) selected else emptyList()
    }

    private fun selectPreviewFallbackUtxos(
        utxos: List<Utxo>,
        requestedAssets: Map<String, BigInteger>,
        maxInputs: Int,
        ctx: PlanContext,
    ): List<Utxo> {
        val selected = mutableListOf<Utxo>()
        val selectedSet = UtxoMembershipSet.create(ctx.uniqueUtxoRefs, maxInputs.coerceAtMost(utxos.size))
        if (requestedAssets.isNotEmpty()) {
            for (utxo in utxos) {
                if (selected.size >= maxInputs) break
                val hasRequestedAsset =
                    utxo.nativeAssets.any { asset ->
                        requestedAssets.containsKey("${asset.policy}.${asset.name}".trimEnd('.'))
                    }
                if (hasRequestedAsset) {
                    selected.add(utxo)
                    selectedSet.add(utxo)
                }
            }
        }
        val remainingPureAda = ArrayList<Utxo>()
        for (utxo in utxos) {
            if (utxo.nativeAssets.isEmpty() && utxo !in selectedSet) {
                remainingPureAda.add(utxo)
            }
        }
        remainingPureAda.sortByDescending { it.lovelace }
        val pureSlots = (maxInputs - selected.size).coerceAtLeast(0)
        for (i in 0 until pureSlots.coerceAtMost(remainingPureAda.size)) {
            selected.add(remainingPureAda[i])
        }
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
        ctx: PlanContext,
    ): List<Utxo> {
        val mandatory = mutableListOf<Utxo>()
        val mandatorySet = UtxoMembershipSet.create(ctx.uniqueUtxoRefs)
        val remAssets = requestedAssets.toMutableMap()

        // 1. Ensure any explicitly requested native assets are included first
        if (remAssets.isNotEmpty()) {
            for (utxo in utxos) {
                if (utxo.nativeAssets.isEmpty()) continue
                val needed =
                    utxo.nativeAssets.any { asset ->
                        val cur = "${asset.policy}.${asset.name}".trimEnd('.')
                        (remAssets[cur] ?: BigInteger.ZERO) > BigInteger.ZERO
                    }
                if (needed) {
                    mandatory.add(utxo)
                    mandatorySet.add(utxo)
                    utxo.nativeAssets.forEach { asset ->
                        val cur = "${asset.policy}.${asset.name}".trimEnd('.')
                        remAssets[cur]?.let { rem -> remAssets[cur] = (rem - asset.amount).max(BigInteger.ZERO) }
                    }
                }
            }
        }

        // 2. Ensure at least one funding UTxO covers mandatoryAda + fees so small prefixes are immediately valid
        val safeChangeAddress = ctx.safeAddress(changeAddress)
        var unlockedSoFar =
            mandatory.sumByBigInteger { utxo ->
                if (utxo.nativeAssets.isEmpty()) {
                    utxo.lovelace
                } else {
                    (utxo.lovelace - calculateMinAdaWithSafeAddress(safeChangeAddress, utxo.nativeAssets, ctx)).max(BigInteger.ZERO)
                }
            }
        if (unlockedSoFar < mandatoryAda) {
            val scoredRemaining = ArrayList<Pair<Utxo, BigInteger>>(utxos.size)
            for (utxo in utxos) {
                if (utxo in mandatorySet) continue
                val netUnlocked =
                    if (utxo.nativeAssets.isEmpty()) {
                        utxo.lovelace
                    } else {
                        utxo.lovelace - calculateMinAdaWithSafeAddress(safeChangeAddress, utxo.nativeAssets, ctx)
                    }
                scoredRemaining.add(utxo to netUnlocked)
            }
            scoredRemaining.sortByDescending { it.second }
            for ((utxo, netUnlocked) in scoredRemaining) {
                if (unlockedSoFar < mandatoryAda) {
                    mandatory.add(utxo)
                    mandatorySet.add(utxo)
                    unlockedSoFar +=
                        if (utxo.nativeAssets.isEmpty()) {
                            utxo.lovelace
                        } else {
                            netUnlocked.max(BigInteger.ZERO)
                        }
                } else {
                    break
                }
            }
        }

        // 3. Sort remaining UTxOs per unfrack.it principles:
        //    - Smallest pure-ADA dust UTxOs ascending by lovelace, interleaved/followed by
        //    - Fragmented multi-asset UTxOs (asset count < MIN_GROUP_SIZE or > MAX_GROUP_SIZE or duplicate policies)
        //    - Remaining pure-ADA UTxOs ascending by lovelace
        val policyFrequency = HashMap<String, Int>()
        for (utxo in utxos) {
            val assets = utxo.nativeAssets
            if (assets.isEmpty()) continue
            if (assets.size == 1) {
                val p = assets[0].policy
                policyFrequency[p] = (policyFrequency[p] ?: 0) + 1
            } else {
                val seenPolicies = HashSet<String>(assets.size * 2)
                for (asset in assets) {
                    if (seenPolicies.add(asset.policy)) {
                        policyFrequency[asset.policy] = (policyFrequency[asset.policy] ?: 0) + 1
                    }
                }
            }
        }

        val dustPureAda = ArrayList<Utxo>()
        val fragmentedTokens = ArrayList<Utxo>()
        val largerPureAda = ArrayList<Utxo>()
        val otherTokenUtxos = ArrayList<Utxo>()
        for (utxo in utxos) {
            if (utxo in mandatorySet) continue
            if (utxo.nativeAssets.isEmpty()) {
                if (utxo.lovelace < DEFRAGMENT_SPLIT_THRESHOLD_LOVELACE) {
                    dustPureAda.add(utxo)
                } else {
                    largerPureAda.add(utxo)
                }
            } else {
                val isFragmented =
                    utxo.nativeAssets.size < NATIVE_ASSET_MIN_GROUP_SIZE ||
                        utxo.nativeAssets.size > NATIVE_ASSET_MAX_GROUP_SIZE ||
                        utxo.nativeAssets.any { (policyFrequency[it.policy] ?: 0) > 1 }
                if (isFragmented) {
                    fragmentedTokens.add(utxo)
                } else {
                    otherTokenUtxos.add(utxo)
                }
            }
        }
        dustPureAda.sortBy { it.lovelace }
        fragmentedTokens.sortWith(compareBy<Utxo> { it.nativeAssets.size }.thenBy { it.lovelace })
        largerPureAda.sortBy { it.lovelace }
        otherTokenUtxos.sortBy { it.lovelace }

        val ordered = ArrayList<Utxo>(utxos.size)
        ordered.addAll(mandatory)
        ordered.addAll(dustPureAda)
        ordered.addAll(fragmentedTokens)
        ordered.addAll(largerPureAda)
        ordered.addAll(otherTokenUtxos)
        return ordered
    }

    private fun buildDefragmentOutputs(
        changeAddress: String,
        recipientItems: List<WalletAccountLineItem>,
        perLineTokenFees: List<BigInteger>,
        tokenOutputs: List<PlannedOutput>,
        netAvailablePureAda: BigInteger,
        nonSweepAdaRequested: BigInteger,
        minPureAda: BigInteger,
        inputCount: Int,
        isSelfConsolidation: Boolean,
        isFullPercentSweep: Boolean,
        isPreview: Boolean,
    ): List<PlannedOutput>? {
        if (!isPreview && netAvailablePureAda < nonSweepAdaRequested) {
            return null
        }

        // Self-consolidation ("Defragment UTxOs" workflow): consolidate pure-ADA wallets into a single
        // clean output; only split spendable pure ADA into 2 outputs alongside native token bundles
        // when doing so still strictly reduces total UTxO count.
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
            val shouldSplitPureAda =
                tokenOutputs.isNotEmpty() &&
                    inputCount > tokenOutputs.size + 2 &&
                    safePureAda >= DEFRAGMENT_SPLIT_THRESHOLD_LOVELACE &&
                    safePureAda >= minPureAda.multiply(BigInteger.TWO)
            val pureAdaOutputs =
                if (shouldSplitPureAda) {
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
        ctx: PlanContext,
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

        val safeAddr = ctx.safeAddress(address)
        return chunks.map { chunk ->
            val minAda = calculateMinAdaWithSafeAddress(safeAddr, chunk, ctx)
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
            outputs
                .indexOfLast {
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
        ctx: PlanContext,
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
                val minRequired = calculateMinAda(item.address, mergedAssets, ctx)
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

    private fun sumUnselectedMultiAssetLovelace(
        allUtxos: List<Utxo>,
        selectedUtxos: List<Utxo>,
        ctx: PlanContext,
    ): BigInteger {
        val selectedSet = UtxoMembershipSet.fromSelected(ctx.uniqueUtxoRefs, selectedUtxos)
        var total = BigInteger.ZERO
        for (utxo in allUtxos) {
            if (utxo.nativeAssets.isNotEmpty() && utxo !in selectedSet) {
                total += utxo.lovelace
            }
        }
        return total
    }

    private fun computeStandardTokenLocked(
        allUtxos: List<Utxo>,
        selectedUtxos: List<Utxo>,
        maxInputs: Int,
        ctx: PlanContext,
    ): BigInteger {
        val selectedSet = UtxoMembershipSet.fromSelected(ctx.uniqueUtxoRefs, selectedUtxos)
        var unselectedMultiAssetAda = BigInteger.ZERO
        val unselectedPureAda = ArrayList<Utxo>()
        for (utxo in allUtxos) {
            if (utxo in selectedSet) continue
            if (utxo.nativeAssets.isNotEmpty()) {
                unselectedMultiAssetAda += utxo.lovelace
            } else {
                unselectedPureAda.add(utxo)
            }
        }

        // If pure-ADA UTxO count exceeds the 16KB single-tx input limit, cap spendable pure ADA as well
        val remainingInputSlots = (maxInputs - selectedUtxos.size).coerceAtLeast(0)
        val unspendablePureAdaOverflow =
            if (unselectedPureAda.size > remainingInputSlots) {
                unselectedPureAda.sortByDescending { it.lovelace }
                var overflow = BigInteger.ZERO
                for (i in remainingInputSlots until unselectedPureAda.size) {
                    overflow += unselectedPureAda[i].lovelace
                }
                overflow
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

    private fun resolveMaxTxSize(protocolParameters: ProtocolParameters): Int = protocolParameters.maxTxSize.toInt().takeIf { it > 0 } ?: DEFAULT_MAX_TX_SIZE

    private fun serializeDummySignedTransaction(
        selectedUtxos: List<Utxo>,
        outputs: List<PlannedOutput>,
        fee: BigInteger,
        ttl: Long,
        withdrawals: List<WithdrawalItem>,
        metadataJson: String?,
        witnessCount: Int,
        ctx: PlanContext,
    ): ByteArray {
        val inputs =
            selectedUtxos.map { utxo ->
                TransactionInput
                    .builder()
                    .transactionId(normalizeTxHash(utxo.hash))
                    .index(utxo.ix.toInt())
                    .build()
            }
        val sdkOutputs = outputs.map { toSdkTransactionOutput(it, ctx) }
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

        val dummyWitnesses = ctx.dummyWitnesses(witnessCount)

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

    private fun toSdkTransactionOutput(
        output: PlannedOutput,
        ctx: PlanContext,
    ): TransactionOutput {
        val multiAssets = toSdkMultiAssets(output.nativeAssets, ctx)
        val value =
            Value
                .builder()
                .coin(output.lovelace.max(MIN_PURE_ADA_FLOOR))
                .multiAssets(multiAssets)
                .build()
        return TransactionOutput
            .builder()
            .address(ctx.safeAddress(output.address))
            .value(value)
            .build()
    }

    private fun toSdkMultiAssets(
        nativeAssets: List<NativeAsset>,
        ctx: PlanContext,
    ): List<MultiAsset> {
        if (nativeAssets.isEmpty()) return emptyList()
        return nativeAssets
            .filter { it.amount > BigInteger.ZERO }
            .groupBy { ctx.normalizedPolicyId(it.policy) }
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

    private fun toSdkUnit(nativeAsset: NativeAsset): String = AssetUtil.getUnit(normalizePolicyId(nativeAsset.policy), toSdkAsset(nativeAsset))

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
    ): Boolean {
        val len = value.length
        if (len == 0 || (len and 1) != 0 || len > maxChars) return false
        for (i in 0 until len) {
            val ch = value[i]
            val isHex = (ch in '0'..'9') || (ch in 'a'..'f') || (ch in 'A'..'F')
            if (!isHex) return false
        }
        return true
    }

    private fun sha256Hex(input: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private data class UtxoRef(
        val hash: String,
        val ix: Long,
    )

    private class UtxoMembershipSet private constructor(
        private val refSet: HashSet<UtxoRef>?,
        private val utxoSet: HashSet<Utxo>?,
    ) {
        fun add(utxo: Utxo): Boolean =
            if (refSet != null) {
                refSet.add(UtxoRef(utxo.hash, utxo.ix))
            } else {
                utxoSet!!.add(utxo)
            }

        operator fun contains(utxo: Utxo): Boolean =
            if (refSet != null) {
                refSet.contains(UtxoRef(utxo.hash, utxo.ix))
            } else {
                utxoSet!!.contains(utxo)
            }

        companion object {
            fun hasUniqueRefs(pool: List<Utxo>): Boolean {
                if (pool.size <= 1) return true
                val seen = HashSet<UtxoRef>(pool.size * 2)
                for (utxo in pool) {
                    if (!seen.add(UtxoRef(utxo.hash, utxo.ix))) {
                        return false
                    }
                }
                return true
            }

            fun create(
                uniqueRefs: Boolean,
                expectedSize: Int = 16,
            ): UtxoMembershipSet {
                val capacity = expectedSize.coerceAtLeast(4) * 2
                return if (uniqueRefs) {
                    UtxoMembershipSet(HashSet(capacity), null)
                } else {
                    UtxoMembershipSet(null, HashSet(capacity))
                }
            }

            fun fromSelected(
                uniqueRefs: Boolean,
                selected: Collection<Utxo>,
            ): UtxoMembershipSet {
                val set = create(uniqueRefs, selected.size)
                for (utxo in selected) {
                    set.add(utxo)
                }
                return set
            }
        }
    }

    private data class AssetKey(
        val policy: String,
        val name: String,
    ) : Comparable<AssetKey> {
        override fun compareTo(other: AssetKey): Int {
            val cmp = policy.compareTo(other.policy)
            return if (cmp != 0) cmp else name.compareTo(other.name)
        }
    }

    private fun toAssetKey(
        policy: String,
        name: String,
    ): AssetKey {
        if (policy.indexOf('.') < 0) {
            if (name.isEmpty()) {
                return AssetKey(policy = policy, name = "")
            }
            if (!name.endsWith('.')) {
                return AssetKey(policy = policy, name = name)
            }
        }
        val parsed = currencyToNativeAsset("$policy.$name", BigInteger.ONE)
        return AssetKey(policy = parsed.policy, name = parsed.name)
    }

    private fun toAssetKeyFromTrimmedCurrency(trimmedCurrency: String): AssetKey {
        val dotIndex = trimmedCurrency.indexOf('.')
        return if (dotIndex >= 0) {
            AssetKey(
                policy = trimmedCurrency.substring(0, dotIndex),
                name = trimmedCurrency.substring(dotIndex + 1),
            )
        } else {
            AssetKey(
                policy = trimmedCurrency,
                name = "",
            )
        }
    }

    private inner class PlanContext(
        val protocolParameters: ProtocolParameters,
        private val rootUtxos: List<Utxo> = emptyList(),
    ) {
        val sdkParams: ProtocolParams = toSdkProtocolParams(protocolParameters)
        val minAdaCalculator: MinAdaCalculator = MinAdaCalculator(sdkParams)
        val coinsPerUtxoSize: BigInteger = BigInteger(sdkParams.coinsPerUtxoSize)
        val legacyMinUtxoFloor: BigInteger =
            protocolParameters.minUTxOValue?.let { BigInteger.valueOf(it) } ?: BigInteger.ZERO
        val maxTxSize: Int = resolveMaxTxSize(protocolParameters)
        val uniqueUtxoRefs: Boolean by lazy(LazyThreadSafetyMode.NONE) {
            UtxoMembershipSet.hasUniqueRefs(rootUtxos)
        }
        val feeService: FeeCalculationServiceImpl by lazy(LazyThreadSafetyMode.NONE) {
            val emptySupplier = InMemoryUtxoSupplier(emptyMap())
            FeeCalculationServiceImpl(emptySupplier, ProtocolParamsSupplier { sdkParams })
        }
        private val safeAddressCache = HashMap<String, String>(8)
        private val addressWrapperBytesCache = HashMap<String, Int>(8)
        private val pureAdaMinCache = HashMap<String, BigInteger>(8)
        private val policyIdCache = HashMap<String, String>(32)
        private val witnessCache = HashMap<Int, List<VkeyWitness>>(4)
        private var cachedPureAdaPool: List<Utxo>? = null
        private var cachedPureAdaLovelace: BigInteger? = null
        private val supplierCache = HashMap<Pair<String, Int>, Pair<UtxoSupplier, Map<Pair<String, Int>, Utxo>>>(4)

        fun safeAddress(address: String): String =
            safeAddressCache.getOrPut(address) {
                toSdkSafeAddress(address)
            }

        fun pureAdaMinLovelace(safeAddress: String): BigInteger =
            pureAdaMinCache.getOrPut(safeAddress) {
                val sdkOutput =
                    TransactionOutput
                        .builder()
                        .address(safeAddress)
                        .value(Value.builder().coin(MIN_PURE_ADA_FLOOR).build())
                        .build()
                minAdaCalculator.calculateMinAda(sdkOutput).max(legacyMinUtxoFloor)
            }

        fun addressWrapperBytes(safeAddress: String): Int =
            addressWrapperBytesCache.getOrPut(safeAddress) {
                val sdkOutput =
                    TransactionOutput
                        .builder()
                        .address(safeAddress)
                        .value(Value.builder().coin(MIN_PURE_ADA_FLOOR).build())
                        .build()
                CborSerializationUtil.serialize(sdkOutput.serialize()).size - ZERO_MULTI_ASSET_CBOR_BYTES
            }

        fun pureAdaPool(utxos: List<Utxo>): List<Utxo> {
            if (utxos === rootUtxos) {
                cachedPureAdaPool?.let { return it }
                val filtered = utxos.filter { it.nativeAssets.isEmpty() }
                cachedPureAdaPool = filtered
                return filtered
            }
            return utxos.filter { it.nativeAssets.isEmpty() }
        }

        fun pureAdaPoolLovelace(utxos: List<Utxo>): BigInteger {
            if (utxos === rootUtxos) {
                cachedPureAdaLovelace?.let { return it }
                val sum = pureAdaPool(utxos).sumByBigInteger { it.lovelace }
                cachedPureAdaLovelace = sum
                return sum
            }
            return utxos.asSequence().filter { it.nativeAssets.isEmpty() }.fold(BigInteger.ZERO) { acc, u -> acc + u.lovelace }
        }

        fun supplierAndMap(
            safeAddress: String,
            pool: List<Utxo>,
        ): Pair<UtxoSupplier, Map<Pair<String, Int>, Utxo>> {
            val poolTag =
                when {
                    pool === rootUtxos -> 1
                    pool === cachedPureAdaPool -> 2
                    else -> 0
                }
            if (poolTag == 0) {
                return buildSupplierAndMap(safeAddress, pool)
            }
            return supplierCache.getOrPut(safeAddress to poolTag) {
                buildSupplierAndMap(safeAddress, pool)
            }
        }

        fun normalizedPolicyId(policy: String): String =
            policyIdCache.getOrPut(policy) {
                normalizePolicyId(policy)
            }

        fun dummyWitnesses(witnessCount: Int): List<VkeyWitness> {
            val count = witnessCount.coerceAtLeast(1)
            return witnessCache.getOrPut(count) {
                (1..count).map { idx ->
                    VkeyWitness
                        .builder()
                        .vkey(ByteArray(32) { (idx + it).toByte() })
                        .signature(ByteArray(64) { (idx * 3 + it).toByte() })
                        .build()
                }
            }
        }
    }

    private inner class IncrementalTokenKeepTracker(
        private val safeChangeAddress: String,
        requestedAssets: Map<String, BigInteger>,
        private val ctx: PlanContext,
    ) {
        private val requestedByKey: Map<AssetKey, BigInteger> =
            if (requestedAssets.isEmpty()) {
                emptyMap()
            } else {
                HashMap<AssetKey, BigInteger>(requestedAssets.size * 2).apply {
                    for ((currency, qty) in requestedAssets) {
                        if (currency == currency.trimEnd('.')) {
                            put(toAssetKeyFromTrimmedCurrency(currency), qty)
                        }
                    }
                }
            }

        private val selectedAmounts = HashMap<AssetKey, BigInteger>()
        private val positiveLeftover = TreeMap<AssetKey, NativeAsset>()
        private var cachedChunks: List<List<NativeAsset>> = emptyList()
        private var cachedChunkMinAda: List<BigInteger> = emptyList()
        private var cachedTotalMinAda: BigInteger = BigInteger.ZERO
        private var dirty: Boolean = false

        fun addUtxo(utxo: Utxo) {
            val assets = utxo.nativeAssets
            if (assets.isEmpty()) return
            for (asset in assets) {
                val key = toAssetKey(asset.policy, asset.name)
                val prevSelected = selectedAmounts[key] ?: BigInteger.ZERO
                val nextSelected = prevSelected + asset.amount
                selectedAmounts[key] = nextSelected

                val req = requestedByKey[key] ?: BigInteger.ZERO
                val prevRem = prevSelected - req
                val nextRem = nextSelected - req
                if (nextRem > BigInteger.ZERO) {
                    if (nextRem != prevRem) {
                        positiveLeftover[key] = NativeAsset(name = key.name, policy = key.policy, amount = nextRem)
                        dirty = true
                    }
                } else if (prevRem > BigInteger.ZERO) {
                    positiveLeftover.remove(key)
                    dirty = true
                }
            }
        }

        fun currentKeepAda(): BigInteger {
            if (!dirty) return cachedTotalMinAda
            dirty = false
            if (positiveLeftover.isEmpty()) {
                cachedChunks = emptyList()
                cachedChunkMinAda = emptyList()
                cachedTotalMinAda = BigInteger.ZERO
                return BigInteger.ZERO
            }

            val chunkCount = (positiveLeftover.size + NATIVE_ASSET_MAX_GROUP_SIZE - 1) / NATIVE_ASSET_MAX_GROUP_SIZE
            val newChunks = ArrayList<List<NativeAsset>>(chunkCount)
            val newChunkMinAda = ArrayList<BigInteger>(chunkCount)
            var totalMinAda = BigInteger.ZERO

            val iter = positiveLeftover.values.iterator()
            var chunkIdx = 0
            while (iter.hasNext()) {
                val chunk = ArrayList<NativeAsset>(NATIVE_ASSET_MAX_GROUP_SIZE)
                while (chunk.size < NATIVE_ASSET_MAX_GROUP_SIZE && iter.hasNext()) {
                    chunk.add(iter.next())
                }
                val prevChunk = if (chunkIdx < cachedChunks.size) cachedChunks[chunkIdx] else null
                val minAda =
                    if (prevChunk != null && sameChunkAssets(prevChunk, chunk)) {
                        cachedChunkMinAda[chunkIdx]
                    } else {
                        calculateMinAdaWithSafeAddress(safeChangeAddress, chunk, ctx)
                    }
                newChunks.add(chunk)
                newChunkMinAda.add(minAda)
                totalMinAda += minAda
                chunkIdx++
            }

            cachedChunks = newChunks
            cachedChunkMinAda = newChunkMinAda
            cachedTotalMinAda = totalMinAda
            return totalMinAda
        }

        private fun sameChunkAssets(
            a: List<NativeAsset>,
            b: List<NativeAsset>,
        ): Boolean {
            if (a.size != b.size) return false
            for (i in a.indices) {
                if (a[i] !== b[i] && a[i] != b[i]) return false
            }
            return true
        }
    }

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
