package com.swiftmako.jormanager.controllers

import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.entities.Transaction
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.ktx.sumByLong
import com.swiftmako.jormanager.model.CalculateFeeRequest
import com.swiftmako.jormanager.model.CreateWalletEntryRequest
import com.swiftmako.jormanager.model.DeleteWalletEntryRequest
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.model.ProtocolParameters
import com.swiftmako.jormanager.model.QueryTip
import com.swiftmako.jormanager.model.SubmitTransactionRequest
import com.swiftmako.jormanager.model.UpdateStakingAddressRequest
import com.swiftmako.jormanager.model.WalletItem
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.TransactionRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import okio.buffer
import okio.source
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.io.IOException
import kotlin.math.round

@Controller
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
) {

    private val log = LoggerFactory.getLogger(WalletController::class.java)

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
    @SendTo("/topic/messages")
    @Synchronized
    fun calculateTxFee(request: CalculateFeeRequest): SocketResponse<Long> {
        return try {
            nodeRepository.findDefault()?.let { defaultNode ->
                fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)?.let { genesisFile ->
                    val genesis = shelleyGenesisAdapter.fromJson(genesisFile.content)!!
                    val magicString = if (genesis.networkId.equals("testnet", ignoreCase = true)) {
                        "--testnet-magic ${genesis.networkMagic}"
                    } else {
                        "--mainnet"
                    }
                    hostRepository.findByIdOrNull(defaultNode.hostId)?.let { host ->
                        val hostConnection = HostConnection(host, defaultNode)
                        val protocolParams = hostConnection.command("${host.cardanoCliPath} shelley query protocol-parameters --cardano-mode $magicString").trim()
                        hostConnection.commandWriteFile("/tmp/protocol-parameters.json", protocolParams)

                        walletRepository.findByIdOrNull(request.fromId)?.let { fromWalletEntry ->

                            val feePayerWalletEntry = if (request.isClaim) {
                                calculateClaimRewardsFeePayer(host, hostConnection, magicString, request.toAccounts)
                            } else {
                                fromWalletEntry
                            }

                            val utxos = walletUtils.getUtxos(host, hostConnection, magicString, feePayerWalletEntry.paymentAddr)
                            val dummyTransaction = StringBuilder()
                            dummyTransaction.append("${host.cardanoCliPath} shelley transaction build-raw ")
                            utxos.forEach { utxo ->
                                dummyTransaction.append("--tx-in ${utxo.hash}#${utxo.ix} ")
                            }
                            repeat(request.txOut) {
                                dummyTransaction.append("--tx-out addr1qyftuwe6fww2eeg2k5quyzp099f5y6pn0snkneu5fdq6puqjuycagppd9yw3kc62xjld0c45a2ljc6d3tlnh96fut8ss67heqw+300000000 ")
                            }
                            val queryTipString = hostConnection.command("${host.cardanoCliPath} shelley query tip $magicString").trim()
                            val ttl = queryTipAdapter.fromJson(queryTipString)?.let { it.slotNo + 1000 } ?: -1

                            if (request.isClaim) {
                                val walletItem = walletUtils.getWalletItem(host, hostConnection, magicString, fromWalletEntry)
                                dummyTransaction.append("--ttl $ttl --fee 300000 --withdrawal ${fromWalletEntry.stakingAddr}+${walletItem.stakingAddrLovelace} --out-file /tmp/dummy.txbody")
                            } else {
                                dummyTransaction.append("--ttl $ttl --fee 300000 --out-file /tmp/dummy.txbody")
                            }
                            hostConnection.command(dummyTransaction.toString())

                            val fee = if (request.isClaim) {
                                hostConnection.command("${host.cardanoCliPath} shelley transaction calculate-min-fee --tx-body-file /tmp/dummy.txbody --protocol-params-file /tmp/protocol-parameters.json --tx-in-count ${utxos.size} --tx-out-count ${request.txOut} $magicString --witness-count 2 --byron-witness-count 0").trim()
                            } else {
                                hostConnection.command("${host.cardanoCliPath} shelley transaction calculate-min-fee --tx-body-file /tmp/dummy.txbody --protocol-params-file /tmp/protocol-parameters.json --tx-in-count ${utxos.size} --tx-out-count ${request.txOut} $magicString --witness-count 1 --byron-witness-count 0").trim()
                            }
                            hostConnection.command("rm -f /tmp/protocol-parameters.json /tmp/dummy.txbody")
                            val lovelace = fee.split(" ")[0].toLong()
                            SocketResponse.Success(type = "calculatefee", data = lovelace)
                        } ?: throw IOException("Wallet entry id ${request.fromId} not found!")
                    } ?: throw IOException("Host not found for default node!")
                } ?: throw IOException("Genesis file for default node not found!")
            } ?: throw IOException("No default node!")
        } catch (e: Throwable) {
            val error = "Fatal error calculating fees!"
            log.error(error, e)
            SocketResponse.Error(type = "calculatefee", exception = e)
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
                            val protocolParamsJson = defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley query protocol-parameters --cardano-mode $magicString").trim()
                            defaultHostConnection.commandWriteFile("/tmp/protocol-parameters.json", protocolParamsJson)
                            val protocolParameters = protocolParamsAdapter.fromJson(protocolParamsJson)
                                    ?: throw IOException("Invalid protocol params!")

                            // 1. Create a transaction to dump EVERYTHING into
                            var depositAndFees = 0L
                            var witnessCount = 0
                            val transaction = StringBuilder()
                            val certificates = StringBuilder()
                            val signingKeys = StringBuilder()
                            transaction.append("${defaultHost.cardanoCliPath} shelley transaction build-raw ")
                            val feePayerAccount = walletRepository.findByIdOrNull(request.stakingFeesAccount)
                                    ?: throw IOException("Registration fees account not found!")
                            val utxos = walletUtils.getUtxos(defaultHost, defaultHostConnection, magicString, feePayerAccount.paymentAddr)
                            utxos.forEach { utxo ->
                                transaction.append("--tx-in ${utxo.hash}#${utxo.ix} ")
                            }
                            log.debug("feePayerAccount balance: ${utxos.sumByLong { it.lovelace }}")
                            witnessCount++ // fee payer is a witness
                            defaultHostConnection.commandWriteFile("/tmp/feepayer.payment.skey", walletUtils.getSKeyContent(requireNotNull(feePayerAccount.paymentSkey), request.spendingPassword))
                            signingKeys.append("--signing-key-file /tmp/feepayer.payment.skey ")

                            // initial dummy value to return change to the fee payer account.
                            // We'll replace this with the actual change to return later
                            transaction.append("--tx-out ${feePayerAccount.paymentAddr}+1234567890 ")

                            val queryTipString = defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley query tip $magicString").trim()
                            val ttl = queryTipAdapter.fromJson(queryTipString)?.let { it.slotNo + 1000 }
                                    ?: -1
                            transaction.append("--ttl $ttl ")
                            transaction.append("--fee 100 ")

                            // 2. Register staking address on the chain if not yet registered
                            val stakingAccount = walletRepository.findByIdOrNull(request.id)
                                    ?: throw IOException("Owner staking account not found!")
                            defaultHostConnection.commandWriteFile("/tmp/staking.skey", walletUtils.getSKeyContent(requireNotNull(stakingAccount.stakingSkey), request.spendingPassword))
                            defaultHostConnection.commandWriteFile("/tmp/staking.vkey", requireNotNull(stakingAccount.stakingVkey?.content))
                            val stakingWalletItem = walletUtils.getWalletItem(defaultHost, defaultHostConnection, magicString, stakingAccount)

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
                                depositAndFees += protocolParameters.keyDeposit
                                certificates.append("--certificate /tmp/staking.cert ")
                            } else {
                                // staking address is registered. We should *de* register it on chain as part of the transaction
                                defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley stake-address deregistration-certificate --stake-verification-key-file /tmp/staking.vkey --out-file /tmp/staking.dereg-cert")
                                certificates.append("--certificate /tmp/staking.dereg-cert ")
                            }
                            witnessCount++ // the staking.skey is a witness
                            signingKeys.append("--signing-key-file /tmp/staking.skey ")

                            // 8. Calculate fees
                            transaction.append(certificates)
                            transaction.append("--out-file /tmp/transaction.txbody")
                            defaultHostConnection.command(transaction.toString())

                            log.debug("depositAndFees: $depositAndFees")
                            val feesString = defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley transaction calculate-min-fee --tx-body-file /tmp/transaction.txbody --protocol-params-file /tmp/protocol-parameters.json --tx-in-count ${utxos.size} --tx-out-count 1 $magicString --witness-count $witnessCount --byron-witness-count 0").trim()
                            val fees = feesString.split(" ")[0].toLong()
                            log.debug("fees: $fees")
                            depositAndFees += fees
                            log.debug("final depositAndFees: $depositAndFees")

                            // 9. Create the transaction
                            val change = utxos.sumByLong { it.lovelace } - depositAndFees + if (!request.isRegistration) {
                                // for a deregistration, we get the 2 ada deposit back as change
                                protocolParameters.keyDeposit
                            } else {
                                0L
                            }
                            if (change < 1) {
                                throw IOException("Not enough funds to pay depositAndFees of $depositAndFees lovelace!")
                            }

                            val realTransaction = transaction.toString()
                                    .replace("--fee 100 ", "--fee $fees ")
                                    .replace("--tx-out ${feePayerAccount.paymentAddr}+1234567890 ", "--tx-out ${feePayerAccount.paymentAddr}+$change ")
                            log.debug("Pool Transaction Command: $realTransaction")
                            defaultHostConnection.command(realTransaction)

                            // 10. Sign the transaction
                            defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley transaction sign --tx-body-file /tmp/transaction.txbody $signingKeys $magicString --out-file /tmp/transaction.txsigned")

                            // 11. Submit the transaction
                            defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley transaction submit --tx-file /tmp/transaction.txsigned --cardano-mode $magicString")
                            val txid = defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley transaction txid --tx-body-file /tmp/transaction.txbody")
                            transactionRepository.save(Transaction(txid = txid))

                            webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success(type = "updatestakingaddress", data = "${stakingAccount.name} ${if (request.isRegistration) "" else "de"}registered. TxId: $txid"))
                        } finally {
                            // Cleanup
                            defaultHostConnection.command("rm -f /tmp/protocol-parameters.json /tmp/transaction.txbody /tmp/transaction.txsigned /tmp/feepayer.payment.skey /tmp/staking.skey /tmp/staking.vkey /tmp/staking.cert /tmp/staking.dereg-cert")
                        }
                    } ?: throw IOException("Host not found for default node!")
                } ?: throw IOException("Genesis file for default node not found!")
            } ?: throw IOException("Default node not found!")
        } catch (e: Throwable) {
            webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Error(type = "updatestakingaddress", exception = e))
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
                hostRepository.findByIdOrNull(defaultNode.hostId)?.let { host ->
                    val hostConnection = HostConnection(host, defaultNode)
                    try {
                        val (pskeyContent, pvkeyContent) = when {
                            request.generateKeys -> {
                                hostConnection.command("${host.cardanoCliPath} shelley address key-gen --verification-key-file /tmp/jormanager-pvkey --signing-key-file /tmp/jormanager-pskey")
                                Pair(
                                        java.io.File("/tmp/jormanager-pskey").source().buffer().use { it.readUtf8() },
                                        java.io.File("/tmp/jormanager-pvkey").source().buffer().use { it.readUtf8() }
                                )
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
                            hostConnection.command("${host.cardanoCliPath} shelley stake-address key-gen --verification-key-file /tmp/jormanager-svkey --signing-key-file /tmp/jormanager-sskey")
                            Pair(
                                    java.io.File("/tmp/jormanager-sskey").source().buffer().use { it.readUtf8() },
                                    java.io.File("/tmp/jormanager-svkey").source().buffer().use { it.readUtf8() }
                            )
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
                            hostConnection.commandWriteFile("/tmp/jormanager-pvkey", pvkeyContent)
                        }
                        hostConnection.commandWriteFile("/tmp/jormanager-svkey", svkeyContent)
                        hostConnection.command("${host.cardanoCliPath} shelley stake-address registration-certificate --staking-verification-key-file /tmp/jormanager-svkey --out-file /tmp/jormanager-regcert").trim()
                        val regcertContent = java.io.File("/tmp/jormanager-regcert").source().buffer().use { it.readUtf8() }
                        val savedRegcertFile = fileRepository.save(
                                File(
                                        name = "${request.name}.staking.cert",
                                        content = regcertContent
                                )
                        )

                        val paymentAddr = if (request.type == "pledge") {
                            request.paymentAddr
                        } else {
                            hostConnection.command("${host.cardanoCliPath} shelley address build --payment-verification-key-file /tmp/jormanager-pvkey --staking-verification-key-file /tmp/jormanager-svkey $magicString").trim()
                        }
                        val stakingAddr = hostConnection.command("${host.cardanoCliPath} shelley stake-address build --staking-verification-key-file /tmp/jormanager-svkey $magicString").trim()
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
                        hostConnection.command("rm -f /tmp/jormanager-pskey /tmp/jormanager-pvkey /tmp/jormanager-sskey /tmp/jormanager-svkey /tmp/jormanager-regcert")
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
                hostRepository.findByIdOrNull(defaultNode.hostId)?.let { host ->
                    val hostConnection = HostConnection(host, defaultNode)
                    try {
                        val (skeyContent, vkeyContent) = if (request.generateKeys) {
                            hostConnection.command("${host.cardanoCliPath} shelley address key-gen --verification-key-file /tmp/jormanager-pvkey --signing-key-file /tmp/jormanager-pskey")
                            Pair(
                                    java.io.File("/tmp/jormanager-pskey").inputStream().bufferedReader().use { it.readText() },
                                    java.io.File("/tmp/jormanager-pvkey").inputStream().bufferedReader().use { it.readText() }
                            )
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

                        hostConnection.commandWriteFile("/tmp/jormanager-pvkey", vkeyContent)
                        val paymentAddr = hostConnection.command("${host.cardanoCliPath} shelley address build --payment-verification-key-file /tmp/jormanager-pvkey $magicString").trim()
                        WalletEntry(
                                name = request.name,
                                type = request.type,
                                paymentAddr = paymentAddr,
                                paymentSkey = savedPaymentSKeyFile,
                                paymentVkey = savedPaymentVKeyFile
                        )
                    } finally {
                        hostConnection.command("rm -f /tmp/jormanager-pskey /tmp/jormanager-pvkey")
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
                walletRepository.save(walletEntry.copy(name = walletEntry.name + "-$now", deleted = true, paymentSkey = paymentSkey, paymentVkey = paymentVkey, stakingRegCert = stakingRegCert, stakingSkey = stakingSkey, stakingVkey = stakingVkey))
            }
            SocketResponse.Success(type = "deletewalletentry", data = "${walletEntry?.name} deleted!")
        } catch (e: Throwable) {
            val error = "Fatal error deleting wallet entry!"
            log.error(error, e)
            SocketResponse.Error(type = "deletewalletentry", exception = e)
        }
    }

    @MessageMapping("/submittransaction")
    @SendTo("/topic/messages")
    @Synchronized
    fun submitTransaction(request: SubmitTransactionRequest): SocketResponse<String> {
        return try {
            nodeRepository.findDefault()?.let { defaultNode ->
                fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)?.let { genesisFile ->
                    val genesis = shelleyGenesisAdapter.fromJson(genesisFile.content)!!
                    val magicString = if (genesis.networkId.equals("testnet", ignoreCase = true)) {
                        "--testnet-magic ${genesis.networkMagic}"
                    } else {
                        "--mainnet"
                    }
                    hostRepository.findByIdOrNull(defaultNode.hostId)?.let { host ->
                        val hostConnection = HostConnection(host, defaultNode)
                        try {
                            val protocolParams = hostConnection.command("${host.cardanoCliPath} shelley query protocol-parameters --cardano-mode $magicString").trim()
                            hostConnection.commandWriteFile("/tmp/protocol-parameters.json", protocolParams)

                            walletRepository.findByIdOrNull(request.fromId)?.let { fromWalletEntry ->
                                val feePayerWalletEntry = if (request.isClaim) {
                                    calculateClaimRewardsFeePayer(host, hostConnection, magicString, request.toAccounts.map { it.account })
                                } else {
                                    fromWalletEntry
                                }

                                val utxos = walletUtils.getUtxos(host, hostConnection, magicString, feePayerWalletEntry.paymentAddr)
                                val transaction = StringBuilder()
                                transaction.append("${host.cardanoCliPath} shelley transaction build-raw ")
                                utxos.forEach { utxo ->
                                    transaction.append("--tx-in ${utxo.hash}#${utxo.ix} ")
                                }

                                val walletItem = walletUtils.getWalletItem(host, hostConnection, magicString, fromWalletEntry)

                                val paymentAddressLovelace = utxos.sumByLong { it.lovelace }
                                var baseAmount = if (request.isClaim) {
                                    walletItem.stakingAddrLovelace ?: -1L
                                } else {
                                    paymentAddressLovelace - request.txFee
                                }

                                var alreadySpentPercentages = 0L
                                request.toAccounts.forEach { account ->
                                    walletRepository.findByIdOrNull(account.account)?.let { walletEntry ->
                                        when (account.type) {
                                            "amount" -> {
                                                var claimAmount = 0L
                                                val amount = if (request.isClaim && walletEntry.id == feePayerWalletEntry.id) {
                                                    // Reimburse payer for the txFee when claiming rewards
                                                    claimAmount = paymentAddressLovelace - request.txFee
                                                    log.debug("claimAmount: $claimAmount")
                                                    account.amount!! + request.txFee
                                                } else {
                                                    account.amount!!
                                                }

                                                transaction.append("--tx-out ${walletEntry.paymentAddr}+${amount + claimAmount} ")
                                                baseAmount -= amount
                                                // reset percentages since this is an amount
                                                alreadySpentPercentages = 0L
                                            }
                                            "percent" -> {
                                                var amount = round(baseAmount * (account.percent!! / (100.0 - alreadySpentPercentages))).toLong()
                                                baseAmount -= amount
                                                alreadySpentPercentages += account.percent
                                                if (alreadySpentPercentages == 100L) {
                                                    alreadySpentPercentages = 0L
                                                }
                                                var claimAmount = 0L
                                                if (request.isClaim && walletEntry.id == feePayerWalletEntry.id) {
                                                    if (baseAmount >= request.txFee) {
                                                        // We have money available to reimburse the fee to the payer
                                                        amount += request.txFee
                                                        baseAmount -= request.txFee
                                                    }
                                                    claimAmount = paymentAddressLovelace - request.txFee
                                                    log.debug("claimAmount: $claimAmount")
                                                }
                                                transaction.append("--tx-out ${walletEntry.paymentAddr}+${amount + claimAmount} ")
                                            }
                                            else -> {
                                                throw IllegalArgumentException("Unknown account type: ${account.type}")
                                            }
                                        }
                                    } ?: throw IOException("Wallet entry id ${account.account} not found!")
                                }
                                val remaining = baseAmount
                                if (remaining > 0) {
                                    transaction.append("--tx-out ${fromWalletEntry.paymentAddr}+$remaining ")

                                    // Sanity check. Should never happen if we did things right
                                    if (request.isClaim) {
                                        throw IOException("We had a remaining balance when claiming rewards!!!")
                                    }
                                }

                                val queryTipString = hostConnection.command("${host.cardanoCliPath} shelley query tip $magicString").trim()
                                val ttl = queryTipAdapter.fromJson(queryTipString)?.let { it.slotNo + 1000 } ?: -1
                                transaction.append("--ttl $ttl ")
                                transaction.append("--fee ${request.txFee} ")
                                if (request.isClaim) {
                                    transaction.append("--withdrawal ${fromWalletEntry.stakingAddr}+${walletItem.stakingAddrLovelace} ")
                                }
                                transaction.append("--out-file /tmp/transaction.txbody")

                                // build the transaction
                                hostConnection.command(transaction.toString())

                                // sign the transaction
                                feePayerWalletEntry.paymentSkey?.let { skey ->
                                    val skeyContent = walletUtils.getSKeyContent(skey, request.spendingPassword)
                                    hostConnection.commandWriteFile("/tmp/signing.skey", skeyContent)
                                } ?: throw IllegalArgumentException("Couldn't find payment skey for transaction")
                                if (request.isClaim) {
                                    fromWalletEntry.stakingSkey?.let { skey ->
                                        val skeyContent = walletUtils.getSKeyContent(skey, request.spendingPassword)
                                        hostConnection.commandWriteFile("/tmp/staking_signing.skey", skeyContent)
                                    }
                                            ?: throw IllegalArgumentException("Couldn't find staking skey for transaction")
                                    hostConnection.command("${host.cardanoCliPath} shelley transaction sign --tx-body-file /tmp/transaction.txbody --signing-key-file /tmp/signing.skey --signing-key-file /tmp/staking_signing.skey $magicString --out-file /tmp/transaction.txsigned")
                                } else {
                                    hostConnection.command("${host.cardanoCliPath} shelley transaction sign --tx-body-file /tmp/transaction.txbody --signing-key-file /tmp/signing.skey $magicString --out-file /tmp/transaction.txsigned")
                                }

                                // submit the transaction
                                hostConnection.command("${host.cardanoCliPath} shelley transaction submit --tx-file /tmp/transaction.txsigned --cardano-mode $magicString")
                                val txid = hostConnection.command("${host.cardanoCliPath} shelley transaction txid --tx-body-file /tmp/transaction.txbody")

                                transactionRepository.save(Transaction(txid = txid))

                                SocketResponse.Success(type = "submittransaction", data = "transaction succeeded: $txid")
                            } ?: throw IOException("Wallet entry id ${request.fromId} not found!")
                        } finally {
                            hostConnection.command("rm -f /tmp/protocol-parameters.json /tmp/signing.skey /tmp/staking_signing.skey /tmp/transaction.txbody /tmp/transaction.txsigned")
                        }
                    } ?: throw IOException("Host not found for default node!")
                } ?: throw IOException("Genesis file for default node not found!")
            } ?: throw IOException("No default node!")
        } catch (e: Throwable) {
            val error = "Fatal error submitting transaction!"
            log.error(error, e)
            SocketResponse.Error(type = "submittransaction", exception = e)
        }
    }

    /**
     * The first account with at least 1 Ada (1m lovelace) is the fee payer
     */
    private fun calculateClaimRewardsFeePayer(host: Host, hostConnection: HostConnection, magicString: String, toAccounts: List<Long?>): WalletEntry {
        toAccounts.filterNotNull().forEach { id ->
            walletRepository.findByIdOrNull(id)?.let { walletEntry ->
                if (walletEntry.paymentSkey != null) {
                    val lovelace = walletUtils.getUtxos(host, hostConnection, magicString, walletEntry.paymentAddr).sumByLong { it.lovelace }
                    if (lovelace >= 1_000_000) {
                        return walletEntry
                    }
                }
            } ?: throw IOException("Wallet entry id $id not found!")
        }
        throw IOException("No Account found capable of covering the claim fee!")
    }
}