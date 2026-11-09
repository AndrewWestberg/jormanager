package com.swiftmako.jormanager.controllers

import com.squareup.moshi.JsonAdapter
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
import com.swiftmako.jormanager.model.WalletItem
import com.swiftmako.jormanager.model.SubmitTransactionRequest
import com.swiftmako.jormanager.model.UpdateStakingAddressRequest
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.model.toNativeAssetMap
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.ProtocolParametersRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.services.WalletTransactionBuilder
import com.swiftmako.jormanager.model.NativeAsset
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.utils.CborSpans
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
                val candidates = if (request.isClaim) request.toAccounts.mapNotNull { it.account.takeIf { id -> id > 0 } }
                    .distinct().map { id -> walletRepository.findByIdOrNull(id)?.takeUnless { it.deleted }
                        ?: throw IOException("Wallet entry id $id not found!") }.filter { it.paymentSkey != null }
                    else listOf(entries.first())
                val candidateUtxos = candidates.associate { it.id to walletUtils.getUtxos(defaultHost, defaultHostConnection, magicString, socketPath, era, it.paymentAddr) }
                val payer = if (request.isClaim) candidates.firstOrNull {
                    candidateUtxos[it.id].orEmpty().sumByBigInteger { utxo -> utxo.lovelace } >= BigInteger("1000000")
                } ?: throw IOException("No Account found capable of covering the claim fee!") else entries.first()
                val utxos = candidateUtxos[payer.id].orEmpty()
                require(utxos.isNotEmpty()) { "Fee payer has no available inputs" }
                val referenceSize = walletUtils.queryReferenceScriptSize(defaultHost, defaultHostConnection, magicString, socketPath, era, utxos)
                val rewards = if (request.isClaim) entries.map { entry ->
                    walletUtils.getWalletItem(defaultHost, defaultHostConnection, magicString, socketPath, era, entry)
                        .stakingAddrLovelace ?: BigInteger.ZERO
                } else emptyList()
                val totalRewards = rewards.fold(BigInteger.ZERO, BigInteger::add)
                val addresses = request.toAccounts.map { destination ->
                    if (destination.isAddress) destination.address else
                        walletRepository.findByIdOrNull(destination.account)?.takeUnless { it.deleted }?.paymentAddr
                            ?: throw IOException("Destination account not found")
                }
                require(addresses.isNotEmpty() && addresses.all { it.isNotBlank() }) { "No destination" }
                val prefix = "/tmp/jormanager-fee-${UUID.randomUUID()}"
                val paramsPath = "$prefix.params"
                val bodyPath = "$prefix.body"
                val metadataPath = "$prefix.metadata"
                val cleanup = mutableListOf(paramsPath, bodyPath)
                val tokenFees = MutableList(request.toAccounts.size) { BigInteger.ZERO }
                var tokenKeepFee = BigInteger.ZERO
                try {
                    defaultHostConnection.commandWriteFile(paramsPath, protocolParams)
                    val extra = mutableListOf<String>()
                    if (!request.metadata.isNullOrBlank()) {
                        json.readTree(request.metadata)
                        defaultHostConnection.commandWriteFile(metadataPath, request.metadata)
                        cleanup += metadataPath
                        extra += listOf("--metadata-json-file", metadataPath)
                    }
                    if (request.isClaim) entries.forEachIndexed { index, entry ->
                        extra += listOf("--withdrawal", "${requireNotNull(entry.stakingAddr)}+${rewards[index]}")
                    }
                    val tip = requireNotNull(queryTipAdapter.fromJson(defaultHostConnection.commandArgs(
                        listOf(defaultHost.cardanoCliPath, era, "query", "tip") + magicString.split(" ") +
                            socketPath.split(" ") + "--output-json")))
                    require(tip.slot >= 0) { "Invalid node tip" }
                    val sourceAssets = utxos.toNativeAssetMap()
                    fun minimum(address: String, coin: BigInteger, assets: Map<String, BigInteger>): BigInteger {
                        val value = address + "+" + coin + assets.entries.joinToString("") { "+${it.value} ${it.key}" }
                        val result = defaultHostConnection.commandArgs(listOf(defaultHost.cardanoCliPath, era, "transaction",
                            "calculate-min-required-utxo", "--protocol-params-file", paramsPath, "--tx-out", value))
                        return result.trim().split(Regex("\\s+")).first { it.matches(Regex("[0-9]+")) }.toBigInteger()
                    }
                    fun outputs(fee: BigInteger): List<WalletTransactionBuilder.Output> {
                        repeat(16) {
                            val remaining = sourceAssets.toMutableMap()
                            remaining["ada"] = if (request.isClaim) totalRewards else
                                utxos.sumByBigInteger { it.lovelace } - fee - tokenKeepFee - tokenFees.fold(BigInteger.ZERO, BigInteger::add)
                            val percentages = mutableMapOf<String, Int>()
                            val amounts = request.toAccounts.map { item ->
                                val currency = item.currency.trimEnd('.')
                                val amount = if (item.percent == null) {
                                    percentages[currency] = 0
                                    item.amount ?: BigInteger.ZERO
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
                            val groups = addresses.indices.groupBy { addresses[it] }
                            val nextFees = MutableList(tokenFees.size) { BigInteger.ZERO }
                            val result = groups.map { (address, indices) ->
                                val ada = indices.filter { request.toAccounts[it].currency == "ada" }.fold(BigInteger.ZERO) { sum, i -> sum + amounts[i] }
                                val assets = sortedMapOf<String, BigInteger>()
                                var previous = BigInteger.ZERO
                                indices.filter { request.toAccounts[it].currency != "ada" }.forEach { i ->
                                    val unit = request.toAccounts[i].currency.trimEnd('.')
                                    require(unit.matches(Regex("[0-9a-fA-F]{56}(\\.[0-9a-fA-F]{0,64})?"))) { "Invalid asset" }
                                    assets[unit] = (assets[unit] ?: BigInteger.ZERO) + amounts[i]
                                    val needed = (minimum(address, ada.max(BigInteger("1000000")), assets) - ada).max(BigInteger.ZERO)
                                    nextFees[i] = needed - previous
                                    previous = needed
                                }
                                WalletTransactionBuilder.Output(address, ada + previous, assets.map { (unit, value) ->
                                    val parts = unit.split(".", limit = 2)
                                    NativeAsset(parts.getOrElse(1) { "" }, parts[0], value)
                                })
                            }
                            val changeAssets = remaining.filter { it.key != "ada" && it.value.signum() > 0 }
                            require(remaining.filterKeys { it != "ada" }.values.all { it.signum() >= 0 }) { "Insufficient native assets" }
                            val nextKeep = if (changeAssets.isEmpty()) BigInteger.ZERO else
                                minimum(payer.paymentAddr, BigInteger("1000000"), changeAssets)
                            if (nextFees == tokenFees && nextKeep == tokenKeepFee) return result
                            tokenFees.clear(); tokenFees.addAll(nextFees); tokenKeepFee = nextKeep
                        }
                        throw IllegalArgumentException("Output minimum ADA did not converge")
                    }
                    val built = transactionBuilder.build(defaultHost, defaultHostConnection, era, utxos, emptyList(), payer.paymentAddr,
                        withdrawals = totalRewards, extraArgs = extra, validUntil = Math.addExact(tip.slot, 21600L),
                        protocolParameters = protocolParams, protocolPath = paramsPath,
                        witnessCount = 1 + if (request.isClaim) entries.size else 0, bodyPath = bodyPath,
                        referenceScriptSize = referenceSize, outputsForFee = ::outputs)
                    val body = CborSpans.parse(json.readTree(built.textEnvelope).path("cborHex").asString().hexToByteArray()).array().first()
                    val lovelace = body.entries().single { it.first.integer() == BigInteger.TWO }.second.integer()
                    webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success(type = "calculatefee",
                        data = CalculateFeeResponse(lovelace, tokenFees.toList(), tokenKeepFee, BigInteger.ZERO, request.uuid)))
                } finally {
                    defaultHostConnection.commandArgs(listOf("rm", "-f") + cleanup)
                }

            } catch (e: Throwable) {
                val error = "Fatal error calculating fees!"
                log.error(e) { error }
                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Error(type = "calculatefee", exception = e)
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
                        try {
                            val era = cardanoRepository.getEra(defaultHost, defaultNode, magicString, socketPath)
                            val protocolParamsJson = protocolSnapshots.query(defaultHost, defaultNode, defaultHostConnection, era, magicString, socketPath)
                            defaultHostConnection.commandWriteFile("/tmp/protocol-parameters.json", protocolParamsJson)
                            val protocolParameters =
                                protocolParamsAdapter.fromJson(protocolParamsJson)
                                    ?: throw IOException("Invalid protocol params!")

                            val (pskeyContent, pvkeyContent) =
                                when {
                                    request.generateKeys -> {
                                        defaultHostConnection.command(
                                            "${defaultHost.cardanoCliPath} $era address key-gen --verification-key-file /tmp/jormanager-pvkey --signing-key-file /tmp/jormanager-pskey"
                                        )
                                        val paymentSKey = defaultHostConnection.commandReadFile("/tmp/jormanager-pskey")
                                        val paymentVKey = defaultHostConnection.commandReadFile("/tmp/jormanager-pvkey")
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
                                        "${defaultHost.cardanoCliPath} $era stake-address key-gen --verification-key-file /tmp/jormanager-svkey --signing-key-file /tmp/jormanager-sskey"
                                    )
                                    val stakingSKey = defaultHostConnection.commandReadFile("/tmp/jormanager-sskey")
                                    val stakingVKey = defaultHostConnection.commandReadFile("/tmp/jormanager-svkey")
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
                                defaultHostConnection.commandWriteFile("/tmp/jormanager-pvkey", pvkeyContent)
                            }
                            defaultHostConnection.commandWriteFile("/tmp/jormanager-svkey", svkeyContent)
                            defaultHostConnection
                                .command(
                                    "${defaultHost.cardanoCliPath} $era stake-address registration-certificate --key-reg-deposit-amt ${protocolParameters.stakeAddressDeposit} --staking-verification-key-file /tmp/jormanager-svkey --out-file /tmp/jormanager-regcert"
                                ).trim()
                            val regcertContent = defaultHostConnection.commandReadFile("/tmp/jormanager-regcert")
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
                                            "${defaultHost.cardanoCliPath} $era address build --payment-verification-key-file /tmp/jormanager-pvkey --staking-verification-key-file /tmp/jormanager-svkey $magicString"
                                        ).trim()
                                }
                            val stakingAddr =
                                defaultHostConnection
                                    .command(
                                        "${defaultHost.cardanoCliPath} $era stake-address build --staking-verification-key-file /tmp/jormanager-svkey $magicString"
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
                            defaultHostConnection.command(
                                "rm -f /tmp/jormanager-pskey /tmp/jormanager-pvkey /tmp/jormanager-sskey /tmp/jormanager-svkey /tmp/jormanager-regcert"
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
                        try {
                            val era = cardanoRepository.getEra(defaultHost, defaultNode, magicString, socketPath)
                            val (skeyContent, vkeyContent) =
                                if (request.generateKeys) {
                                    defaultHostConnection.command(
                                        "${defaultHost.cardanoCliPath} $era address key-gen --verification-key-file /tmp/jormanager-pvkey --signing-key-file /tmp/jormanager-pskey"
                                    )
                                    val paymentSKey = defaultHostConnection.commandReadFile("/tmp/jormanager-pskey")
                                    val paymentVKey = defaultHostConnection.commandReadFile("/tmp/jormanager-pvkey")
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

                            defaultHostConnection.commandWriteFile("/tmp/jormanager-pvkey", vkeyContent)
                            val paymentAddr =
                                defaultHostConnection
                                    .command(
                                        "${defaultHost.cardanoCliPath} $era address build --payment-verification-key-file /tmp/jormanager-pvkey $magicString"
                                    ).trim()
                            WalletEntry(
                                name = request.name,
                                type = request.type,
                                paymentAddr = paymentAddr,
                                paymentSkey = savedPaymentSKeyFile,
                                paymentVkey = savedPaymentVKeyFile
                            )
                        } finally {
                            defaultHostConnection.command("rm -f /tmp/jormanager-pskey /tmp/jormanager-pvkey")
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
        fun submitTransaction(request: SubmitTransactionRequest): SocketResponse<String> = try {
            require(walletUtils.isValidSpendingPassword(request.spendingPassword)) { "Invalid spending password!" }
            val txId = ledgerDao.withSubmissionLock { submitWallet(request.spendingPassword, request, null) }
            SocketResponse.Success("submittransaction", "transaction succeeded: $txId")
        } catch (e: Exception) {
            log.error(e) { "Error submitting wallet transaction" }
            SocketResponse.Error("submittransaction", e)
        }

        @MessageMapping("/updatestakingaddress")
        @SendTo("/topic/messages")
        fun updateStakingAddress(request: UpdateStakingAddressRequest): SocketResponse<String> = try {
            require(walletUtils.isValidSpendingPassword(request.spendingPassword)) { "Invalid spending password!" }
            val txId = ledgerDao.withSubmissionLock { submitWallet(request.spendingPassword, null, request) }
            SocketResponse.Success("updatestakingaddress", "transaction succeeded: $txId")
        } catch (e: Exception) {
            log.error(e) { "Error updating stake address" }
            SocketResponse.Error("updatestakingaddress", e)
        }

        private fun submitWallet(password: String, request: SubmitTransactionRequest?, stakeRequest: UpdateStakingAddressRequest?): String {
            fun entry(id: Long) = walletRepository.findByIdOrNull(id)?.takeUnless { it.deleted }
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
                request!!.isClaim -> request.toAccounts.filter { !it.isAddress && it.account > 0 }.map { entry(it.account) }
                    .firstOrNull { it.paymentSkey != null && walletUtils.getUtxos(host, connection, magic, socket, era, it.paymentAddr)
                        .sumByBigInteger { utxo -> utxo.lovelace } >= BigInteger("1000000") + request.txFee }
                    ?: throw IOException("No Account found capable of covering the claim fee!")
                else -> sources.first()
            }
            val inputs = walletUtils.getUtxos(host, connection, magic, socket, era, payer.paymentAddr)
            require(inputs.isNotEmpty()) { "Fee payer has no available inputs" }
            val stakes = if (stakeRequest != null || request!!.isClaim) sources else emptyList()
            val prefix = "/tmp/jormanager-wallet-${UUID.randomUUID()}"
            val body = "$prefix.body"
            val signed = "$prefix.signed"
            val params = "$prefix.params"
            val cleanup = mutableListOf(body, signed, params)
            try {
                connection.commandWriteFile(params, parameters)
                val args = mutableListOf<String>()
                var withdrawals = BigInteger.ZERO
                var deposit = BigInteger.ZERO
                var refund = BigInteger.ZERO
                if (stakeRequest != null) {
                    val stake = sources.single()
                    val info = walletUtils.getStakeAddressInfo(host, connection, magic, socket, era, requireNotNull(stake.stakingAddr))
                    require(stakeRequest.isRegistration == info.isEmpty()) { "Stake registration state changed" }
                    val amount = if (stakeRequest.isRegistration) json.readTree(parameters).path("stakeAddressDeposit").asString().toBigInteger()
                        else requireNotNull(info.single().stakeAddressDeposit) { "Recorded stake deposit unavailable" }
                    if (stakeRequest.isRegistration) deposit = amount else refund = amount
                    val key = "$prefix.vkey"
                    val cert = "$prefix.cert"
                    cleanup += listOf(key, cert)
                    connection.commandWriteFile(key, requireNotNull(stake.stakingVkey).content)
                    val certificate = mutableListOf(host.cardanoCliPath, era, "stake-address",
                        if (stakeRequest.isRegistration) "registration-certificate" else "deregistration-certificate")
                    if (era == "conway" || era == "dijkstra") certificate += listOf("--key-reg-deposit-amt", amount.toString())
                    certificate += listOf("--stake-verification-key-file", key, "--out-file", cert)
                    connection.commandArgs(certificate)
                    args += listOf("--certificate-file", cert)
                } else {
                    if (request!!.isClaim) stakes.forEach { stake ->
                        val reward = walletUtils.getStakeAddressInfo(host, connection, magic, socket, era, requireNotNull(stake.stakingAddr)).single().rewardAccountBalance
                        require(reward.signum() >= 0)
                        withdrawals += reward
                        args += listOf("--withdrawal", "${stake.stakingAddr}+$reward")
                    }
                    if (!request.metadata.isNullOrBlank()) {
                        json.readTree(request.metadata)
                        val metadata = "$prefix.metadata"
                        cleanup += metadata
                        connection.commandWriteFile(metadata, request.metadata)
                        args += listOf("--metadata-json-file", metadata)
                    }
                }
                val outputs = request?.toAccounts?.groupBy { if (it.isAddress) it.address else entry(it.account).paymentAddr }?.map { (address, items) ->
                    require(address.isNotBlank())
                    var ada = BigInteger.ZERO
                    val assets = sortedMapOf<String, BigInteger>()
                    items.forEach { item ->
                        val amount = requireNotNull(item.amount)
                        require(amount.signum() > 0 && item.tokenFee.signum() >= 0) { "Invalid output amount" }
                        if (item.currency == "ada") ada += amount else {
                            val unit = item.currency.trimEnd('.')
                            require(unit.matches(Regex("[0-9a-fA-F]{56}(\\.[0-9a-fA-F]{0,64})?"))) { "Invalid asset" }
                            assets[unit] = (assets[unit] ?: BigInteger.ZERO) + amount
                            ada += item.tokenFee
                        }
                    }
                    WalletTransactionBuilder.Output(address, ada, assets.map { (unit, amount) ->
                        val parts = unit.split(".", limit = 2)
                        NativeAsset(parts.getOrElse(1) { "" }, parts[0], amount)
                    })
                } ?: emptyList()
                if (request != null) require(outputs.isNotEmpty()) { "No destination" }
                val tip = requireNotNull(queryTipAdapter.fromJson(connection.commandArgs(
                    listOf(host.cardanoCliPath, era, "query", "tip") + magic.split(" ") + socket.split(" ") + "--output-json")))
                require(tip.slot >= 0) { "Invalid node tip" }
                val referenceSize = walletUtils.queryReferenceScriptSize(host, connection, magic, socket, era, inputs)
                val built = transactionBuilder.build(host, connection, era, inputs, outputs, payer.paymentAddr,
                    withdrawals = withdrawals, deposits = deposit, refunds = refund, extraArgs = args,
                    validUntil = Math.addExact(tip.slot, 21600L), protocolParameters = parameters, protocolPath = params,
                    witnessCount = 1 + stakes.size, bodyPath = body, referenceScriptSize = referenceSize)
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
                require(json.readTree(envelope).path("cborHex").asString().isNotBlank()) { "Invalid signed transaction" }
                connection.commandArgs(listOf(host.cardanoCliPath, era, "transaction", "submit", "--tx-file", signed) + magic.split(" ") + socket.split(" "))
                ledgerDao.recordSubmitted(host, node, era, built.txId, envelope)
                return built.txId
            } finally {
                connection.commandArgs(listOf("rm", "-f") + cleanup)
            }
        }
    }
