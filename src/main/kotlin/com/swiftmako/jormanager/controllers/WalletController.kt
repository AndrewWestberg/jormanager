package com.swiftmako.jormanager.controllers

import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.entities.Transaction
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
import com.swiftmako.jormanager.model.SubmitTransactionRequest
import com.swiftmako.jormanager.model.UpdateStakingAddressRequest
import com.swiftmako.jormanager.model.WalletItem
import com.swiftmako.jormanager.model.WithdrawalItem
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.TransactionRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.services.CardanoCapabilities
import com.swiftmako.jormanager.services.TransactionPlannerService
import com.swiftmako.jormanager.services.WalletAccountLineItem
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
        private val transactionRepository: TransactionRepository,
        private val queryTipAdapter: JsonAdapter<QueryTip>,
        private val shelleyGenesisAdapter: JsonAdapter<GenesisShelley>,
        private val protocolParamsAdapter: JsonAdapter<ProtocolParameters>,
        private val webSocketTemplate: SimpMessagingTemplate,
        private val cardanoRepository: CardanoRepository,
        private val cardanoCapabilities: CardanoCapabilities,
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
                        SocketResponse.Success(
                            type = "wallet",
                            data = runBlocking { walletUtils.getWalletItems(magicString, era) }
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

        internal fun calculateTxFee(request: CalculateFeeRequest) {
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
                cardanoCapabilities.requireOperation(defaultHost, defaultNode, "wallet_fee_preview")
                val protocolParamsJson =
                    defaultHostConnection
                        .command("${defaultHost.cardanoCliPath} $era query protocol-parameters $magicString $socketPath --output-json")
                        .trim()
                val protocolParameters = parseProtocolParameters(protocolParamsJson, era)

                // Load all wallet entries for multi-claim support
                val effectiveFromIds = request.getEffectiveFromIds()
                val fromWalletEntries = effectiveFromIds.map { id ->
                    walletRepository.findByIdOrNull(id)
                        ?: throw IOException("Wallet entry id $id not found!")
                }
                val fromWalletEntry = fromWalletEntries.first()

                val feePayerWalletEntry =
                    if (request.isClaim) {
                        calculateClaimRewardsFeePayer(
                            defaultHost,
                            defaultHostConnection,
                            magicString,
                            socketPath,
                            era,
                            request.toAccounts.map { it.account },
                            BigInteger.ZERO,
                        )
                    } else {
                        fromWalletEntry
                    }

                val utxos =
                    walletUtils.getUtxos(
                        defaultHost,
                        defaultHostConnection,
                        magicString,
                        socketPath,
                        era,
                        feePayerWalletEntry.paymentAddr,
                    )
                log.debug { "YYYY Utxos: $utxos" }

                val withdrawals =
                    if (request.isClaim) {
                        val fromWalletItems =
                            fromWalletEntries.map { entry ->
                                walletUtils.getWalletItem(
                                    defaultHost,
                                    defaultHostConnection,
                                    magicString,
                                    socketPath,
                                    era,
                                    entry,
                                )
                            }
                        fromWalletEntries.mapIndexed { index, entry ->
                            WithdrawalItem(
                                stakingAddress = entry.stakingAddr ?: "",
                                lovelace = fromWalletItems[index].stakingAddrLovelace ?: BigInteger.ZERO,
                            )
                        }
                    } else {
                        emptyList()
                    }

                val toAccountItems =
                    request.toAccounts.map { toAccount ->
                        val destinationEntry =
                            when {
                                toAccount.isAddress ->
                                    WalletEntry(
                                        name = "unnamed_address",
                                        type = "address",
                                        paymentAddr = toAccount.address,
                                    )

                                toAccount.account < 1L ->
                                    WalletEntry(
                                        name = "dummy_entry",
                                        type = "address",
                                        paymentAddr = "addr_test1vqwyf8g87u0x2ex9mjczrceyh65zj77fra23nfylnf4gxhqwz3dvu",
                                    )

                                else ->
                                    walletRepository.findByIdOrNull(toAccount.account)
                                        ?: throw IOException("Wallet entry id ${toAccount.account} not found!")
                            }
                        val destinationKey =
                            if (toAccount.isAddress) {
                                "addr:${toAccount.address}"
                            } else {
                                "acct:${toAccount.account}"
                            }
                        WalletAccountLineItem(
                            destinationKey = destinationKey,
                            address = destinationEntry.paymentAddr,
                            currency = toAccount.currency,
                            amount = toAccount.amount ?: BigInteger.ZERO,
                            isPercent = toAccount.type == "percent" && (toAccount.percent ?: 0) > 0,
                            percent = toAccount.percent ?: 0,
                            isFeePayerClaimAccount = request.isClaim && !toAccount.isAddress && destinationEntry.id == feePayerWalletEntry.id,
                        )
                    }

                val witnessCount = if (request.isClaim) 1 + fromWalletEntries.size else 1
                val selectionMode = SelectionMode.fromString(request.selectionMode)

                val plan =
                    transactionPlannerService.planWalletTransaction(
                        utxos = utxos,
                        changeAddress = fromWalletEntry.paymentAddr,
                        toAccountItems = toAccountItems,
                        protocolParameters = protocolParameters,
                        isClaim = request.isClaim,
                        withdrawals = withdrawals,
                        metadataJson = request.metadata,
                        witnessCount = witnessCount,
                        selectionMode = selectionMode,
                        isPreview = true,
                    )

                if (request.isClaim) {
                    // Verify fee payer still has enough to cover the calculated fee
                    calculateClaimRewardsFeePayer(
                        defaultHost,
                        defaultHostConnection,
                        magicString,
                        socketPath,
                        era,
                        request.toAccounts.map { it.account },
                        plan.fee,
                    )
                }

                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Success(
                        type = "calculatefee",
                        data =
                            CalculateFeeResponse(
                                txFee = plan.fee,
                                tokenFees = plan.tokenFees,
                                tokenKeepFee = plan.tokenKeepFee,
                                tokenLocked = plan.tokenLocked,
                                uuid = request.uuid,
                                selectionMode = plan.selectionMode.name,
                                inputCount = plan.selectedUtxos.size,
                                outputCount = plan.outputs.size,
                                estimatedTxSize = plan.estimatedTxBytes,
                            ),
                    ),
                )
            } catch (e: Throwable) {
                val error = "Fatal error calculating fees!"
                log.error(e) { error }
                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Error(type = "calculatefee", exception = e),
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

        @MessageMapping("/updatestakingaddress")
        @Transactional
        fun updateStakingAddress(request: UpdateStakingAddressRequest) {
            try {
                if (!walletUtils.isValidSpendingPassword(request.spendingPassword)) {
                    throw IllegalArgumentException("Invalid spending password!")
                }
                if (!request.isRegistration) {
                    throw IllegalStateException("Stake deregistration requires the recorded registration deposit; current protocol parameters cannot determine the refund")
                }
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
                                cardanoCapabilities.requireOperation(defaultHost, defaultNode, "wallet_stake_registration")
                                val targetNetworkKey = cardanoRepository.networkKey(defaultNode)
                                val protocolParamsJson =
                                    defaultHostConnection
                                        .command(
                                            "${defaultHost.cardanoCliPath} $era query protocol-parameters $magicString $socketPath --output-json"
                                        ).trim()
                                val protocolParameters = parseProtocolParameters(protocolParamsJson, era)

                                val feePayerAccount =
                                    walletRepository.findByIdOrNull(request.stakingFeesAccount)
                                        ?: throw IOException("Registration fees account not found!")
                                val utxos =
                                    walletUtils.getUtxos(
                                        defaultHost,
                                        defaultHostConnection,
                                        magicString,
                                        socketPath,
                                        era,
                                        feePayerAccount.paymentAddr,
                                    )
                                defaultHostConnection.commandWriteFile(
                                    "/tmp/feepayer.payment.skey",
                                    walletUtils.getSKeyContent(
                                        requireNotNull(feePayerAccount.paymentSkey),
                                        request.spendingPassword,
                                    ),
                                )

                                val queryTipString =
                                    defaultHostConnection
                                        .command("${defaultHost.cardanoCliPath} $era query tip $magicString $socketPath --output-json")
                                        .trim()
                                val ttl =
                                    queryTipAdapter.fromJson(queryTipString)?.let { it.slot + 21600 }
                                        ?: -1

                                // Register the staking address on chain.
                                val stakingAccount =
                                    walletRepository.findByIdOrNull(request.id)
                                        ?: throw IOException("Owner staking account not found!")
                                defaultHostConnection.commandWriteFile(
                                    "/tmp/staking.skey",
                                    walletUtils.getSKeyContent(
                                        requireNotNull(stakingAccount.stakingSkey),
                                        request.spendingPassword,
                                    ),
                                )
                                defaultHostConnection.commandWriteFile(
                                    "/tmp/staking.vkey",
                                    requireNotNull(stakingAccount.stakingVkey?.content),
                                )
                                val stakingWalletItem =
                                    walletUtils.getWalletItem(
                                        defaultHost,
                                        defaultHostConnection,
                                        magicString,
                                        socketPath,
                                        era,
                                        stakingAccount,
                                    )

                                if (stakingWalletItem.stakingAddrRegistered) {
                                    throw IOException("Staking Address already registered!")
                                }

                                defaultHostConnection.command(
                                    "${defaultHost.cardanoCliPath} $era stake-address registration-certificate --key-reg-deposit-amt ${protocolParameters.stakeAddressDeposit} --stake-verification-key-file /tmp/staking.vkey --out-file /tmp/staking.cert"
                                )
                                val certificate = defaultHostConnection.commandReadFile("/tmp/staking.cert")

                                val plan =
                                    transactionPlannerService.planAdminTransaction(
                                        utxos = utxos,
                                        changeAddress = feePayerAccount.paymentAddr,
                                        protocolParameters = protocolParameters,
                                        deposit = protocolParameters.stakeAddressDeposit,
                                        ttl = ttl,
                                        witnessCount = 2,
                                        extraPayloadBytes = transactionPlannerService.estimateEnvelopePayloadBytes(listOf(certificate)),
                                    )

                                val realTransaction = StringBuilder()
                                realTransaction.append("${defaultHost.cardanoCliPath} $era transaction build-raw ")
                                plan.selectedUtxos.forEach { utxo ->
                                    realTransaction.append("--tx-in ${utxo.hash}#${utxo.ix} ")
                                }
                                plan.outputs.forEach { output ->
                                    realTransaction.append("--tx-out ${output.toCliTxOutArg()} ")
                                }
                                realTransaction.append("--invalid-hereafter $ttl ")
                                realTransaction.append("--fee ${plan.fee} ")
                                realTransaction.append("--certificate /tmp/staking.cert ")
                                realTransaction.append("--out-file /tmp/transaction.txbody")
                                log.debug { "StakeKey Transaction Command: $realTransaction" }
                                defaultHostConnection.command(realTransaction.toString())

                                check(nodeRepository.findDefault()?.id == defaultNode.id && cardanoRepository.networkKey(defaultNode) == targetNetworkKey)
                                check(cardanoRepository.getEra(defaultHost, defaultNode, magicString, socketPath) == era)
                                cardanoCapabilities.requireOperation(defaultHost, defaultNode, "wallet_stake_registration")
                                defaultHostConnection.command(
                                    "${defaultHost.cardanoCliPath} $era transaction sign --tx-body-file /tmp/transaction.txbody --signing-key-file /tmp/feepayer.payment.skey --signing-key-file /tmp/staking.skey $magicString --out-file /tmp/transaction.txsigned"
                                )

                                val txSigned = defaultHostConnection.commandReadFile("/tmp/transaction.txsigned")
                                transactionPlannerService.validateSignedEnvelopeSize(txSigned, protocolParameters)

                                check(nodeRepository.findDefault()?.id == defaultNode.id && cardanoRepository.networkKey(defaultNode) == targetNetworkKey)
                                check(cardanoRepository.getEra(defaultHost, defaultNode, magicString, socketPath) == era)
                                cardanoCapabilities.requireOperation(defaultHost, defaultNode, "wallet_stake_registration")
                                defaultHostConnection.command(
                                    "${defaultHost.cardanoCliPath} $era transaction submit --tx-file /tmp/transaction.txsigned $magicString $socketPath"
                                )
                                val txid =
                                    defaultHostConnection
                                        .command(
                                            "${defaultHost.cardanoCliPath} $era transaction txid --tx-body-file /tmp/transaction.txbody --output-text"
                                        ).trim()
                                transactionRepository.save(Transaction(txid = txid))

                                webSocketTemplate.convertAndSend(
                                    "/topic/messages",
                                    SocketResponse.Success(
                                        type = "updatestakingaddress",
                                        data = "${stakingAccount.name} registration submitted. TxId: $txid"
                                    )
                                )
                            } finally {
                                // Cleanup
                                defaultHostConnection.command(
                                    "rm -f /tmp/transaction.txbody /tmp/transaction.txsigned /tmp/feepayer.payment.skey /tmp/staking.skey /tmp/staking.vkey /tmp/staking.cert"
                                )
                            }
                        } ?: throw IOException("Host not found for default node!")
                    } ?: throw IOException("Genesis file for default node not found!")
                } ?: throw IOException("Default node not found!")
            } catch (e: Throwable) {
                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Error(type = "updatestakingaddress", exception = e)
                )
                // rethrow so db transaction is rolled back
                throw RuntimeException(e)
            }
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
                            val protocolParamsJson =
                                defaultHostConnection
                                    .command(
                                        "${defaultHost.cardanoCliPath} $era query protocol-parameters $magicString $socketPath --output-json"
                                    ).trim()
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
        @Synchronized
        fun submitTransaction(request: SubmitTransactionRequest) {
            try {
                log.debug { "submitTransaction request: $request" }
                if (!walletUtils.isValidSpendingPassword(request.spendingPassword)) {
                    throw IllegalArgumentException("Invalid spending password!")
                }

                val defaultNode = nodeRepository.findDefault() ?: throw IOException("No default node!")
                val genesisFile =
                    fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)
                        ?: throw IOException("Genesis file for default node not found!")
                val genesis =
                    shelleyGenesisAdapter.fromJson(genesisFile.content)
                        ?: throw IOException("Unable to parse genesis json")
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
                try {
                    val era = cardanoRepository.getEra(defaultHost, defaultNode, magicString, socketPath)
                    val operation = if (request.isClaim) "wallet_reward_withdrawal" else "wallet_send"
                    cardanoCapabilities.requireOperation(defaultHost, defaultNode, operation)
                    val targetNetworkKey = cardanoRepository.networkKey(defaultNode)
                    val protocolParamsJson =
                        defaultHostConnection
                            .command(
                                "${defaultHost.cardanoCliPath} $era query protocol-parameters $magicString $socketPath --output-json"
                            ).trim()
                    val protocolParameters = parseProtocolParameters(protocolParamsJson, era)

                    // Load all wallet entries for multi-claim support
                    val effectiveFromIds = request.getEffectiveFromIds()
                    val fromWalletEntries = effectiveFromIds.map { id ->
                        walletRepository.findByIdOrNull(id)
                            ?: throw IOException("Wallet entry id $id not found!")
                    }
                    val fromWalletEntry = fromWalletEntries.first()
                    val feePayerWalletEntry =
                        if (request.isClaim) {
                            calculateClaimRewardsFeePayer(
                                defaultHost,
                                defaultHostConnection,
                                magicString,
                                socketPath,
                                era,
                                request.toAccounts.filter { !it.isAddress }.map { it.account },
                                request.txFee,
                            )
                        } else {
                            fromWalletEntry
                        }

                    val utxos =
                        walletUtils.getUtxos(
                            defaultHost,
                            defaultHostConnection,
                            magicString,
                            socketPath,
                            era,
                            feePayerWalletEntry.paymentAddr,
                        )
                    log.debug { "XXXX UTXOS: $utxos" }

                    val fromWalletItems =
                        if (request.isClaim) {
                            fromWalletEntries.map { entry ->
                                walletUtils.getWalletItem(
                                    defaultHost,
                                    defaultHostConnection,
                                    magicString,
                                    socketPath,
                                    era,
                                    entry,
                                )
                            }
                        } else {
                            emptyList()
                        }

                    val withdrawals =
                        if (request.isClaim) {
                            fromWalletEntries.mapIndexed { index, entry ->
                                WithdrawalItem(
                                    stakingAddress = entry.stakingAddr ?: "",
                                    lovelace = fromWalletItems[index].stakingAddrLovelace ?: BigInteger.ZERO,
                                )
                            }
                        } else {
                            emptyList()
                        }

                    val queryTipString =
                        defaultHostConnection
                            .command("${defaultHost.cardanoCliPath} $era query tip $magicString $socketPath --output-json")
                            .trim()
                    val ttl = queryTipAdapter.fromJson(queryTipString)?.let { it.slot + 21600 } ?: -1

                    val toAccountItems =
                        request.toAccounts.map { toAccount ->
                            val destinationEntry =
                                when {
                                    toAccount.isAddress ->
                                        WalletEntry(
                                            name = "unnamed_address",
                                            type = "address",
                                            paymentAddr = toAccount.address,
                                        )

                                    else ->
                                        walletRepository.findByIdOrNull(toAccount.account)
                                            ?: throw IOException("Wallet entry id ${toAccount.account} not found!")
                                }
                            val destinationKey =
                                if (toAccount.isAddress) {
                                    "addr:${toAccount.address}"
                                } else {
                                    "acct:${toAccount.account}"
                                }
                            WalletAccountLineItem(
                                destinationKey = destinationKey,
                                address = destinationEntry.paymentAddr,
                                currency = toAccount.currency,
                                amount = toAccount.amount ?: BigInteger.ZERO,
                                isPercent = (toAccount.percent ?: 0) > 0,
                                percent = toAccount.percent ?: 0,
                                isFeePayerClaimAccount =
                                    request.isClaim && !toAccount.isAddress && destinationEntry.id == feePayerWalletEntry.id,
                            )
                        }

                    val witnessCount = if (request.isClaim) 1 + fromWalletEntries.size else 1
                    val selectionMode = SelectionMode.fromString(request.selectionMode)

                    val plan =
                        transactionPlannerService.planWalletTransaction(
                            utxos = utxos,
                            changeAddress = fromWalletEntry.paymentAddr,
                            toAccountItems = toAccountItems,
                            protocolParameters = protocolParameters,
                            isClaim = request.isClaim,
                            withdrawals = withdrawals,
                            metadataJson = request.metadata,
                            ttl = ttl,
                            witnessCount = witnessCount,
                            selectionMode = selectionMode,
                            fixedFeeOverride = request.txFee.takeIf { it > BigInteger.ZERO },
                            isPreview = false,
                        )

                    val transaction = StringBuilder()
                    transaction.append("${defaultHost.cardanoCliPath} $era transaction build-raw ")
                    plan.selectedUtxos.forEach { utxo ->
                        transaction.append("--tx-in ${utxo.hash}#${utxo.ix} ")
                    }
                    plan.outputs.forEach { output ->
                        transaction.append("--tx-out ${output.toCliTxOutArg()} ")
                    }
                    transaction.append("--invalid-hereafter $ttl ")
                    transaction.append("--fee ${plan.fee} ")

                    if (!request.metadata.isNullOrBlank()) {
                        defaultHostConnection.commandWriteFile("/tmp/metadata.json", request.metadata)
                        transaction.append("--metadata-json-file /tmp/metadata.json ")
                    }

                    if (request.isClaim) {
                        fromWalletItems.forEachIndexed { index, item ->
                            val entry = fromWalletEntries[index]
                            transaction.append("--withdrawal ${entry.stakingAddr}+${item.stakingAddrLovelace} ")
                        }
                    }
                    transaction.append("--out-file /tmp/transaction.txbody")

                    // build the transaction
                    log.debug { "transaction: $transaction" }
                    defaultHostConnection.command(transaction.toString())

                    check(nodeRepository.findDefault()?.id == defaultNode.id && cardanoRepository.networkKey(defaultNode) == targetNetworkKey)
                    check(cardanoRepository.getEra(defaultHost, defaultNode, magicString, socketPath) == era)
                    cardanoCapabilities.requireOperation(defaultHost, defaultNode, operation)
                    // sign the transaction
                    feePayerWalletEntry.paymentSkey?.let { skey ->
                        val skeyContent = walletUtils.getSKeyContent(skey, request.spendingPassword)
                        defaultHostConnection.commandWriteFile("/tmp/signing.skey", skeyContent)
                    } ?: throw IllegalArgumentException("Couldn't find payment skey for transaction")

                    if (request.isClaim) {
                        val stakingKeyFiles = mutableListOf<String>()
                        fromWalletEntries.forEachIndexed { index, entry ->
                            entry.stakingSkey?.let { skey ->
                                val skeyContent = walletUtils.getSKeyContent(skey, request.spendingPassword)
                                val keyFile = "/tmp/staking_signing_$index.skey"
                                defaultHostConnection.commandWriteFile(keyFile, skeyContent)
                                stakingKeyFiles.add(keyFile)
                            } ?: throw IllegalArgumentException("Couldn't find staking skey for wallet entry ${entry.name}")
                        }

                        val stakingKeyParams = stakingKeyFiles.joinToString(" ") { "--signing-key-file $it" }
                        defaultHostConnection.command(
                            "${defaultHost.cardanoCliPath} $era transaction sign --tx-body-file /tmp/transaction.txbody --signing-key-file /tmp/signing.skey $stakingKeyParams $magicString --out-file /tmp/transaction.txsigned"
                        )
                    } else {
                        defaultHostConnection.command(
                            "${defaultHost.cardanoCliPath} $era transaction sign --tx-body-file /tmp/transaction.txbody --signing-key-file /tmp/signing.skey $magicString --out-file /tmp/transaction.txsigned"
                        )
                    }
                    val txSigned = defaultHostConnection.commandReadFile("/tmp/transaction.txsigned")
                    transactionPlannerService.validateSignedEnvelopeSize(txSigned, protocolParameters)

                    check(nodeRepository.findDefault()?.id == defaultNode.id && cardanoRepository.networkKey(defaultNode) == targetNetworkKey)
                    check(cardanoRepository.getEra(defaultHost, defaultNode, magicString, socketPath) == era)
                    cardanoCapabilities.requireOperation(defaultHost, defaultNode, operation)

                    defaultHostConnection.command(
                        "${defaultHost.cardanoCliPath} $era transaction submit --tx-file /tmp/transaction.txsigned $magicString $socketPath"
                    )
                    val txid = defaultHostConnection.command(
                        "${defaultHost.cardanoCliPath} $era transaction txid --tx-body-file /tmp/transaction.txbody --output-text"
                    ).trim()
                    transactionRepository.save(Transaction(txid = txid))
                    webSocketTemplate.convertAndSend(
                        "/topic/messages",
                        SocketResponse.Success(type = "submittransaction", data = "transaction submitted: $txid")
                    )
                } finally {
                    defaultHostConnection.command(
                        "rm -f /tmp/signing.skey /tmp/staking_signing_*.skey /tmp/transaction.txbody /tmp/transaction.txsigned /tmp/metadata.json"
                    )
                }
            } catch (e: Throwable) {
                val error = "Fatal error submitting transaction!"
                log.error(e) { error }
                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Error(type = "submittransaction", exception = e)
                )
                // rethrow so db transaction is rolled back
                throw RuntimeException(e)
            }
        }

        /**
         * The first account with at least 1 Ada (1m lovelace) is the fee payer
         */
        private fun calculateClaimRewardsFeePayer(
            host: Host,
            hostConnection: HostConnection,
            magicString: String,
            socketPath: String,
            era: String,
            toAccounts: List<Long?>,
            txFee: BigInteger,
        ): WalletEntry {
            toAccounts.filterNotNull().forEach { id ->
                if (id > 0) {
                    walletRepository.findByIdOrNull(id)?.let { walletEntry ->
                        if (walletEntry.paymentSkey != null) {
                            val lovelace =
                                walletUtils
                                    .getUtxos(host, hostConnection, magicString, socketPath, era, walletEntry.paymentAddr)
                                    .sumByBigInteger { it.lovelace }
                            if (lovelace >= BigInteger("1000000") + txFee) {
                                return walletEntry
                            }
                        }
                    } ?: throw IOException("Wallet entry id $id not found!")
                }
            }
            throw IOException("No Account found capable of covering the claim fee!")
        }

        private fun parseProtocolParameters(protocolParamsJson: String, era: String): ProtocolParameters =
            if (era == "conway" && (protocolParamsJson.isBlank() || protocolParamsJson == "{}")) {
                ProtocolParameters.conwayDefaults()
            } else {
                protocolParamsAdapter.fromJson(protocolParamsJson)
                    ?: throw IOException("Invalid protocol params!")
            }
    }
