package com.swiftmako.jormanager.controllers

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.ktx.sumByBigInteger
import com.swiftmako.jormanager.model.CalculateFeeRequest
import com.swiftmako.jormanager.model.CalculateFeeResponse
import com.swiftmako.jormanager.model.CreateWalletEntryRequest
import com.swiftmako.jormanager.model.DeleteWalletEntryRequest
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.model.ProtocolParameters
import com.swiftmako.jormanager.model.QueryTip
import com.swiftmako.jormanager.model.SelectionMode
import com.swiftmako.jormanager.model.WalletItem
import com.swiftmako.jormanager.model.SubmitTransactionRequest
import com.swiftmako.jormanager.model.UpdateStakingAddressRequest
import com.swiftmako.jormanager.model.WithdrawalItem
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.model.toNativeAssetMap
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.ProtocolParametersRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.services.TransactionPlannerService
import com.swiftmako.jormanager.services.WalletAccountLineItem
import com.swiftmako.jormanager.services.WalletTransactionBuilder
import com.swiftmako.jormanager.model.NativeAsset
import com.swiftmako.jormanager.model.TransactionPlan
import com.swiftmako.jormanager.model.Utxo
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_SINGLETON
import org.springframework.context.annotation.Scope
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional

@Controller
@Scope(SCOPE_SINGLETON)
class WalletController
    @Autowired
    constructor(
        private val walletUtils: WalletUtils,
        private val walletRepository: WalletRepository,
        private val fileRepository: FileRepository,
        private val nodeRepository: NodeRepository,
        private val hostRepository: HostRepository,
        private val queryTipAdapter: JsonAdapter<QueryTip>,
        private val shelleyGenesisAdapter: JsonAdapter<GenesisShelley>,
        private val protocolParamsAdapter: JsonAdapter<ProtocolParameters>,
        private val webSocketTemplate: SimpMessagingTemplate,
        private val cardanoRepository: CardanoRepository,
        private val protocolSnapshots: ProtocolParametersRepository,
        private val ledgerDao: LedgerDao,
        private val transactionBuilder: WalletTransactionBuilder,
        private val json: ObjectMapper,
        private val transactionPlannerService: TransactionPlannerService = TransactionPlannerService(),
    ) : CoroutineScope {
        private val log by lazy { KotlinLogging.logger("WalletController") }

        private val debounceFlow = MutableStateFlow<CalculateFeeRequest?>(null)

        override val coroutineContext: CoroutineContext =
            Executors.newSingleThreadScheduledExecutor().asCoroutineDispatcher()

        init {
            launch {
                debounceFlow.filterNotNull().buffer(CONFLATED).collect { request -> calculateTxFee(request) }
            }
        }

        @MessageMapping("/wallet")
        @SendTo("/topic/messages")
        fun getWalletEntries(): SocketResponse<List<WalletItem>> =
            try {
                nodeRepository.findDefault()?.let { defaultNode ->
                    fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)?.let { genesisFile ->
                        val genesis = shelleyGenesisAdapter.fromJson(genesisFile.content)!!
                        val magicString =
                            if (genesis.networkId.equals("testnet", ignoreCase = true)) {
                                "--testnet-magic ${genesis.networkMagic}"
                            } else {
                                "--mainnet"
                            }
                        val defaultHost =
                            hostRepository.findByIdOrNull(defaultNode.hostId)
                                ?: throw IOException("Host not found for default node!")
                        val socketPath = "--socket-path ${defaultHost.nodeHomePath}/${defaultNode.name}/db/socket"
                        val era = cardanoRepository.getEra(defaultHost, defaultNode, magicString, socketPath)
                        val result = runBlocking { walletUtils.getWalletItems(magicString, era) }
                        SocketResponse.Success(
                            type = "wallet",
                            data = result
                        )
                    } ?: throw IOException("Error finding shelley genesis file!")
                } ?: throw IOException("Error finding default node!")
            } catch (e: Throwable) {
                val error = "Fatal error getting wallet entries!"
                log.error(e) { error }
                SocketResponse.Error(type = "wallet", exception = e)
            }

        @MessageMapping("/calculatefee")
        fun receiveCalculateTxFeeRequest(request: CalculateFeeRequest) {
            debounceFlow.value = request
        }

        private fun calculateTxFee(request: CalculateFeeRequest) {
            try {
                val defaultNode = nodeRepository.findDefault() ?: throw IOException("No default node!")
                val genesisFile =
                    fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)
                        ?: throw IOException("Genesis file for default node not found!")
                val genesis = shelleyGenesisAdapter.fromJson(genesisFile.content)!!
                val magicString =
                    if (genesis.networkId.equals("testnet", ignoreCase = true)) {
                        "--testnet-magic ${genesis.networkMagic}"
                    } else {
                        "--mainnet"
                    }
                val defaultHost =
                    hostRepository.findByIdOrNull(defaultNode.hostId)
                        ?: throw IOException("Host not found for default node!")
                val defaultHostConnection = HostConnection(defaultHost, defaultNode)
                val socketPath = "--socket-path ${defaultHost.nodeHomePath}/${defaultNode.name}/db/socket"
                val era = cardanoRepository.getEra(defaultHost, defaultNode, magicString, socketPath)
                val protocolParams = protocolSnapshots.query(defaultHost, defaultNode, defaultHostConnection, era, magicString, socketPath)
                val entries = request.getEffectiveFromIds().map { id ->
                    walletRepository.findByIdOrNull(id)?.takeUnless { it.deleted }
                        ?: throw IOException("Wallet entry id $id not found!")
                }
                require(entries.isNotEmpty() && entries.map { it.id }.distinct().size == entries.size)
                val candidates = if (request.isClaim) {
                    request.toAccounts
                        .mapNotNull { it.account.takeIf { id -> id > 0 && !it.isAddress } }
                        .distinct()
                        .map { id ->
                            walletRepository.findByIdOrNull(id)?.takeUnless { it.deleted }
                                ?: throw IOException("Wallet entry id $id not found!")
                        }.filter { it.paymentSkey != null }
                } else {
                    listOf(entries.first())
                }
                val candidateUtxos = candidates.associate { it.id to walletUtils.getUtxos(defaultHost, defaultHostConnection, magicString, socketPath, era, it.paymentAddr) }
                val payer = if (request.isClaim) {
                    candidates.firstOrNull {
                        candidateUtxos[it.id].orEmpty().sumByBigInteger { utxo -> utxo.lovelace } >= BigInteger("1000000")
                    } ?: throw IOException("No Account found capable of covering the claim fee!")
                } else {
                    entries.first()
                }
                val utxos = candidateUtxos[payer.id].orEmpty()
                require(utxos.isNotEmpty()) { "Fee payer has no available inputs" }
                val rewards = if (request.isClaim) {
                    entries.map { entry ->
                        walletUtils
                            .getWalletItem(defaultHost, defaultHostConnection, magicString, socketPath, era, entry)
                            .stakingAddrLovelace ?: BigInteger.ZERO
                    }
                } else {
                    emptyList()
                }
                val totalRewards = rewards.fold(BigInteger.ZERO, BigInteger::add)
                val addresses = request.toAccounts.map { destination ->
                    if (destination.isAddress) {
                        destination.address
                    } else {
                        walletRepository.findByIdOrNull(destination.account)?.takeUnless { it.deleted }?.paymentAddr
                            ?: throw IOException("Destination account not found")
                    }
                }
                require(addresses.all { it.isNotBlank() }) { "Invalid destination" }
                val selectionMode = SelectionMode.fromString(request.selectionMode)
                val items = request.toAccounts.mapIndexed { index, item ->
                    require(item.percent == null || item.percent in 1..100) { "Invalid percentage" }
                    walletLine(addresses[index], item.currency, item.amount, item.percent, request.isClaim && item.account == payer.id)
                }
                requireWalletDestinations(selectionMode, request.isClaim, entries.size, payer.paymentAddr, items)
                val sourceAssets = utxos.toNativeAssetMap()
                val totalLovelace = utxos.sumByBigInteger { it.lovelace }

                fun amounts(availableAda: BigInteger): List<BigInteger> {
                    val remaining = sourceAssets.toMutableMap()
                    remaining["ada"] = availableAda
                    val percentages = mutableMapOf<String, Int>()
                    return items.map { item ->
                        val currency = item.currency
                        val amount = if (!item.isPercent) {
                            percentages[currency] = 0
                            item.amount
                        } else {
                            val previous = percentages[currency] ?: 0
                            require(item.percent in 1..100 && previous + item.percent <= 100) { "Invalid percentage" }
                            val denominator = BigInteger.valueOf((100 - previous).toLong())
                            percentages[currency] = previous + item.percent
                            ((remaining[currency] ?: BigInteger.ZERO) * BigInteger.valueOf(item.percent.toLong()) + denominator / BigInteger.TWO) / denominator
                        }
                        require(amount.signum() > 0) { "Invalid output amount" }
                        remaining[currency] = (remaining[currency] ?: BigInteger.ZERO) - amount
                        amount
                    }
                }
                val prefix = "/tmp/jormanager-fee-${UUID.randomUUID()}"
                val paramsPath = "$prefix.params"
                val bodyPath = "$prefix.body"
                val metadataPath = "$prefix.metadata"
                val cleanup = mutableListOf(paramsPath, bodyPath)
                val tokenFees = MutableList(request.toAccounts.size) { BigInteger.ZERO }
                try {
                    defaultHostConnection.commandWriteFile(paramsPath, protocolParams)
                    val extra = mutableListOf<String>()
                    if (!request.metadata.isNullOrBlank()) {
                        json.readTree(request.metadata)
                        defaultHostConnection.commandWriteFile(metadataPath, request.metadata)
                        cleanup += metadataPath
                        extra += listOf("--metadata-json-file", metadataPath)
                    }
                    val withdrawals = rewards.mapIndexed { index, reward ->
                        val address = requireNotNull(entries[index].stakingAddr)
                        extra += listOf("--withdrawal", "$address+$reward")
                        WithdrawalItem(address, reward)
                    }
                    val tip = requireNotNull(
                        queryTipAdapter.fromJson(
                            defaultHostConnection.commandArgs(
                                listOf(defaultHost.cardanoCliPath, era, "query", "tip") + magicString.split(" ") +
                                    socketPath.split(" ") + "--output-json"
                            )
                        )
                    )
                    require(tip.slot >= 0) { "Invalid node tip" }
                    val validUntil = Math.addExact(tip.slot, 21600L)
                    val planItems = if (selectionMode == SelectionMode.DEFRAGMENT) {
                        emptyList()
                    } else {
                        val provisionalAmounts = amounts(if (request.isClaim) totalRewards else totalLovelace)
                        items.mapIndexed { index, item -> item.copy(amount = provisionalAmounts[index]) }
                    }
                    val plan = planWallet(
                        era,
                        protocolParams,
                        utxos,
                        payer.paymentAddr,
                        planItems,
                        selectionMode,
                        request.isClaim,
                        withdrawals,
                        request.metadata,
                        validUntil,
                        1 + withdrawals.size,
                        isPreview = true
                    )
                    val inputs = plan?.selectedUtxos ?: utxos
                    val tokenLocked = plan?.tokenLocked ?: BigInteger.ZERO
                    val groups = addresses.indices.groupBy { addresses[it] }
                    // The planner places change after the grouped recipients; CLI determines its exact lovelace.
                    val changeLayout = if (request.isClaim) null else plan?.outputs?.drop(if (selectionMode == SelectionMode.DEFRAGMENT) 0 else groups.size)
                    val referenceSize = walletUtils.queryReferenceScriptSize(defaultHost, defaultHostConnection, magicString, socketPath, era, inputs)
                    val remainingAssets = inputs.toNativeAssetMap().toMutableMap()
                    planItems.filter { it.currency != "ada" }.forEach { item ->
                        remainingAssets[item.currency] = (remainingAssets[item.currency] ?: BigInteger.ZERO) - item.amount
                    }
                    require(remainingAssets.values.all { it.signum() >= 0 }) { "Insufficient native assets" }

                    fun minimum(
                        address: String,
                        coin: BigInteger,
                        assets: Map<String, BigInteger>
                    ): BigInteger {
                        val value = address + "+" + coin + assets.entries.joinToString("") { "+${it.value} ${it.key}" }
                        val result = defaultHostConnection.commandArgs(
                            listOf(
                                defaultHost.cardanoCliPath,
                                era,
                                "transaction",
                                "calculate-min-required-utxo",
                                "--protocol-params-file",
                                paramsPath,
                                "--tx-out",
                                value
                            )
                        )
                        return result
                            .trim()
                            .split(Regex("\\s+"))
                            .first { it.matches(Regex("[0-9]+")) }
                            .toBigInteger()
                    }

                    val changeAssets = remainingAssets.filterValues { it.signum() > 0 }
                    val tokenKeepFee = when {
                        changeAssets.isEmpty() -> BigInteger.ZERO
                        changeLayout == null -> minimum(payer.paymentAddr, BigInteger("1000000"), changeAssets)
                        else -> changeLayout.filter { it.nativeAssets.isNotEmpty() }.sumByBigInteger { output ->
                            minimum(
                                payer.paymentAddr,
                                output.lovelace,
                                output.nativeAssets.associate { asset -> "${asset.policy}.${asset.name}".trimEnd('.') to asset.amount }
                            )
                        }
                    }

                    fun outputs(fee: BigInteger): List<WalletTransactionBuilder.Output> {
                        repeat(16) {
                            val availableAda = if (request.isClaim) {
                                totalRewards
                            } else {
                                totalLovelace - tokenLocked - fee - tokenKeepFee - tokenFees.fold(BigInteger.ZERO, BigInteger::add)
                            }
                            val amounts = amounts(availableAda)
                            val nextFees = MutableList(tokenFees.size) { BigInteger.ZERO }
                            val result = groups.map { (address, indices) ->
                                val ada = indices.filter { items[it].currency == "ada" }.fold(BigInteger.ZERO) { sum, i -> sum + amounts[i] }
                                val assets = sortedMapOf<String, BigInteger>()
                                var previous = BigInteger.ZERO
                                indices.filter { items[it].currency != "ada" }.forEach { i ->
                                    val unit = items[i].currency
                                    assets[unit] = (assets[unit] ?: BigInteger.ZERO) + amounts[i]
                                    val needed = (minimum(address, ada.max(BigInteger("1000000")), assets) - ada).max(BigInteger.ZERO)
                                    nextFees[i] = needed - previous
                                    previous = needed
                                }
                                WalletTransactionBuilder.Output(address, ada + previous, nativeAssets(assets))
                            }
                            if (nextFees == tokenFees) return result
                            tokenFees.clear()
                            tokenFees.addAll(nextFees)
                        }
                        throw IllegalArgumentException("Output minimum ADA did not converge")
                    }
                    val built = transactionBuilder.build(
                        defaultHost,
                        defaultHostConnection,
                        era,
                        inputs,
                        emptyList(),
                        payer.paymentAddr,
                        withdrawals = totalRewards,
                        extraArgs = extra,
                        validUntil = validUntil,
                        protocolParameters = protocolParams,
                        protocolPath = paramsPath,
                        witnessCount = 1 + withdrawals.size,
                        bodyPath = bodyPath,
                        referenceScriptSize = referenceSize,
                        outputsForFee = if (selectionMode == SelectionMode.DEFRAGMENT) null else ::outputs,
                        changeLayout = changeLayout
                    )
                    webSocketTemplate.convertAndSend(
                        "/topic/messages",
                        SocketResponse.Success(
                            type = "calculatefee",
                            data = CalculateFeeResponse(
                                built.fee,
                                tokenFees.toList(),
                                tokenKeepFee,
                                tokenLocked,
                                request.uuid,
                                selectionMode.name,
                                inputs.size,
                                built.outputCount,
                                built.estimatedTxSize
                            )
                        )
                    )
                } finally {
                    defaultHostConnection.commandArgs(listOf("rm", "-f") + cleanup)
                }
            } catch (e: Throwable) {
                val error = "Fatal error calculating fees!"
                log.error(e) { error }
                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Error(
                        type = "calculatefee",
                        exception = SocketResponse.PublicFailure(
                            code = e.javaClass.simpleName,
                            message = "Operation failed; inspect the protected server diagnostics.",
                            operation = "calculatefee",
                            retryDisposition = "retry",
                            requestId = request.uuid,
                        )
                    )
                )
            }
        }

        @MessageMapping("/createwalletentry")
        @SendTo("/topic/messages")
        @Transactional
        fun createWalletEntry(request: CreateWalletEntryRequest): SocketResponse<String> =
            try {
                if (!walletUtils.isValidSpendingPassword(request.spendingPassword)) {
                    throw IllegalArgumentException("Invalid spending password!")
                }
                val walletEntry: WalletEntry =
                    when (request.type) {
                        "address" -> {
                            WalletEntry(
                                name = request.name,
                                type = request.type,
                                paymentAddr = request.paymentAddr
                            )
                        }

                        "payment" -> {
                            createPaymentWalletEntry(request)
                        }

                        else -> {
                            // "stake" or "pledge"
                            createStakeWalletEntry(request)
                        }
                    }

                walletRepository.save(walletEntry)
                SocketResponse.Success(type = "createwalletentry", data = "${walletEntry.name} created!")
            } catch (e: Throwable) {
                val error = "Fatal error saving wallet entry!"
                log.error(e) { error }
                SocketResponse.Error(type = "createwalletentry", exception = e)
            }

        private fun createStakeWalletEntry(request: CreateWalletEntryRequest): WalletEntry =
            nodeRepository.findDefault()?.let { defaultNode ->
                fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)?.let { genesisFile ->
                    val genesis = shelleyGenesisAdapter.fromJson(genesisFile.content)!!
                    val magicString =
                        if (genesis.networkId.equals("testnet", ignoreCase = true)) {
                            "--testnet-magic ${genesis.networkMagic}"
                        } else {
                            "--mainnet"
                        }
                    hostRepository.findByIdOrNull(defaultNode.hostId)?.let { defaultHost ->
                        val defaultHostConnection = HostConnection(defaultHost, defaultNode)
                        val socketPath = "--socket-path ${defaultHost.nodeHomePath}/${defaultNode.name}/db/socket"
                        val prefix = "/tmp/jormanager-wallet-${UUID.randomUUID()}"
                        val pskeyPath = "$prefix.pskey"
                        val pvkeyPath = "$prefix.pvkey"
                        val sskeyPath = "$prefix.sskey"
                        val svkeyPath = "$prefix.svkey"
                        val regcertPath = "$prefix.regcert"
                        try {
                            val era = cardanoRepository.getEra(defaultHost, defaultNode, magicString, socketPath)
                            val protocolParamsJson = protocolSnapshots.query(defaultHost, defaultNode, defaultHostConnection, era, magicString, socketPath)
                            val protocolParameters =
                                protocolParamsAdapter.fromJson(protocolParamsJson)
                                    ?: throw IOException("Invalid protocol params!")

                            val (pskeyContent, pvkeyContent) =
                                when {
                                    request.generateKeys -> {
                                        defaultHostConnection.command(
                                            "${defaultHost.cardanoCliPath} $era address key-gen --verification-key-file $pvkeyPath --signing-key-file $pskeyPath"
                                        )
                                        defaultHostConnection.commandArgs(listOf("chmod", "600", pskeyPath))
                                        val paymentSKey = defaultHostConnection.commandReadFile(pskeyPath)
                                        val paymentVKey = defaultHostConnection.commandReadFile(pvkeyPath)
                                        Pair(paymentSKey, paymentVKey)
                                    }

                                    request.type == "pledge" -> {
                                        Pair(null, null)
                                    }

                                    else -> {
                                        Pair(request.paymentSKey!!, request.paymentVKey!!)
                                    }
                                }

                            val savedPaymentSKeyFile =
                                pskeyContent?.let {
                                    fileRepository.save(
                                        File(
                                            name = "${request.name}.payment.skey",
                                            content = walletUtils.encryptSKeyContent(pskeyContent, request.spendingPassword)
                                        )
                                    )
                                }
                            val savedPaymentVKeyFile =
                                pvkeyContent?.let {
                                    fileRepository.save(
                                        File(
                                            name = "${request.name}.payment.vkey",
                                            content = pvkeyContent
                                        )
                                    )
                                }

                            val (sskeyContent, svkeyContent) =
                                if (request.generateKeys) {
                                    defaultHostConnection.command(
                                        "${defaultHost.cardanoCliPath} $era stake-address key-gen --verification-key-file $svkeyPath --signing-key-file $sskeyPath"
                                    )
                                    defaultHostConnection.commandArgs(listOf("chmod", "600", sskeyPath))
                                    val stakingSKey = defaultHostConnection.commandReadFile(sskeyPath)
                                    val stakingVKey = defaultHostConnection.commandReadFile(svkeyPath)
                                    Pair(stakingSKey, stakingVKey)
                                } else {
                                    Pair(request.stakingSKey!!, request.stakingVKey!!)
                                }

                            val savedStakingSKeyFile =
                                fileRepository.save(
                                    File(
                                        name = "${request.name}.staking.skey",
                                        content = walletUtils.encryptSKeyContent(sskeyContent, request.spendingPassword)
                                    )
                                )
                            val savedStakingVKeyFile =
                                fileRepository.save(
                                    File(
                                        name = "${request.name}.staking.vkey",
                                        content = svkeyContent
                                    )
                                )

                            if (pvkeyContent != null) {
                                defaultHostConnection.commandWriteFile(pvkeyPath, pvkeyContent)
                            }
                            defaultHostConnection.commandWriteFile(svkeyPath, svkeyContent)
                            val depositArg =
                                if (era.lowercase() in setOf("conway", "dijkstra")) {
                                    "--key-reg-deposit-amt ${protocolParameters.stakeAddressDeposit} "
                                } else {
                                    ""
                                }
                            defaultHostConnection
                                .command(
                                    "${defaultHost.cardanoCliPath} $era stake-address registration-certificate $depositArg--staking-verification-key-file $svkeyPath --out-file $regcertPath"
                                ).trim()
                            val regcertContent = defaultHostConnection.commandReadFile(regcertPath)
                            val savedRegcertFile =
                                fileRepository.save(
                                    File(
                                        name = "${request.name}.staking.cert",
                                        content = regcertContent
                                    )
                                )

                            val paymentAddr =
                                if (request.type == "pledge") {
                                    request.paymentAddr
                                } else {
                                    defaultHostConnection
                                        .command(
                                            "${defaultHost.cardanoCliPath} $era address build --payment-verification-key-file $pvkeyPath --staking-verification-key-file $svkeyPath $magicString"
                                        ).trim()
                                }
                            val stakingAddr =
                                defaultHostConnection
                                    .command(
                                        "${defaultHost.cardanoCliPath} $era stake-address build --staking-verification-key-file $svkeyPath $magicString"
                                    ).trim()
                            WalletEntry(
                                name = request.name,
                                type = request.type,
                                paymentAddr = paymentAddr,
                                paymentSkey = savedPaymentSKeyFile,
                                paymentVkey = savedPaymentVKeyFile,
                                stakingAddr = stakingAddr,
                                stakingSkey = savedStakingSKeyFile,
                                stakingVkey = savedStakingVKeyFile,
                                stakingRegCert = savedRegcertFile
                            )
                        } finally {
                            defaultHostConnection.commandArgs(
                                listOf("rm", "-f", pskeyPath, pvkeyPath, sskeyPath, svkeyPath, regcertPath)
                            )
                        }
                    } ?: throw IOException("Host not found for default node!")
                } ?: throw IOException("Genesis file for default node not found!")
            } ?: throw IOException("No default node!")

        private fun createPaymentWalletEntry(request: CreateWalletEntryRequest): WalletEntry =
            nodeRepository.findDefault()?.let { defaultNode ->
                fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)?.let { genesisFile ->
                    val genesis = shelleyGenesisAdapter.fromJson(genesisFile.content)!!
                    val magicString =
                        if (genesis.networkId.equals("testnet", ignoreCase = true)) {
                            "--testnet-magic ${genesis.networkMagic}"
                        } else {
                            "--mainnet"
                        }
                    hostRepository.findByIdOrNull(defaultNode.hostId)?.let { defaultHost ->
                        val defaultHostConnection = HostConnection(defaultHost, defaultNode)
                        val socketPath = "--socket-path ${defaultHost.nodeHomePath}/${defaultNode.name}/db/socket"
                        val prefix = "/tmp/jormanager-wallet-${UUID.randomUUID()}"
                        val pskeyPath = "$prefix.pskey"
                        val pvkeyPath = "$prefix.pvkey"
                        try {
                            val era = cardanoRepository.getEra(defaultHost, defaultNode, magicString, socketPath)
                            val (skeyContent, vkeyContent) =
                                if (request.generateKeys) {
                                    defaultHostConnection.command(
                                        "${defaultHost.cardanoCliPath} $era address key-gen --verification-key-file $pvkeyPath --signing-key-file $pskeyPath"
                                    )
                                    defaultHostConnection.commandArgs(listOf("chmod", "600", pskeyPath))
                                    val paymentSKey = defaultHostConnection.commandReadFile(pskeyPath)
                                    val paymentVKey = defaultHostConnection.commandReadFile(pvkeyPath)
                                    Pair(paymentSKey, paymentVKey)
                                } else {
                                    Pair(request.paymentSKey!!, request.paymentVKey!!)
                                }

                            val savedPaymentSKeyFile =
                                fileRepository.save(
                                    File(
                                        name = "${request.name}.payment.skey",
                                        content = walletUtils.encryptSKeyContent(skeyContent, request.spendingPassword)
                                    )
                                )
                            val savedPaymentVKeyFile =
                                fileRepository.save(
                                    File(
                                        name = "${request.name}.payment.vkey",
                                        content = vkeyContent
                                    )
                                )

                            defaultHostConnection.commandWriteFile(pvkeyPath, vkeyContent)
                            val paymentAddr =
                                defaultHostConnection
                                    .command(
                                        "${defaultHost.cardanoCliPath} $era address build --payment-verification-key-file $pvkeyPath $magicString"
                                    ).trim()
                            WalletEntry(
                                name = request.name,
                                type = request.type,
                                paymentAddr = paymentAddr,
                                paymentSkey = savedPaymentSKeyFile,
                                paymentVkey = savedPaymentVKeyFile
                            )
                        } finally {
                            defaultHostConnection.commandArgs(listOf("rm", "-f", pskeyPath, pvkeyPath))
                        }
                    } ?: throw IOException("Host not found for default node!")
                } ?: throw IOException("Genesis file for default node not found!")
            } ?: throw IOException("No default node!")

        @MessageMapping("/deletewalletentry")
        @SendTo("/topic/messages")
        @Transactional
        fun deleteWalletEntry(request: DeleteWalletEntryRequest): SocketResponse<String> =
            try {
                if (!walletUtils.isValidSpendingPassword(request.spendingPassword)) {
                    throw IllegalArgumentException("Invalid spending password!")
                }
                val walletEntry = walletRepository.findByIdOrNull(request.id)
                if (walletEntry != null) {
                    val now = System.currentTimeMillis()
                    val paymentSkey =
                        walletEntry.paymentSkey?.let {
                            fileRepository.save(it.copy(name = it.name + "-$now"))
                        }
                    val paymentVkey =
                        walletEntry.paymentVkey?.let {
                            fileRepository.save(it.copy(name = it.name + "-$now"))
                        }
                    val stakingRegCert =
                        walletEntry.stakingRegCert?.let {
                            fileRepository.save(it.copy(name = it.name + "-$now"))
                        }
                    val stakingSkey =
                        walletEntry.stakingSkey?.let {
                            fileRepository.save(it.copy(name = it.name + "-$now"))
                        }
                    val stakingVkey =
                        walletEntry.stakingVkey?.let {
                            fileRepository.save(it.copy(name = it.name + "-$now"))
                        }
                    walletRepository.save(
                        walletEntry.copy(
                            name = walletEntry.name + "-$now",
                            deleted = true,
                            paymentSkey = paymentSkey,
                            paymentVkey = paymentVkey,
                            stakingRegCert = stakingRegCert,
                            stakingSkey = stakingSkey,
                            stakingVkey = stakingVkey
                        )
                    )
                }
                SocketResponse.Success(type = "deletewalletentry", data = "${walletEntry?.name} deleted!")
            } catch (e: Throwable) {
                val error = "Fatal error deleting wallet entry!"
                log.error(e) { error }
                SocketResponse.Error(type = "deletewalletentry", exception = e)
            }

        @MessageMapping("/submittransaction")
        @SendTo("/topic/messages")
        fun submitTransaction(request: SubmitTransactionRequest): SocketResponse<String> =
            try {
                require(walletUtils.isValidSpendingPassword(request.spendingPassword)) { "Invalid spending password!" }
                val txId = ledgerDao.withSubmissionLock { submitWallet(request.spendingPassword, request, null) }
                SocketResponse.Success("submittransaction", "transaction succeeded: $txId")
            } catch (e: Exception) {
                log.error(e) { "Error submitting wallet transaction" }
                SocketResponse.Error("submittransaction", e)
            }

        @MessageMapping("/updatestakingaddress")
        @SendTo("/topic/messages")
        fun updateStakingAddress(request: UpdateStakingAddressRequest): SocketResponse<String> =
            try {
                require(walletUtils.isValidSpendingPassword(request.spendingPassword)) { "Invalid spending password!" }
                val txId = ledgerDao.withSubmissionLock { submitWallet(request.spendingPassword, null, request) }
                SocketResponse.Success("updatestakingaddress", "transaction succeeded: $txId")
            } catch (e: Exception) {
                log.error(e) { "Error updating stake address" }
                SocketResponse.Error("updatestakingaddress", e)
            }

        private fun submitWallet(
            password: String,
            request: SubmitTransactionRequest?,
            stakeRequest: UpdateStakingAddressRequest?
        ): String {
            fun entry(id: Long) =
                walletRepository.findByIdOrNull(id)?.takeUnless { it.deleted }
                    ?: throw IllegalArgumentException("Wallet account not found")
            val node = nodeRepository.findDefault() ?: throw IOException("No default node!")
            val host = hostRepository.findByIdOrNull(node.hostId) ?: throw IOException("Host not found!")
            val genesis = shelleyGenesisAdapter.fromJson(requireNotNull(fileRepository.findByIdOrNull(node.genesisShelleyFileId)).content)
                ?: throw IOException("Invalid genesis")
            val magic = if (genesis.networkId.equals("testnet", true)) "--testnet-magic ${genesis.networkMagic}" else "--mainnet"
            val socket = "--socket-path ${host.nodeHomePath}/${node.name}/db/socket"
            val era = cardanoRepository.getEra(host, node, magic, socket)
            val connection = HostConnection(host, node)
            val parameters = protocolSnapshots.query(host, node, connection, era, magic, socket)
            val sources = if (stakeRequest != null) listOf(entry(stakeRequest.id)) else requireNotNull(request).getEffectiveFromIds().map(::entry)
            require(sources.isNotEmpty() && sources.map { it.id }.distinct().size == sources.size)
            val payer = when {
                stakeRequest != null -> entry(stakeRequest.stakingFeesAccount)
                request!!.isClaim ->
                    request.toAccounts
                        .filter { !it.isAddress && it.account > 0 }
                        .map { entry(it.account) }
                        .firstOrNull {
                            it.paymentSkey != null && walletUtils
                                .getUtxos(host, connection, magic, socket, era, it.paymentAddr)
                                .sumByBigInteger { utxo -> utxo.lovelace } >= BigInteger("1000000") + request.txFee
                        }
                        ?: throw IOException("No Account found capable of covering the claim fee!")
                else -> sources.first()
            }
            val candidates = walletUtils.getUtxos(host, connection, magic, socket, era, payer.paymentAddr)
            require(candidates.isNotEmpty()) { "Fee payer has no available inputs" }
            val stakes = if (stakeRequest != null || request!!.isClaim) sources else emptyList()
            val prefix = "/tmp/jormanager-wallet-${UUID.randomUUID()}"
            val body = "$prefix.body"
            val signed = "$prefix.signed"
            val params = "$prefix.params"
            val cleanup = mutableListOf(body, signed, params)
            try {
                connection.commandWriteFile(params, parameters)
                val args = mutableListOf<String>()
                val withdrawals = mutableListOf<WithdrawalItem>()
                var deposit = BigInteger.ZERO
                var refund = BigInteger.ZERO
                if (stakeRequest != null) {
                    val stake = sources.single()
                    val info = walletUtils.getStakeAddressInfo(host, connection, magic, socket, era, requireNotNull(stake.stakingAddr))
                    require(stakeRequest.isRegistration == info.isEmpty()) { "Stake registration state changed" }
                    val amount = if (stakeRequest.isRegistration) {
                        json
                            .readTree(parameters)
                            .path("stakeAddressDeposit")
                            .asString()
                            .toBigInteger()
                    } else {
                        requireNotNull(info.single().stakeAddressDeposit) { "Recorded stake deposit unavailable" }
                    }
                    if (stakeRequest.isRegistration) deposit = amount else refund = amount
                    val key = "$prefix.vkey"
                    val cert = "$prefix.cert"
                    cleanup += listOf(key, cert)
                    connection.commandWriteFile(key, requireNotNull(stake.stakingVkey).content)
                    val certificate = mutableListOf(
                        host.cardanoCliPath,
                        era,
                        "stake-address",
                        if (stakeRequest.isRegistration) "registration-certificate" else "deregistration-certificate"
                    )
                    if (era == "conway" || era == "dijkstra") certificate += listOf("--key-reg-deposit-amt", amount.toString())
                    certificate += listOf("--stake-verification-key-file", key, "--out-file", cert)
                    connection.commandArgs(certificate)
                    args += listOf("--certificate-file", cert)
                } else {
                    if (request!!.isClaim) {
                        stakes.forEach { stake ->
                            val address = requireNotNull(stake.stakingAddr)
                            val reward = walletUtils.getStakeAddressInfo(host, connection, magic, socket, era, address).single().rewardAccountBalance
                            require(reward.signum() >= 0)
                            withdrawals += WithdrawalItem(address, reward)
                            args += listOf("--withdrawal", "$address+$reward")
                        }
                    }
                    if (!request.metadata.isNullOrBlank()) {
                        json.readTree(request.metadata)
                        val metadata = "$prefix.metadata"
                        cleanup += metadata
                        connection.commandWriteFile(metadata, request.metadata)
                        args += listOf("--metadata-json-file", metadata)
                    }
                }
                val selectionMode = SelectionMode.fromString(request?.selectionMode)
                val items = request
                    ?.let { transfer ->
                        transfer.toAccounts.map { item ->
                            val address = if (item.isAddress) item.address else entry(item.account).paymentAddr
                            walletLine(address, item.currency, item.amount, item.percent, transfer.isClaim && item.account == payer.id)
                        }
                    }.orEmpty()
                if (request != null) requireWalletDestinations(selectionMode, request.isClaim, sources.size, payer.paymentAddr, items)
                val outputs = if (request == null || selectionMode == SelectionMode.DEFRAGMENT) {
                    emptyList()
                } else {
                    items.indices.groupBy { items[it].address }.map { (address, indices) ->
                        var ada = BigInteger.ZERO
                        val assets = sortedMapOf<String, BigInteger>()
                        indices.forEach { index ->
                            val item = items[index]
                            val tokenFee = request.toAccounts[index].tokenFee
                            require(item.amount.signum() > 0 && tokenFee.signum() >= 0) { "Invalid output amount" }
                            if (item.currency == "ada") {
                                ada += item.amount
                            } else {
                                assets[item.currency] = (assets[item.currency] ?: BigInteger.ZERO) + item.amount
                                ada += tokenFee
                            }
                        }
                        WalletTransactionBuilder.Output(address, ada, nativeAssets(assets))
                    }
                }
                val tip = requireNotNull(queryTipAdapter.fromJson(connection.commandArgs(listOf(host.cardanoCliPath, era, "query", "tip") + magic.split(" ") + socket.split(" ") + "--output-json")))
                require(tip.slot >= 0) { "Invalid node tip" }
                val validUntil = Math.addExact(tip.slot, 21600L)
                val plan = request?.let {
                    planWallet(
                        era,
                        parameters,
                        candidates,
                        payer.paymentAddr,
                        if (selectionMode == SelectionMode.DEFRAGMENT) emptyList() else items,
                        selectionMode,
                        it.isClaim,
                        withdrawals,
                        it.metadata,
                        validUntil,
                        1 + stakes.size,
                        quotedFee = it.txFee
                    )
                }
                val inputs = if (stakeRequest != null && QueryTip(era).eraNumber >= QueryTip.ERA_BABBAGE) {
                    transactionBuilder.selectAdminInputs(candidates, payer.paymentAddr, parameters, validUntil, 1 + stakes.size, 128, deposit - refund)
                } else {
                    plan?.selectedUtxos ?: candidates
                }
                val changeLayout = if (request?.isClaim == true) null else plan?.outputs?.drop(outputs.size)
                val referenceSize = walletUtils.queryReferenceScriptSize(host, connection, magic, socket, era, inputs)
                val built = transactionBuilder.build(
                    host,
                    connection,
                    era,
                    inputs,
                    outputs,
                    payer.paymentAddr,
                    withdrawals = withdrawals.sumByBigInteger { it.lovelace },
                    deposits = deposit,
                    refunds = refund,
                    extraArgs = args,
                    validUntil = validUntil,
                    protocolParameters = parameters,
                    protocolPath = params,
                    witnessCount = 1 + stakes.size,
                    bodyPath = body,
                    referenceScriptSize = referenceSize,
                    changeLayout = changeLayout
                )
                val keys = listOf(requireNotNull(payer.paymentSkey)) + stakes.map { requireNotNull(it.stakingSkey) }
                val signing = mutableListOf(host.cardanoCliPath, era, "transaction", "sign", "--tx-body-file", body)
                keys.forEachIndexed { index, file ->
                    val path = "$prefix.$index.skey"
                    cleanup += path
                    connection.commandWriteSecret(path, walletUtils.getSKeyContent(file, password))
                    signing += listOf("--signing-key-file", path)
                }
                signing += magic.split(" ")
                signing += listOf("--out-file", signed)
                connection.commandArgs(signing)
                val envelope = connection.commandReadFile(signed)
                transactionBuilder.validateSignedEnvelopeSize(envelope, parameters)
                connection.commandArgs(listOf(host.cardanoCliPath, era, "transaction", "submit", "--tx-file", signed) + magic.split(" ") + socket.split(" "))
                ledgerDao.recordSubmitted(host, node, era, built.txId, envelope)
                return built.txId
            } finally {
                connection.commandArgs(listOf("rm", "-f") + cleanup)
            }
        }

        private fun walletLine(
            address: String,
            currency: String,
            amount: BigInteger?,
            percent: Int?,
            isFeePayerClaimAccount: Boolean
        ): WalletAccountLineItem {
            require(address.isNotBlank()) { "Invalid destination" }
            val unit = currency.trimEnd('.').lowercase()
            require(unit == "ada" || unit.matches(Regex("[0-9a-f]{56}(\\.([0-9a-f]{2}){1,32})?"))) { "Invalid asset" }
            require(percent == null || percent in 0..100) { "Invalid percentage" }
            return WalletAccountLineItem(address, address, unit, amount ?: BigInteger.ZERO, (percent ?: 0) != 0, percent ?: 0, isFeePayerClaimAccount)
        }

        private fun nativeAssets(assets: Map<String, BigInteger>): List<NativeAsset> =
            assets.map { (unit, amount) ->
                NativeAsset(unit.substringAfter('.', ""), unit.substringBefore('.'), amount)
            }

        private fun requireWalletDestinations(
            selectionMode: SelectionMode,
            isClaim: Boolean,
            sourceCount: Int,
            payerAddress: String,
            items: List<WalletAccountLineItem>
        ) {
            if (selectionMode == SelectionMode.DEFRAGMENT) {
                val selfSweep = items.singleOrNull()?.let { it.address == payerAddress && it.currency == "ada" && it.percent == 100 } == true
                require(!isClaim && sourceCount == 1 && (items.isEmpty() || selfSweep)) {
                    "Defragmentation requires a single source wallet and a 100% ADA transfer back to itself"
                }
            } else {
                require(items.isNotEmpty()) { "No destination" }
            }
        }

        private fun planWallet(
            era: String,
            rawParameters: String,
            candidates: List<Utxo>,
            payerAddress: String,
            items: List<WalletAccountLineItem>,
            selectionMode: SelectionMode,
            isClaim: Boolean,
            withdrawals: List<WithdrawalItem>,
            metadata: String?,
            validUntil: Long,
            witnessCount: Int,
            isPreview: Boolean = false,
            quotedFee: BigInteger = BigInteger.ZERO
        ): TransactionPlan? {
            val parameters = if (QueryTip(era).eraNumber >= QueryTip.ERA_BABBAGE && json.readTree(rawParameters).hasNonNull("protocolVersion")) {
                try {
                    protocolParamsAdapter.fromJson(rawParameters)
                } catch (_: JsonDataException) {
                    null
                }
            } else {
                null
            }
            if (parameters == null) {
                require(selectionMode != SelectionMode.DEFRAGMENT) { "Defragmentation requires protocol parameters supported by the wallet planner" }
                return null
            }
            return transactionPlannerService.planWalletTransaction(
                utxos = candidates,
                changeAddress = payerAddress,
                toAccountItems = items,
                protocolParameters = parameters,
                isClaim = isClaim,
                withdrawals = withdrawals,
                metadataJson = metadata,
                ttl = validUntil,
                witnessCount = witnessCount,
                selectionMode = selectionMode,
                fixedFeeOverride = quotedFee.takeIf { selectionMode == SelectionMode.STANDARD && it.signum() > 0 },
                isPreview = isPreview && selectionMode == SelectionMode.STANDARD
            )
        }
    }
