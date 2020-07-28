package com.swiftmako.jormanager.controllers

import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.ktx.sumByLong
import com.swiftmako.jormanager.model.CalculateFeeRequest
import com.swiftmako.jormanager.model.CreateWalletEntryRequest
import com.swiftmako.jormanager.model.Genesis
import com.swiftmako.jormanager.model.QueryTip
import com.swiftmako.jormanager.model.SubmitTransactionRequest
import com.swiftmako.jormanager.model.WalletItem
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.round

@Controller
class WalletController @Autowired constructor(
        private val walletUtils: WalletUtils,
        private val walletRepository: WalletRepository,
        private val fileRepository: FileRepository,
        private val nodeRepository: NodeRepository,
        private val hostRepository: HostRepository,
        private val moshi: Moshi
) {

    private val log = LoggerFactory.getLogger(WalletController::class.java)
    private val queryTipAdapter by lazy { moshi.adapter(QueryTip::class.java) }

    @MessageMapping("/wallet")
    @SendTo("/topic/messages")
    fun getWalletEntries(): SocketResponse<List<WalletItem>> {
        return try {
            SocketResponse.Success(type = "wallet", data = walletUtils.getWalletItems())
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
                    val genesis = moshi.adapter(Genesis::class.java).fromJson(genesisFile.content)
                    val magicString = if (genesis?.networkMagic == 42) {
                        "--testnet-magic ${genesis.networkMagic}"
                    } else {
                        "--mainnet"
                    }
                    hostRepository.findByIdOrNull(defaultNode.hostId)?.let { host ->
                        HostConnection(host, defaultNode).use { hostConnection ->
                            val protocolParams = hostConnection.command("${host.cardanoCliPath} shelley query protocol-parameters --cardano-mode $magicString").trim()
                            hostConnection.commandWriteFile("/tmp/protocol-parameters.json", protocolParams)

                            walletRepository.findByIdOrNull(request.fromId)?.let { fromWalletEntry ->

                                val feePayerWalletEntry = if (request.isClaim) {
                                    calculateClaimRewardsFeePayer(host, hostConnection, request.toAccounts)
                                } else {
                                    fromWalletEntry
                                }

                                val utxos = walletUtils.getUtxos(host, hostConnection, feePayerWalletEntry.paymentAddr)
                                val dummyTransaction = StringBuilder()
                                dummyTransaction.append("${host.cardanoCliPath} shelley transaction build-raw ")
                                utxos.forEach { utxo ->
                                    dummyTransaction.append("--tx-in ${utxo.hash}#${utxo.ix} ")
                                }
                                repeat(request.txOut) {
                                    dummyTransaction.append("--tx-out addr1qyftuwe6fww2eeg2k5quyzp099f5y6pn0snkneu5fdq6puqjuycagppd9yw3kc62xjld0c45a2ljc6d3tlnh96fut8ss67heqw+0 ")
                                }
                                val queryTipString = hostConnection.command("${host.cardanoCliPath} shelley query tip $magicString").trim()
                                val ttl = queryTipAdapter.fromJson(queryTipString)?.let { it.slotNo + 1000 } ?: -1

                                if (request.isClaim) {
                                    val walletItem = walletUtils.getWalletItem(host, hostConnection, fromWalletEntry)
                                    dummyTransaction.append("--ttl $ttl --fee 0 --withdrawal ${fromWalletEntry.stakingAddr}+${walletItem.stakingAddrLovelace} --out-file /tmp/dummy.txbody")
                                } else {
                                    dummyTransaction.append("--ttl $ttl --fee 0 --out-file /tmp/dummy.txbody")
                                }
                                hostConnection.command(dummyTransaction.toString())

                                val fee = if (request.isClaim) {
                                    hostConnection.command("${host.cardanoCliPath} shelley transaction calculate-min-fee --tx-body-file /tmp/dummy.txbody --protocol-params-file /tmp/protocol-parameters.json --tx-in-count ${utxos.size} --tx-out-count ${request.txOut} $magicString --witness-count 2 --byron-witness-count 0").trim()
                                } else {
                                    hostConnection.command("${host.cardanoCliPath} shelley transaction calculate-min-fee --tx-body-file /tmp/dummy.txbody --protocol-params-file /tmp/protocol-parameters.json --tx-in-count ${utxos.size} --tx-out-count ${request.txOut} $magicString --witness-count 1 --byron-witness-count 0").trim()
                                }
                                hostConnection.command("rm -f /tmp/protocol-parameters.json")
                                hostConnection.command("rm -f /tmp/dummy.txbody")
                                val lovelace = fee.split(" ")[0].toLong()
                                SocketResponse.Success(type = "calculatefee", data = lovelace)
                            } ?: throw IOException("Wallet entry id ${request.fromId} not found!")
                        }
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
                    // "stake"
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

    private fun createStakeWalletEntry(request: CreateWalletEntryRequest): WalletEntry {
        return nodeRepository.findDefault()?.let { defaultNode ->
            fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)?.let { genesisFile ->
                val genesis = moshi.adapter(Genesis::class.java).fromJson(genesisFile.content)
                val magicString = //if (genesis?.networkMagic != null) {
//                    "--testnet-magic ${genesis.networkMagic}"
//                } else {
                        "--mainnet"
//                }
                hostRepository.findByIdOrNull(defaultNode.hostId)?.let { host ->
                    HostConnection(host, defaultNode).use { hostConnection ->
                        val (pskeyContent, pvkeyContent) = if (request.generateKeys) {
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
                                        content = pskeyContent
                                )
                        )
                        val savedPaymentVKeyFile = fileRepository.save(
                                File(
                                        name = "${request.name}.payment.vkey",
                                        content = pvkeyContent
                                )
                        )

                        val (sskeyContent, svkeyContent) = if (request.generateKeys) {
                            hostConnection.command("${host.cardanoCliPath} shelley stake-address key-gen --verification-key-file /tmp/jormanager-svkey --signing-key-file /tmp/jormanager-sskey")
                            Pair(
                                    java.io.File("/tmp/jormanager-sskey").inputStream().bufferedReader().use { it.readText() },
                                    java.io.File("/tmp/jormanager-svkey").inputStream().bufferedReader().use { it.readText() }
                            )
                        } else {
                            Pair(request.stakingSKey!!, request.stakingVKey!!)
                        }

                        val savedStakingSKeyFile = fileRepository.save(
                                File(
                                        name = "${request.name}.staking.skey",
                                        content = sskeyContent
                                )
                        )
                        val savedStakingVKeyFile = fileRepository.save(
                                File(
                                        name = "${request.name}.staking.vkey",
                                        content = svkeyContent
                                )
                        )

                        hostConnection.commandWriteFile("/tmp/jormanager-pvkey", pvkeyContent)
                        hostConnection.commandWriteFile("/tmp/jormanager-svkey", svkeyContent)
                        hostConnection.command("${host.cardanoCliPath} shelley stake-address registration-certificate --staking-verification-key-file /tmp/jormanager-svkey --out-file /tmp/jormanager-regcert").trim()
                        val regcertContent = java.io.File("/tmp/jormanager-regcert").inputStream().bufferedReader().use { it.readText() }
                        val savedRegcertFile = fileRepository.save(
                                File(
                                        name = "${request.name}.staking.cert",
                                        content = regcertContent
                                )
                        )

                        val paymentAddr = hostConnection.command("${host.cardanoCliPath} shelley address build --payment-verification-key-file /tmp/jormanager-pvkey --staking-verification-key-file /tmp/jormanager-svkey $magicString").trim()
                        val stakingAddr = hostConnection.command("${host.cardanoCliPath} shelley stake-address build --staking-verification-key-file /tmp/jormanager-svkey $magicString").trim()
                        hostConnection.command("rm -f /tmp/jormanager-pskey")
                        hostConnection.command("rm -f /tmp/jormanager-pvkey")
                        hostConnection.command("rm -f /tmp/jormanager-sskey")
                        hostConnection.command("rm -f /tmp/jormanager-svkey")
                        hostConnection.command("rm -f /tmp/jormanager-regcert")
                        WalletEntry(
                                name = request.name,
                                type = "stake",
                                paymentAddr = paymentAddr,
                                paymentSkey = savedPaymentSKeyFile,
                                paymentVkey = savedPaymentVKeyFile,
                                stakingAddr = stakingAddr,
                                stakingSkey = savedStakingSKeyFile,
                                stakingVkey = savedStakingVKeyFile,
                                stakingRegCert = savedRegcertFile
                        )
                    }
                } ?: throw IOException("Host not found for default node!")
            } ?: throw IOException("Genesis file for default node not found!")
        } ?: throw IOException("No default node!")
    }

    private fun createPaymentWalletEntry(request: CreateWalletEntryRequest): WalletEntry {
        return nodeRepository.findDefault()?.let { defaultNode ->
            fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)?.let { genesisFile ->
//                val genesis = moshi.adapter(Genesis::class.java).fromJson(genesisFile.content)
                val magicString = /*if (genesis?.networkMagic != null) {
                    "--testnet-magic ${genesis.networkMagic}"
                } else {*/
                        "--mainnet"
//                }
                hostRepository.findByIdOrNull(defaultNode.hostId)?.let { host ->
                    HostConnection(host, defaultNode).use { hostConnection ->
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
                                        content = skeyContent
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
                        hostConnection.command("rm -f /tmp/jormanager-pskey")
                        hostConnection.command("rm -f /tmp/jormanager-pvkey")
                        WalletEntry(
                                name = request.name,
                                type = request.type,
                                paymentAddr = paymentAddr,
                                paymentSkey = savedPaymentSKeyFile,
                                paymentVkey = savedPaymentVKeyFile
                        )
                    }
                } ?: throw IOException("Host not found for default node!")
            } ?: throw IOException("Genesis file for default node not found!")
        } ?: throw IOException("No default node!")
    }

    @MessageMapping("/deletewalletentry")
    @SendTo("/topic/messages")
    @Transactional
    fun deleteWalletEntry(id: Long): SocketResponse<String> {
        return try {
            val walletEntry = walletRepository.findByIdOrNull(id)
            if (walletEntry != null) {
                walletRepository.save(walletEntry.copy(name = walletEntry.name + "-${System.currentTimeMillis()}", deleted = true))
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
                    val genesis = moshi.adapter(Genesis::class.java).fromJson(genesisFile.content)
                    val magicString = if (genesis?.networkMagic == 42) {
                        "--testnet-magic ${genesis.networkMagic}"
                    } else {
                        "--mainnet"
                    }
                    hostRepository.findByIdOrNull(defaultNode.hostId)?.let { host ->
                        HostConnection(host, defaultNode).use { hostConnection ->
                            val protocolParams = hostConnection.command("${host.cardanoCliPath} shelley query protocol-parameters --cardano-mode $magicString").trim()
                            hostConnection.commandWriteFile("/tmp/protocol-parameters.json", protocolParams)

                            walletRepository.findByIdOrNull(request.fromId)?.let { fromWalletEntry ->
                                val feePayerWalletEntry = if (request.isClaim) {
                                    calculateClaimRewardsFeePayer(host, hostConnection, request.toAccounts.map { it.account })
                                } else {
                                    fromWalletEntry
                                }

                                val utxos = walletUtils.getUtxos(host, hostConnection, feePayerWalletEntry.paymentAddr)
                                val transaction = StringBuilder()
                                transaction.append("${host.cardanoCliPath} shelley transaction build-raw ")
                                utxos.forEach { utxo ->
                                    transaction.append("--tx-in ${utxo.hash}#${utxo.ix} ")
                                }

                                val walletItem = walletUtils.getWalletItem(host, hostConnection, fromWalletEntry)

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
                                log.info(transaction.toString())
                                hostConnection.command(transaction.toString())

                                // sign the transaction
                                feePayerWalletEntry.paymentSkey?.let { skey ->
                                    hostConnection.commandWriteFile("/tmp/signing.skey", skey.content)
                                } ?: throw IllegalArgumentException("Couldn't find payment skey for transaction")
                                if (request.isClaim) {
                                    fromWalletEntry.stakingSkey?.let { skey ->
                                        hostConnection.commandWriteFile("/tmp/staking_signing.skey", skey.content)
                                    } ?: throw IllegalArgumentException("Couldn't find staking skey for transaction")
                                    hostConnection.command("${host.cardanoCliPath} shelley transaction sign --tx-body-file /tmp/transaction.txbody --signing-key-file /tmp/signing.skey --signing-key-file /tmp/staking_signing.skey $magicString --out-file /tmp/transaction.txsigned")
                                } else {
                                    hostConnection.command("${host.cardanoCliPath} shelley transaction sign --tx-body-file /tmp/transaction.txbody --signing-key-file /tmp/signing.skey $magicString --out-file /tmp/transaction.txsigned")
                                }

                                // submit the transaction
                                hostConnection.command("${host.cardanoCliPath} shelley transaction submit --tx-file /tmp/transaction.txsigned --cardano-mode $magicString")

                                hostConnection.command("rm -f /tmp/protocol-parameters.json")
                                hostConnection.command("rm -f /tmp/signing.skey")
                                hostConnection.command("rm -f /tmp/staking_signing.skey")
                                hostConnection.command("rm -f /tmp/transaction.txbody")
                                hostConnection.command("rm -f /tmp/transaction.txsigned")

                                SocketResponse.Success(type = "submittransaction", data = "transaction succeeded.")
                            } ?: throw IOException("Wallet entry id ${request.fromId} not found!")
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

    @GetMapping("/jormanager_wallet.zip")
    fun walletBackup(): ResponseEntity<Resource> {
        ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zipOutputStream ->
                val walletEntries = walletRepository.findAll()
                walletEntries.forEach { walletEntry ->
                    val paymentAddrZipEntry = ZipEntry("${walletEntry.name}.addr")
                    zipOutputStream.putNextEntry(paymentAddrZipEntry)
                    zipOutputStream.write(walletEntry.paymentAddr.toByteArray())

                    walletEntry.paymentSkey?.let { paymentSkey ->
                        val paymentSkeyZipEntry = ZipEntry("${walletEntry.name}.payment.skey")
                        zipOutputStream.putNextEntry(paymentSkeyZipEntry)
                        zipOutputStream.write(paymentSkey.content.toByteArray())
                    }

                    walletEntry.paymentVkey?.let { paymentVkey ->
                        val paymentVkeyZipEntry = ZipEntry("${walletEntry.name}.payment.vkey")
                        zipOutputStream.putNextEntry(paymentVkeyZipEntry)
                        zipOutputStream.write(paymentVkey.content.toByteArray())
                    }

                    walletEntry.stakingAddr?.let { stakingAddr ->
                        val stakingAddrZipEntry = ZipEntry("${walletEntry.name}.staking.addr")
                        zipOutputStream.putNextEntry(stakingAddrZipEntry)
                        zipOutputStream.write(stakingAddr.toByteArray())
                    }

                    walletEntry.stakingSkey?.let { stakingSkey ->
                        val stakingSkeyZipEntry = ZipEntry("${walletEntry.name}.staking.skey")
                        zipOutputStream.putNextEntry(stakingSkeyZipEntry)
                        zipOutputStream.write(stakingSkey.content.toByteArray())
                    }

                    walletEntry.stakingVkey?.let { stakingVkey ->
                        val stakingVkeyZipEntry = ZipEntry("${walletEntry.name}.staking.vkey")
                        zipOutputStream.putNextEntry(stakingVkeyZipEntry)
                        zipOutputStream.write(stakingVkey.content.toByteArray())
                    }
                }
            }
            bos.flush()
            val zipBytes = bos.toByteArray()
            return ResponseEntity
                    .ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(zipBytes.size.toLong())
                    .body(ByteArrayResource(zipBytes))
        }
    }

    /**
     * The first account with at least 1 Ada (1m lovelace) is the fee payer
     */
    private fun calculateClaimRewardsFeePayer(host: Host, hostConnection: HostConnection, toAccounts: List<Long?>): WalletEntry {
        toAccounts.filterNotNull().forEach { id ->
            walletRepository.findByIdOrNull(id)?.let { walletEntry ->
                if (walletEntry.paymentSkey != null) {
                    val lovelace = walletUtils.getUtxos(host, hostConnection, walletEntry.paymentAddr).sumByLong { it.lovelace }
                    if (lovelace >= 1_000_000) {
                        return walletEntry
                    }
                }
            } ?: throw IOException("Wallet entry id $id not found!")
        }
        throw IOException("No Account found capable of covering the claim fee!")
    }
}