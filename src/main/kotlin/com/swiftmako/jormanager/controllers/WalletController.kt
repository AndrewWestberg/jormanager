package com.swiftmako.jormanager.controllers

import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.*
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.ktx.sumByBigInteger
import com.swiftmako.jormanager.model.*
import com.swiftmako.jormanager.repositories.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_SINGLETON
import org.springframework.context.annotation.Scope
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

@Controller
@Scope(SCOPE_SINGLETON)
class WalletController @Autowired constructor(
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
) : CoroutineScope {

    private val log by lazy { LoggerFactory.getLogger(WalletController::class.java) }

    private val debounceFlow = MutableStateFlow<CalculateFeeRequest?>(null)

    override val coroutineContext: CoroutineContext = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    init {
        launch {
            debounceFlow.filterNotNull().collect { request -> calculateTxFee(request) }
        }
    }

    @MessageMapping("/wallet")
    @SendTo("/topic/messages")
    fun getWalletEntries(): SocketResponse<List<WalletItem>> {
        return try {
            nodeRepository.findDefault()?.let { defaultNode ->
                fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)?.let { genesisFile ->
                    val genesis = shelleyGenesisAdapter.fromJson(genesisFile.content)!!
                    val magicString = if (genesis.networkId.equals("testnet", ignoreCase = true)) {
                        "--testnet-magic ${genesis.networkMagic}"
                    } else {
                        "--mainnet"
                    }
                    SocketResponse.Success(type = "wallet", data = walletUtils.getWalletItems(magicString))
                } ?: throw IOException("Error finding shelley genesis file!")
            } ?: throw IOException("Error finding default node!")
        } catch (e: Throwable) {
            val error = "Fatal error getting wallet entries!"
            log.error(error, e)
            SocketResponse.Error(type = "wallet", exception = e)
        }
    }

    @MessageMapping("/calculatefee")
    fun receiveCalculateTxFeeRequest(request: CalculateFeeRequest) {
        debounceFlow.value = request
    }

    private fun calculateTxFee(request: CalculateFeeRequest) {
        try {
            val defaultNode = nodeRepository.findDefault() ?: throw IOException("No default node!")
            val genesisFile = fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)
                ?: throw IOException("Genesis file for default node not found!")
            val genesis = shelleyGenesisAdapter.fromJson(genesisFile.content)!!
            val magicString = if (genesis.networkId.equals("testnet", ignoreCase = true)) {
                "--testnet-magic ${genesis.networkMagic}"
            } else {
                "--mainnet"
            }
            val defaultHost = hostRepository.findByIdOrNull(defaultNode.hostId)
                ?: throw IOException("Host not found for default node!")
            val defaultHostConnection = HostConnection(defaultHost, defaultNode)
            val protocolParams =
                defaultHostConnection.command("${defaultHost.cardanoCliPath} query protocol-parameters $magicString")
                    .trim()
            defaultHostConnection.commandWriteFile(
                "/tmp/protocol-parameters-${genesis.networkMagic}.json",
                protocolParams
            )

            val fromWalletEntry = walletRepository.findByIdOrNull(request.fromId)
                ?: throw IOException("Wallet entry id ${request.fromId} not found!")

            val feePayerWalletEntry = if (request.isClaim) {
                calculateClaimRewardsFeePayer(
                    defaultHost,
                    defaultHostConnection,
                    magicString,
                    request.toAccounts.map { it.account },
                    BigInteger.ZERO
                )
            } else {
                fromWalletEntry
            }

            val dummyTxFee = "1000000".toBigInteger() // 1 ada
            val dummyTokenKeepFee = "1500000".toBigInteger() // 1.5 ada
            val baseAmount = sortedMapOf<String, BigInteger>()
            val utxos = walletUtils.getUtxos(
                defaultHost,
                defaultHostConnection,
                magicString,
                feePayerWalletEntry.paymentAddr
            )
            val transaction = StringBuilder()
            transaction.append("${defaultHost.cardanoCliPath} transaction build-raw ")
            val utxosToSpend = mutableListOf<Utxo>()
            utxos.forEach { utxo ->
                if (utxo.nativeAssets.isEmpty()) {
                    // Always spend a utxo that is ada-only
                    transaction.append("--tx-in ${utxo.hash}#${utxo.ix} ")
                    utxosToSpend.add(utxo)
                } else {
                    var shouldSpendUtxo = false
                    utxo.nativeAssets.forEach nativeAssets@{ nativeAsset ->
                        val currency = "${nativeAsset.policy}.${nativeAsset.name}".trimEnd('.')
                        val nativeAssetBaseAmount = baseAmount.getOrDefault(currency, BigInteger.ZERO)
                        if (nativeAssetBaseAmount > BigInteger.ZERO || request.toAccounts.find { toAccountFeeRequest -> toAccountFeeRequest.currency == currency } != null) {
                            // This native asset will be spent, include this utxo
                            shouldSpendUtxo = true
                            return@nativeAssets // break
                        }
                    }
                    if (shouldSpendUtxo || utxo.nativeAssets.size > NATIVE_ASSET_MAX_GROUP_SIZE || utxo.nativeAssets.size < NATIVE_ASSET_MIN_GROUP_SIZE) {
                        // We either need to spend this utxo, or we include it because the native assets on it need to be split or consolidated
                        transaction.append("--tx-in ${utxo.hash}#${utxo.ix} ")
                        utxosToSpend.add(utxo)
                        utxo.nativeAssets.forEach { nativeAsset ->
                            val currency = "${nativeAsset.policy}.${nativeAsset.name}".trimEnd('.')
                            val nativeAssetBaseAmount = baseAmount.getOrDefault(currency, BigInteger.ZERO)
                            baseAmount[currency] = nativeAssetBaseAmount + nativeAsset.amount
                        }
                    }
                }
            }

            val walletItem =
                walletUtils.getWalletItem(defaultHost, defaultHostConnection, magicString, fromWalletEntry)

            val paymentAddressLovelace = utxosToSpend.sumByBigInteger { it.lovelace }
            // amount of ada locked keeping tokens on utxos that won't be spent. Don't allow the gui to spend this ada.
            val tokenLocked = utxos.sumByBigInteger { it.lovelace } - paymentAddressLovelace
            baseAmount["ada"] = if (request.isClaim) {
                walletItem.stakingAddrLovelace!!
            } else {
                paymentAddressLovelace - dummyTxFee - dummyTokenKeepFee
            }

            val toAccounts = request.toAccounts.toMutableList()
            while (toAccounts.size > 0) {
                // the group of all toAccounts destined for the same receiving address
                val account = toAccounts[0]
                val toAccountsGroup =
                    toAccounts.filter { it.account == account.account && it.address == account.address }
                val walletEntry = when {
                    account.isAddress -> {
                        // This raw receiving address won't be in our db, so just make a dummy WalletEntry
                        WalletEntry(
                            name = "unnamed_address",
                            type = "address",
                            paymentAddr = account.address
                        )
                    }
                    account.account < 1L -> {
                        WalletEntry(
                            name = "dummy_entry",
                            type = "address",
                            paymentAddr = "addr_test1vqwyf8g87u0x2ex9mjczrceyh65zj77fra23nfylnf4gxhqwz3dvu"
                        )
                    }
                    else -> {
                        walletRepository.findByIdOrNull(account.account)
                            ?: throw IOException("Wallet entry id ${account.account} not found!")
                    }
                }

                var claimAmount = BigInteger.ZERO
                val amount = toAccountsGroup.sumByBigInteger { toAccount ->
                    if (toAccount.currency == "ada") {
                        if (request.isClaim && walletEntry.id == feePayerWalletEntry.id) {
                            claimAmount = paymentAddressLovelace
                            baseAmount["ada"] = baseAmount["ada"]!! - dummyTxFee
                            toAccount.amount?.takeIf { it > BigInteger.ZERO } ?: "1000000".toBigInteger()
                        } else {
                            toAccount.amount?.takeIf { it > BigInteger.ZERO } ?: "1000000".toBigInteger()
                        }
                    } else {
                        "15000000".toBigInteger() // dummy token fee
                    }
                }
                baseAmount["ada"] = baseAmount["ada"]!! - amount

                transaction.append("--tx-out '${walletEntry.paymentAddr}+${amount + claimAmount}")
                toAccountsGroup.forEach { toAccount ->
                    if (toAccount.currency != "ada") {
                        transaction.append("+${toAccount.amount!!.takeIf { it > BigInteger.ZERO } ?: "1".toBigInteger()} ${
                            toAccount.currency.trimEnd(
                                '.'
                            )
                        }")
                        baseAmount[toAccount.currency.trimEnd('.')] =
                            baseAmount[toAccount.currency.trimEnd('.')]!! - toAccount.amount
                    }
                }
                if (request.isClaim && walletEntry == feePayerWalletEntry) {
                    utxosToSpend.toNativeAssetMap().forEach { (currency, amount) ->
                        if (amount > BigInteger.ZERO) {
                            transaction.append("+$amount $currency")
                        }
                    }
                }
                transaction.append("' ")
                toAccounts.removeAll(toAccountsGroup)
            }

            var remaining = baseAmount["ada"]!! + if (!request.isClaim) dummyTokenKeepFee else BigInteger.ZERO
            if (remaining != BigInteger.ZERO) {

                // split change for tokens into multiple utxo out values of max group size
                baseAmount.filter { it.key != "ada" && it.value > BigInteger.ZERO }.entries.chunked(
                    NATIVE_ASSET_MAX_GROUP_SIZE
                ).forEach { chunk ->
                    val multiAssetString =
                        chunk.joinToString(separator = "+", prefix = "+") { (currency, amount) -> "$amount $currency" }
                    transaction.append("--tx-out '${fromWalletEntry.paymentAddr}+${dummyTokenKeepFee}${multiAssetString}' ")
                    remaining -= dummyTokenKeepFee
                }

                if (remaining != BigInteger.ZERO) {
                    // utxo for the remaining ada
                    transaction.append("--tx-out '${fromWalletEntry.paymentAddr}+${remaining.abs()}' ")
                }
            }

            val queryTipString =
                defaultHostConnection.command("${defaultHost.cardanoCliPath} query tip $magicString").trim()
            val ttl = queryTipAdapter.fromJson(queryTipString)?.let { it.slot + 21600 } ?: -1
            transaction.append("--invalid-hereafter $ttl ")
            transaction.append("--fee 300000 ")

            val metadataFileParameter = if (!request.metadata.isNullOrBlank()) {
                defaultHostConnection.commandWriteFile(
                    "/tmp/dummy.metadata-${genesis.networkMagic}.json",
                    request.metadata
                )
                "--metadata-json-file /tmp/dummy.metadata-${genesis.networkMagic}.json "
            } else {
                ""
            }
            transaction.append(metadataFileParameter)

            if (request.isClaim) {
                transaction.append("--withdrawal ${fromWalletEntry.stakingAddr}+${walletItem.stakingAddrLovelace} ")
            }
            transaction.append("--out-file /tmp/dummy-${genesis.networkMagic}.txbody")

            // build the transaction
            val error = defaultHostConnection.command(transaction.toString()).trim()

            if (error.isNotBlank()) {
                throw IOException(error)
            }

            if (!defaultHostConnection.commandFileExists("/tmp/protocol-parameters-${genesis.networkMagic}.json")) {
                throw IOException("/tmp/protocol-parameters-${genesis.networkMagic}.json does not exist!")
            }
            if (!defaultHostConnection.commandFileExists("/tmp/dummy-${genesis.networkMagic}.txbody")) {
                throw IOException("/tmp/dummy-${genesis.networkMagic}.txbody does not exist!")
            }
            if (metadataFileParameter.isNotBlank() && !defaultHostConnection.commandFileExists("/tmp/dummy.metadata-${genesis.networkMagic}.json")) {
                throw IOException("/tmp/dummy.metadata-${genesis.networkMagic}.json does not exist!")
            }

            val fee = if (request.isClaim) {
                defaultHostConnection.command("${defaultHost.cardanoCliPath} transaction calculate-min-fee --tx-body-file /tmp/dummy-${genesis.networkMagic}.txbody --protocol-params-file /tmp/protocol-parameters-${genesis.networkMagic}.json --tx-in-count 0 --tx-out-count 0 $magicString --witness-count 2 --byron-witness-count 0")
                    .trim()
            } else {
                defaultHostConnection.command("${defaultHost.cardanoCliPath} transaction calculate-min-fee --tx-body-file /tmp/dummy-${genesis.networkMagic}.txbody --protocol-params-file /tmp/protocol-parameters-${genesis.networkMagic}.json --tx-in-count 0 --tx-out-count 0 $magicString --witness-count 1 --byron-witness-count 0")
                    .trim()
            }
            val lovelace = fee.split(" ")[0].toBigInteger()

            val tokenFees = mutableListOf<BigInteger>()
            var tokenKeepFee = BigInteger.ZERO
            if (request.isClaim) {
                // Make sure the fee payer still has enough to cover now that we know the amount
                calculateClaimRewardsFeePayer(
                    defaultHost,
                    defaultHostConnection,
                    magicString,
                    request.toAccounts.map { it.account },
                    lovelace
                )
                tokenFees.addAll(request.toAccounts.map { BigInteger.ZERO })
            } else {
                // Calculate any token fees
                val tokenAmounts = sortedMapOf<String, BigInteger>()
                utxosToSpend.forEach { utxo ->
                    utxo.nativeAssets.forEach { nativeAsset ->
                        val currency = "${nativeAsset.policy}.${nativeAsset.name}".trimEnd('.')
                        val nativeAssetBaseAmount = tokenAmounts.getOrDefault(currency, BigInteger.ZERO)
                        tokenAmounts[currency] = nativeAssetBaseAmount + nativeAsset.amount
                    }
                }
                if (tokenAmounts.isNotEmpty()) {
                    val adaAmountsMap = mutableMapOf<String, BigInteger>()
                    val prevTokenFeesMap = mutableMapOf<String, BigInteger>()
                    val multiAssetStringMap = mutableMapOf<String, String>()
                    request.toAccounts.forEach { toAccount ->
                        // Sum up all the entries going to a given destination that contain ada. This ada can be
                        // used to cover some/all of the token fees.
                        if (toAccount.currency == "ada") {
                            val destination = if (toAccount.isAddress) {
                                toAccount.address
                            } else {
                                toAccount.account.toString()
                            }
                            adaAmountsMap[destination] =
                                (adaAmountsMap[destination] ?: BigInteger.ZERO) + toAccount.amount!!
                        }
                    }
                    request.toAccounts.forEach { toAccount ->
                        if (toAccount.currency != "ada") {
                            val destination = if (toAccount.isAddress) {
                                toAccount.address
                            } else {
                                toAccount.account.toString()
                            }
                            var multiAssetString = multiAssetStringMap[destination]
                                ?: "addr_test1qq9p80xwnjn7h7jnf68gdrl0dpz9q906wcec50lj3gy87rkh0yuewe07amxf24z2d4z8lrkx3ffjnmecyc6zy86fmvrsg5d570 1000000+" // start with dummy amount of ada to send
                            multiAssetString += "${toAccount.amount} ${toAccount.currency}"
                            val minValueResult =
                                defaultHostConnection.command("${defaultHost.cardanoCliPath} transaction calculate-min-required-utxo --alonzo-era --protocol-params-file /tmp/protocol-parameters-${genesis.networkMagic}.json --tx-out '$multiAssetString'")
                                    .trim()
                            val tokenFee = (minValueResult.split(" ")[1].toBigInteger() - (adaAmountsMap[destination]
                                ?: BigInteger.ZERO)).takeIf { tokenFee -> tokenFee > BigInteger.ZERO }
                                ?: BigInteger.ZERO

                            val prevTokenFees = prevTokenFeesMap[destination] ?: BigInteger.ZERO

                            tokenFees.add(tokenFee - prevTokenFees)
                            prevTokenFeesMap[destination] = tokenFee
                            multiAssetStringMap[destination] = "$multiAssetString+"
                            tokenAmounts[toAccount.currency] = tokenAmounts[toAccount.currency]!! - toAccount.amount!!
                        } else {
                            tokenFees.add(BigInteger.ZERO)
                        }
                    }
                    val remainingCurrencies = tokenAmounts.filterValues { amount -> amount > BigInteger.ZERO }.keys
                    if (remainingCurrencies.isNotEmpty()) {
                        remainingCurrencies.chunked(NATIVE_ASSET_MAX_GROUP_SIZE).forEach { chunk ->
                            val multiAssetString = chunk.joinToString(
                                separator = "+1 ",
                                prefix = "addr_test1qq9p80xwnjn7h7jnf68gdrl0dpz9q906wcec50lj3gy87rkh0yuewe07amxf24z2d4z8lrkx3ffjnmecyc6zy86fmvrsg5d570 1000000+1 "
                            )
                            val minValueResult =
                                defaultHostConnection.command("${defaultHost.cardanoCliPath} transaction calculate-min-required-utxo --alonzo-era --protocol-params-file /tmp/protocol-parameters-${genesis.networkMagic}.json --tx-out '$multiAssetString'")
                                    .trim()
                            tokenKeepFee += minValueResult.split(" ")[1].toBigInteger()
                        }
                    }
                } else {
                    tokenFees.addAll(request.toAccounts.map { BigInteger.ZERO })
                }
            }

            defaultHostConnection.command("rm -f /tmp/protocol-parameters-${genesis.networkMagic}.json /tmp/dummy-${genesis.networkMagic}.txbody /tmp/dummy.metadata-${genesis.networkMagic}.json")

            webSocketTemplate.convertAndSend(
                "/topic/messages",
                SocketResponse.Success(
                    type = "calculatefee",
                    data = CalculateFeeResponse(
                        txFee = lovelace,
                        tokenFees = tokenFees,
                        tokenKeepFee = tokenKeepFee,
                        tokenLocked = tokenLocked,
                        uuid = request.uuid,
                    )
                )
            )
        } catch (e: Throwable) {
            val error = "Fatal error calculating fees!"
            log.error(error, e)
            webSocketTemplate.convertAndSend(
                "/topic/messages",
                SocketResponse.Error(type = "calculatefee", exception = e)
            )
        }
    }

    @MessageMapping("/createwalletentry")
    @SendTo("/topic/messages")
    @Transactional
    fun createWalletEntry(request: CreateWalletEntryRequest): SocketResponse<String> {
        return try {
            if (!walletUtils.isValidSpendingPassword(request.spendingPassword)) {
                throw IllegalArgumentException("Invalid spending password!")
            }
            val walletEntry: WalletEntry = when (request.type) {
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
            log.error(error, e)
            SocketResponse.Error(type = "createwalletentry", exception = e)
        }
    }

    @MessageMapping("/updatestakingaddress")
    @Transactional
    fun updateStakingAddress(request: UpdateStakingAddressRequest) {
        try {
            if (!walletUtils.isValidSpendingPassword(request.spendingPassword)) {
                throw IllegalArgumentException("Invalid spending password!")
            }
            nodeRepository.findDefault()?.let { defaultNode ->
                fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)?.let { genesisFile ->
                    val genesis = shelleyGenesisAdapter.fromJson(genesisFile.content)!!
                    val magicString = if (genesis.networkId.equals("testnet", ignoreCase = true)) {
                        "--testnet-magic ${genesis.networkMagic}"
                    } else {
                        "--mainnet"
                    }

                    hostRepository.findByIdOrNull(defaultNode.hostId)?.let { defaultHost ->
                        val defaultHostConnection = HostConnection(defaultHost, defaultNode)
                        try {
                            val protocolParamsJson =
                                defaultHostConnection.command("${defaultHost.cardanoCliPath} query protocol-parameters $magicString")
                                    .trim()
                            defaultHostConnection.commandWriteFile("/tmp/protocol-parameters.json", protocolParamsJson)
                            val protocolParameters = protocolParamsAdapter.fromJson(protocolParamsJson)
                                ?: throw IOException("Invalid protocol params!")

                            // 1. Create a transaction to dump EVERYTHING into
                            var depositAndFees = BigInteger.ZERO
                            var witnessCount = 0
                            val transaction = StringBuilder()
                            val certificates = StringBuilder()
                            val signingKeys = StringBuilder()
                            transaction.append("${defaultHost.cardanoCliPath} transaction build-raw ")
                            val feePayerAccount = walletRepository.findByIdOrNull(request.stakingFeesAccount)
                                ?: throw IOException("Registration fees account not found!")
                            val utxos = walletUtils.getUtxos(
                                defaultHost,
                                defaultHostConnection,
                                magicString,
                                feePayerAccount.paymentAddr
                            )
                            utxos.forEach { utxo ->
                                transaction.append("--tx-in ${utxo.hash}#${utxo.ix} ")
                            }
                            log.debug("feePayerAccount balance: ${utxos.sumByBigInteger { it.lovelace }}")
                            witnessCount++ // fee payer is a witness
                            defaultHostConnection.commandWriteFile(
                                "/tmp/feepayer.payment.skey",
                                walletUtils.getSKeyContent(
                                    requireNotNull(feePayerAccount.paymentSkey),
                                    request.spendingPassword
                                )
                            )
                            signingKeys.append("--signing-key-file /tmp/feepayer.payment.skey ")

                            // initial dummy value to return change to the fee payer account.
                            // We'll replace this with the actual change to return later
                            transaction.append("--tx-out ${feePayerAccount.paymentAddr}+1234567890 ")

                            val queryTipString =
                                defaultHostConnection.command("${defaultHost.cardanoCliPath} query tip $magicString")
                                    .trim()
                            val ttl = queryTipAdapter.fromJson(queryTipString)?.let { it.slot + 21600 }
                                ?: -1
                            transaction.append("--invalid-hereafter $ttl ")
                            transaction.append("--fee 100 ")

                            // 2. Register staking address on the chain if not yet registered
                            val stakingAccount = walletRepository.findByIdOrNull(request.id)
                                ?: throw IOException("Owner staking account not found!")
                            defaultHostConnection.commandWriteFile(
                                "/tmp/staking.skey",
                                walletUtils.getSKeyContent(
                                    requireNotNull(stakingAccount.stakingSkey),
                                    request.spendingPassword
                                )
                            )
                            defaultHostConnection.commandWriteFile(
                                "/tmp/staking.vkey",
                                requireNotNull(stakingAccount.stakingVkey?.content)
                            )
                            val stakingWalletItem = walletUtils.getWalletItem(
                                defaultHost,
                                defaultHostConnection,
                                magicString,
                                stakingAccount
                            )

                            if (request.isRegistration && stakingWalletItem.stakingAddrRegistered) {
                                throw IOException("Staking Address already registered!")
                            } else if (!request.isRegistration && !stakingWalletItem.stakingAddrRegistered) {
                                throw IOException("Staking Address already deregistered!")
                            }

                            if (!stakingWalletItem.stakingAddrRegistered) {
                                // staking address is *not* registered. We should register it on chain as part of the transaction
                                stakingAccount.stakingRegCert?.let { stakingRegCert ->
                                    defaultHostConnection.commandWriteFile("/tmp/staking.cert", stakingRegCert.content)
                                } ?: throw IOException("Staking reg cert not found on account!")
                                depositAndFees += protocolParameters.stakeAddressDeposit
                                certificates.append("--certificate /tmp/staking.cert ")
                            } else {
                                // staking address is registered. We should *de* register it on chain as part of the transaction
                                defaultHostConnection.command("${defaultHost.cardanoCliPath} stake-address deregistration-certificate --stake-verification-key-file /tmp/staking.vkey --out-file /tmp/staking.dereg-cert")
                                certificates.append("--certificate /tmp/staking.dereg-cert ")
                            }
                            witnessCount++ // the staking.skey is a witness
                            signingKeys.append("--signing-key-file /tmp/staking.skey ")

                            // 8. Calculate fees
                            transaction.append(certificates)
                            transaction.append("--out-file /tmp/transaction.txbody")
                            defaultHostConnection.command(transaction.toString())

                            log.debug("depositAndFees: $depositAndFees")
                            val feesString =
                                defaultHostConnection.command("${defaultHost.cardanoCliPath} transaction calculate-min-fee --tx-body-file /tmp/transaction.txbody --protocol-params-file /tmp/protocol-parameters.json --tx-in-count ${utxos.size} --tx-out-count 1 $magicString --witness-count $witnessCount --byron-witness-count 0")
                                    .trim()
                            val fees = feesString.split(" ")[0].toBigInteger()
                            log.debug("fees: $fees")
                            depositAndFees += fees
                            log.debug("final depositAndFees: $depositAndFees")

                            // 9. Create the transaction
                            val change =
                                utxos.sumByBigInteger { it.lovelace } - depositAndFees + if (!request.isRegistration) {
                                    // for a deregistration, we get the 2 ada deposit back as change
                                    protocolParameters.stakeAddressDeposit
                                } else {
                                    BigInteger.ZERO
                                }
                            if (change < BigInteger.ONE) {
                                throw IOException("Not enough funds to pay depositAndFees of $depositAndFees lovelace!")
                            }

                            val tokenChange = StringBuilder()
                            utxos.toNativeAssetMap().forEach { (currency, amount) ->
                                if (amount > BigInteger.ZERO) {
                                    tokenChange.append("+$amount $currency")
                                }
                            }

                            val realTransaction = transaction.toString()
                                .replace("--fee 100 ", "--fee $fees ")
                                .replace(
                                    "--tx-out ${feePayerAccount.paymentAddr}+1234567890 ",
                                    "--tx-out '${feePayerAccount.paymentAddr}+$change$tokenChange' "
                                )
                            log.debug("StakeKey Transaction Command: $realTransaction")
                            defaultHostConnection.command(realTransaction)

                            // 10. Sign the transaction
                            defaultHostConnection.command("${defaultHost.cardanoCliPath} transaction sign --tx-body-file /tmp/transaction.txbody $signingKeys $magicString --out-file /tmp/transaction.txsigned")

                            // 11. Submit the transaction
                            defaultHostConnection.command("${defaultHost.cardanoCliPath} transaction submit --tx-file /tmp/transaction.txsigned $magicString")
                            val txid =
                                defaultHostConnection.command("${defaultHost.cardanoCliPath} transaction txid --tx-body-file /tmp/transaction.txbody")
                            transactionRepository.save(Transaction(txid = txid))

                            webSocketTemplate.convertAndSend(
                                "/topic/messages",
                                SocketResponse.Success(
                                    type = "updatestakingaddress",
                                    data = "${stakingAccount.name} ${if (request.isRegistration) "" else "de"}registered. TxId: $txid"
                                )
                            )
                        } finally {
                            // Cleanup
                            defaultHostConnection.command("rm -f /tmp/protocol-parameters.json /tmp/transaction.txbody /tmp/transaction.txsigned /tmp/feepayer.payment.skey /tmp/staking.skey /tmp/staking.vkey /tmp/staking.cert /tmp/staking.dereg-cert")
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

    private fun createStakeWalletEntry(request: CreateWalletEntryRequest): WalletEntry {
        return nodeRepository.findDefault()?.let { defaultNode ->
            fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)?.let { genesisFile ->
                val genesis = shelleyGenesisAdapter.fromJson(genesisFile.content)!!
                val magicString = if (genesis.networkId.equals("testnet", ignoreCase = true)) {
                    "--testnet-magic ${genesis.networkMagic}"
                } else {
                    "--mainnet"
                }
                hostRepository.findByIdOrNull(defaultNode.hostId)?.let { defaultHost ->
                    val defaultHostConnection = HostConnection(defaultHost, defaultNode)
                    try {
                        val (pskeyContent, pvkeyContent) = when {
                            request.generateKeys -> {
                                defaultHostConnection.command("${defaultHost.cardanoCliPath} address key-gen --verification-key-file /tmp/jormanager-pvkey --signing-key-file /tmp/jormanager-pskey")
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

                        val savedPaymentSKeyFile = pskeyContent?.let {
                            fileRepository.save(
                                File(
                                    name = "${request.name}.payment.skey",
                                    content = walletUtils.encryptSKeyContent(pskeyContent, request.spendingPassword)
                                )
                            )
                        }
                        val savedPaymentVKeyFile = pvkeyContent?.let {
                            fileRepository.save(
                                File(
                                    name = "${request.name}.payment.vkey",
                                    content = pvkeyContent
                                )
                            )
                        }

                        val (sskeyContent, svkeyContent) = if (request.generateKeys) {
                            defaultHostConnection.command("${defaultHost.cardanoCliPath} stake-address key-gen --verification-key-file /tmp/jormanager-svkey --signing-key-file /tmp/jormanager-sskey")
                            val stakingSKey = defaultHostConnection.commandReadFile("/tmp/jormanager-sskey")
                            val stakingVKey = defaultHostConnection.commandReadFile("/tmp/jormanager-svkey")
                            Pair(stakingSKey, stakingVKey)
                        } else {
                            Pair(request.stakingSKey!!, request.stakingVKey!!)
                        }

                        val savedStakingSKeyFile = fileRepository.save(
                            File(
                                name = "${request.name}.staking.skey",
                                content = walletUtils.encryptSKeyContent(sskeyContent, request.spendingPassword)
                            )
                        )
                        val savedStakingVKeyFile = fileRepository.save(
                            File(
                                name = "${request.name}.staking.vkey",
                                content = svkeyContent
                            )
                        )

                        if (pvkeyContent != null) {
                            defaultHostConnection.commandWriteFile("/tmp/jormanager-pvkey", pvkeyContent)
                        }
                        defaultHostConnection.commandWriteFile("/tmp/jormanager-svkey", svkeyContent)
                        defaultHostConnection.command("${defaultHost.cardanoCliPath} stake-address registration-certificate --staking-verification-key-file /tmp/jormanager-svkey --out-file /tmp/jormanager-regcert")
                            .trim()
                        val regcertContent = defaultHostConnection.commandReadFile("/tmp/jormanager-regcert")
                        val savedRegcertFile = fileRepository.save(
                            File(
                                name = "${request.name}.staking.cert",
                                content = regcertContent
                            )
                        )

                        val paymentAddr = if (request.type == "pledge") {
                            request.paymentAddr
                        } else {
                            defaultHostConnection.command("${defaultHost.cardanoCliPath} address build --payment-verification-key-file /tmp/jormanager-pvkey --staking-verification-key-file /tmp/jormanager-svkey $magicString")
                                .trim()
                        }
                        val stakingAddr =
                            defaultHostConnection.command("${defaultHost.cardanoCliPath} stake-address build --staking-verification-key-file /tmp/jormanager-svkey $magicString")
                                .trim()
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
                        defaultHostConnection.command("rm -f /tmp/jormanager-pskey /tmp/jormanager-pvkey /tmp/jormanager-sskey /tmp/jormanager-svkey /tmp/jormanager-regcert")
                    }
                } ?: throw IOException("Host not found for default node!")
            } ?: throw IOException("Genesis file for default node not found!")
        } ?: throw IOException("No default node!")
    }

    private fun createPaymentWalletEntry(request: CreateWalletEntryRequest): WalletEntry {
        return nodeRepository.findDefault()?.let { defaultNode ->
            fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)?.let { genesisFile ->
                val genesis = shelleyGenesisAdapter.fromJson(genesisFile.content)!!
                val magicString = if (genesis.networkId.equals("testnet", ignoreCase = true)) {
                    "--testnet-magic ${genesis.networkMagic}"
                } else {
                    "--mainnet"
                }
                hostRepository.findByIdOrNull(defaultNode.hostId)?.let { defaultHost ->
                    val defaultHostConnection = HostConnection(defaultHost, defaultNode)
                    try {
                        val (skeyContent, vkeyContent) = if (request.generateKeys) {
                            defaultHostConnection.command("${defaultHost.cardanoCliPath} address key-gen --verification-key-file /tmp/jormanager-pvkey --signing-key-file /tmp/jormanager-pskey")
                            val paymentSKey = defaultHostConnection.commandReadFile("/tmp/jormanager-pskey")
                            val paymentVKey = defaultHostConnection.commandReadFile("/tmp/jormanager-pvkey")
                            Pair(paymentSKey, paymentVKey)
                        } else {
                            Pair(request.paymentSKey!!, request.paymentVKey!!)
                        }

                        val savedPaymentSKeyFile = fileRepository.save(
                            File(
                                name = "${request.name}.payment.skey",
                                content = walletUtils.encryptSKeyContent(skeyContent, request.spendingPassword)
                            )
                        )
                        val savedPaymentVKeyFile = fileRepository.save(
                            File(
                                name = "${request.name}.payment.vkey",
                                content = vkeyContent
                            )
                        )

                        defaultHostConnection.commandWriteFile("/tmp/jormanager-pvkey", vkeyContent)
                        val paymentAddr =
                            defaultHostConnection.command("${defaultHost.cardanoCliPath} address build --payment-verification-key-file /tmp/jormanager-pvkey $magicString")
                                .trim()
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
    }

    @MessageMapping("/deletewalletentry")
    @SendTo("/topic/messages")
    @Transactional
    fun deleteWalletEntry(request: DeleteWalletEntryRequest): SocketResponse<String> {
        return try {
            if (!walletUtils.isValidSpendingPassword(request.spendingPassword)) {
                throw IllegalArgumentException("Invalid spending password!")
            }
            val walletEntry = walletRepository.findByIdOrNull(request.id)
            if (walletEntry != null) {
                val now = System.currentTimeMillis()
                val paymentSkey = walletEntry.paymentSkey?.let {
                    fileRepository.save(it.copy(name = it.name + "-$now"))
                }
                val paymentVkey = walletEntry.paymentVkey?.let {
                    fileRepository.save(it.copy(name = it.name + "-$now"))
                }
                val stakingRegCert = walletEntry.stakingRegCert?.let {
                    fileRepository.save(it.copy(name = it.name + "-$now"))
                }
                val stakingSkey = walletEntry.stakingSkey?.let {
                    fileRepository.save(it.copy(name = it.name + "-$now"))
                }
                val stakingVkey = walletEntry.stakingVkey?.let {
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
            log.error(error, e)
            SocketResponse.Error(type = "deletewalletentry", exception = e)
        }
    }

    @MessageMapping("/submittransaction")
    @Synchronized
    fun submitTransaction(request: SubmitTransactionRequest) {
        try {
            log.debug("submitTransaction request: $request")
            if (!walletUtils.isValidSpendingPassword(request.spendingPassword)) {
                throw IllegalArgumentException("Invalid spending password!")
            }

            val defaultNode = nodeRepository.findDefault() ?: throw IOException("No default node!")
            val genesisFile = fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)
                ?: throw IOException("Genesis file for default node not found!")
            val genesis = shelleyGenesisAdapter.fromJson(genesisFile.content)
                ?: throw IOException("Unable to parse genesis json")
            val magicString = if (genesis.networkId.equals("testnet", ignoreCase = true)) {
                "--testnet-magic ${genesis.networkMagic}"
            } else {
                "--mainnet"
            }
            val defaultHost = hostRepository.findByIdOrNull(defaultNode.hostId)
                ?: throw IOException("Host not found for default node!")
            val defaultHostConnection = HostConnection(defaultHost, defaultNode)
            try {
                val protocolParams =
                    defaultHostConnection.command("${defaultHost.cardanoCliPath} query protocol-parameters $magicString")
                        .trim()
                defaultHostConnection.commandWriteFile(
                    "/tmp/protocol-parameters-${genesis.networkMagic}.json",
                    protocolParams
                )

                val fromWalletEntry = walletRepository.findByIdOrNull(request.fromId)
                    ?: throw IOException("Wallet entry id ${request.fromId} not found!")
                val feePayerWalletEntry = if (request.isClaim) {
                    calculateClaimRewardsFeePayer(
                        defaultHost,
                        defaultHostConnection,
                        magicString,
                        request.toAccounts.filter { !it.isAddress }.map { it.account },
                        request.txFee
                    )
                } else {
                    fromWalletEntry
                }

                val baseAmount = sortedMapOf<String, BigInteger>()
                val utxos = walletUtils.getUtxos(
                    defaultHost,
                    defaultHostConnection,
                    magicString,
                    feePayerWalletEntry.paymentAddr
                )
                val transaction = StringBuilder()
                transaction.append("${defaultHost.cardanoCliPath} transaction build-raw ")
                val utxosToSpend = mutableListOf<Utxo>()
                utxos.forEach { utxo ->
                    if (utxo.nativeAssets.isEmpty()) {
                        // Always spend a utxo that is ada-only
                        transaction.append("--tx-in ${utxo.hash}#${utxo.ix} ")
                        utxosToSpend.add(utxo)
                    } else {
                        var shouldSpendUtxo = false
                        utxo.nativeAssets.forEach nativeAssets@{ nativeAsset ->
                            val currency = "${nativeAsset.policy}.${nativeAsset.name}".trimEnd('.')
                            val nativeAssetBaseAmount = baseAmount.getOrDefault(currency, BigInteger.ZERO)
                            if (nativeAssetBaseAmount > BigInteger.ZERO || request.toAccounts.find { toAccountFeeRequest -> toAccountFeeRequest.currency == currency } != null) {
                                // This native asset will be spent, include this utxo
                                shouldSpendUtxo = true
                                return@nativeAssets // break
                            }
                        }
                        if (shouldSpendUtxo || utxo.nativeAssets.size > NATIVE_ASSET_MAX_GROUP_SIZE || utxo.nativeAssets.size < NATIVE_ASSET_MIN_GROUP_SIZE) {
                            // We either need to spend this utxo, or we include it because the native assets on it need to be split or consolidated
                            transaction.append("--tx-in ${utxo.hash}#${utxo.ix} ")
                            utxosToSpend.add(utxo)
                            utxo.nativeAssets.forEach { nativeAsset ->
                                val currency = "${nativeAsset.policy}.${nativeAsset.name}".trimEnd('.')
                                val nativeAssetBaseAmount = baseAmount.getOrDefault(currency, BigInteger.ZERO)
                                baseAmount[currency] = nativeAssetBaseAmount + nativeAsset.amount
                            }
                        }
                    }
                }

                val walletItem =
                    walletUtils.getWalletItem(defaultHost, defaultHostConnection, magicString, fromWalletEntry)

                val paymentAddressLovelace = utxosToSpend.sumByBigInteger { it.lovelace }
                baseAmount["ada"] = if (request.isClaim) {
                    walletItem.stakingAddrLovelace!!
                } else {
                    paymentAddressLovelace - request.txFee - request.tokenKeepFee
                }

                val toAccounts = request.toAccounts.toMutableList()
                while (toAccounts.size > 0) {
                    // the group of all toAccounts destined for the same receiving address
                    val account = toAccounts[0]
                    val toAccountsGroup =
                        toAccounts.filter { it.account == account.account && it.address == account.address }
                    val walletEntry = when {
                        account.isAddress -> {
                            // This raw receiving address won't be in our db, so just make a dummy WalletEntry
                            WalletEntry(
                                name = "unnamed_address",
                                type = "address",
                                paymentAddr = account.address
                            )
                        }
                        else -> {
                            walletRepository.findByIdOrNull(account.account)
                                ?: throw IOException("Wallet entry id ${account.account} not found!")
                        }
                    }

                    var claimAmount = BigInteger.ZERO
                    val amount = toAccountsGroup.sumByBigInteger { toAccount ->
                        if (toAccount.currency == "ada") {
                            if (request.isClaim && walletEntry.id == feePayerWalletEntry.id) {
                                if (toAccount.percent ?: -1 > 0 && baseAmount["ada"]!! - account.amount!! >= request.txFee) {
                                    // Reimburse payer for the txFee when claiming rewards
                                    claimAmount = paymentAddressLovelace - request.txFee
                                    log.debug("claimAmount: $claimAmount")
                                    account.amount + request.txFee
                                } else {
                                    // We've already reimbursed txFee on the client side. take it out of the base amount
                                    claimAmount = paymentAddressLovelace
                                    baseAmount["ada"] = baseAmount["ada"]!! - request.txFee
                                    toAccount.amount!!
                                }
                            } else {
                                toAccount.amount!!
                            }
                        } else {
                            toAccount.tokenFee
                        }
                    }
                    baseAmount["ada"] = baseAmount["ada"]!! - amount

                    transaction.append("--tx-out '${walletEntry.paymentAddr}+${amount + claimAmount}")
                    toAccountsGroup.forEach { toAccount ->
                        if (toAccount.currency != "ada") {
                            transaction.append("+${toAccount.amount} ${toAccount.currency.trimEnd('.')}")
                            baseAmount[toAccount.currency.trimEnd('.')] =
                                baseAmount[toAccount.currency.trimEnd('.')]!! - toAccount.amount!!
                        }
                    }
                    if (request.isClaim && walletEntry == feePayerWalletEntry) {
                        utxosToSpend.toNativeAssetMap().forEach { (currency, amount) ->
                            if (amount > BigInteger.ZERO) {
                                transaction.append("+$amount $currency")
                            }
                        }
                    }
                    transaction.append("' ")
                    toAccounts.removeAll(toAccountsGroup)
                }

                var remaining = baseAmount["ada"]!! + if (!request.isClaim) request.tokenKeepFee else BigInteger.ZERO
                if (remaining != BigInteger.ZERO) {
                    // Sanity check. Should never happen if we did things right
                    if (request.isClaim) {
                        throw IOException("We had a remaining balance when claiming rewards!!!: $remaining")
                    }

                    // split change for tokens into multiple utxo out values of max group size
                    baseAmount.filter { it.key != "ada" && it.value > BigInteger.ZERO }.entries.chunked(
                        NATIVE_ASSET_MAX_GROUP_SIZE
                    ).forEach { chunk ->
                        val multiAssetString =
                            chunk.joinToString(separator = "+") { (currency, amount) -> "$amount $currency" }
                        val minValueResult =
                            defaultHostConnection.command("${defaultHost.cardanoCliPath} transaction calculate-min-required-utxo --alonzo-era --protocol-params-file /tmp/protocol-parameters-${genesis.networkMagic}.json --tx-out 'addr_test1qq9p80xwnjn7h7jnf68gdrl0dpz9q906wcec50lj3gy87rkh0yuewe07amxf24z2d4z8lrkx3ffjnmecyc6zy86fmvrsg5d570 1000000+$multiAssetString'")
                                .trim()
                        val tokenKeepFee = minValueResult.split(" ")[1].toBigInteger()

                        transaction.append("--tx-out '${fromWalletEntry.paymentAddr}+${tokenKeepFee}+${multiAssetString}' ")
                        remaining -= tokenKeepFee
                    }

                    if (remaining != BigInteger.ZERO) {
                        // utxo for the remaining ada
                        transaction.append("--tx-out '${fromWalletEntry.paymentAddr}+${remaining}' ")
                    }
                }

                val queryTipString =
                    defaultHostConnection.command("${defaultHost.cardanoCliPath} query tip $magicString").trim()
                val ttl = queryTipAdapter.fromJson(queryTipString)?.let { it.slot + 21600 } ?: -1
                transaction.append("--invalid-hereafter $ttl ")
                transaction.append("--fee ${request.txFee} ")

                if (!request.metadata.isNullOrBlank()) {
                    defaultHostConnection.commandWriteFile("/tmp/metadata.json", request.metadata)
                    transaction.append("--metadata-json-file /tmp/metadata.json ")
                }

                if (request.isClaim) {
                    transaction.append("--withdrawal ${fromWalletEntry.stakingAddr}+${walletItem.stakingAddrLovelace} ")
                }
                transaction.append("--out-file /tmp/transaction.txbody")

                // build the transaction
                log.debug("transaction: $transaction")
                defaultHostConnection.command(transaction.toString())

                // sign the transaction
                feePayerWalletEntry.paymentSkey?.let { skey ->
                    val skeyContent = walletUtils.getSKeyContent(skey, request.spendingPassword)
                    defaultHostConnection.commandWriteFile("/tmp/signing.skey", skeyContent)
                } ?: throw IllegalArgumentException("Couldn't find payment skey for transaction")
                if (request.isClaim) {
                    fromWalletEntry.stakingSkey?.let { skey ->
                        val skeyContent = walletUtils.getSKeyContent(skey, request.spendingPassword)
                        defaultHostConnection.commandWriteFile("/tmp/staking_signing.skey", skeyContent)
                    } ?: throw IllegalArgumentException("Couldn't find staking skey for transaction")
                    defaultHostConnection.command("${defaultHost.cardanoCliPath} transaction sign --tx-body-file /tmp/transaction.txbody --signing-key-file /tmp/signing.skey --signing-key-file /tmp/staking_signing.skey $magicString --out-file /tmp/transaction.txsigned")
                } else {
                    defaultHostConnection.command("${defaultHost.cardanoCliPath} transaction sign --tx-body-file /tmp/transaction.txbody --signing-key-file /tmp/signing.skey $magicString --out-file /tmp/transaction.txsigned")
                }

                // submit the transaction
                defaultHostConnection.command("${defaultHost.cardanoCliPath} transaction submit --tx-file /tmp/transaction.txsigned $magicString")
                    .trim()
                val txid =
                    defaultHostConnection.command("${defaultHost.cardanoCliPath} transaction txid --tx-body-file /tmp/transaction.txbody")

                transactionRepository.save(Transaction(txid = txid))
                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Success(type = "submittransaction", data = "transaction succeeded: $txid")
                )
            } finally {
                defaultHostConnection.command("rm -f /tmp/protocol-parameters-${genesis.networkMagic}.json /tmp/signing.skey /tmp/staking_signing.skey /tmp/transaction.txbody /tmp/transaction.txsigned /tmp/metadata.json")
            }
        } catch (e: Throwable) {
            val error = "Fatal error submitting transaction!"
            log.error(error, e)
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
        toAccounts: List<Long?>,
        txFee: BigInteger,
    ): WalletEntry {
        toAccounts.filterNotNull().forEach { id ->
            if (id > 0) {
                walletRepository.findByIdOrNull(id)?.let { walletEntry ->
                    if (walletEntry.paymentSkey != null) {
                        val lovelace = walletUtils.getUtxos(host, hostConnection, magicString, walletEntry.paymentAddr)
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

    companion object {
        // Maximum number of native assets to put into a single UTxO
        const val NATIVE_ASSET_MAX_GROUP_SIZE = 20

        // Minimum number of native assets to allow on a UTxO before we try to consolidate them.
        const val NATIVE_ASSET_MIN_GROUP_SIZE = 5
    }
}
