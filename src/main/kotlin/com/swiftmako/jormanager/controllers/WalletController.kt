package com.swiftmako.jormanager.controllers

import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.File
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
import kotlin.math.floor

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
                    val magicString = if (genesis?.networkMagic != null) {
                        "--testnet-magic ${genesis.networkMagic}"
                    } else {
                        "--mainnet"
                    }
                    hostRepository.findByIdOrNull(defaultNode.hostId)?.let { host ->
                        HostConnection(host, defaultNode).use { hostConnection ->
                            val protocolParams = hostConnection.command("${host.cardanoCliPath} shelley query protocol-parameters --cardano-mode $magicString").trim()
                            hostConnection.commandWriteFile("/tmp/protocol-parameters.json", protocolParams)

                            val utxos = walletUtils.getUtxos(host, hostConnection, request.fromAddress)
                            val dummyTransaction = StringBuilder()
                            dummyTransaction.append("${host.cardanoCliPath} shelley transaction build-raw ")
                            utxos.forEach { utxo ->
                                dummyTransaction.append("--tx-in ${utxo.hash}#${utxo.ix} ")
                            }
                            repeat(request.txOut) {
                                dummyTransaction.append("--tx-out addr1vyde3cg6cccdzxf4szzpswgz53p8m3r4hu76j3zw0tagyvgdy3s4p+10 ")
                            }
                            dummyTransaction.append("--ttl 0 --fee 0 --out-file /tmp/dummy.txbody")
                            hostConnection.command(dummyTransaction.toString())

                            val fee = hostConnection.command("${host.cardanoCliPath} shelley transaction calculate-min-fee --tx-body-file /tmp/dummy.txbody --protocol-params-file /tmp/protocol-parameters.json --tx-in-count ${utxos.size} --tx-out-count ${request.txOut} $magicString --witness-count 1 --byron-witness-count 0").trim()
                            hostConnection.command("rm -f /tmp/protocol-parameters.json")
                            hostConnection.command("rm -f /tmp/dummy.txbody")
                            val lovelace = fee.split(" ")[0].toLong()
                            SocketResponse.Success(type = "calculatefee", data = lovelace)
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
                    val magicString = if (genesis?.networkMagic != null) {
                        "--testnet-magic ${genesis.networkMagic}"
                    } else {
                        "--mainnet"
                    }
                    hostRepository.findByIdOrNull(defaultNode.hostId)?.let { host ->
                        HostConnection(host, defaultNode).use { hostConnection ->
                            val protocolParams = hostConnection.command("${host.cardanoCliPath} shelley query protocol-parameters --cardano-mode $magicString").trim()
                            hostConnection.commandWriteFile("/tmp/protocol-parameters.json", protocolParams)

                            val fromAccount = walletRepository.findByIdOrNull(request.fromId)!!
                            val utxos = walletUtils.getUtxos(host, hostConnection, fromAccount.paymentAddr)
                            val transaction = StringBuilder()
                            transaction.append("${host.cardanoCliPath} shelley transaction build-raw ")
                            utxos.forEach { utxo ->
                                transaction.append("--tx-in ${utxo.hash}#${utxo.ix} ")
                            }

                            var baseAmount = utxos.sumByLong { it.lovelace } - request.txFee
                            var alreadySpentPercentages = 0L
                            request.toAccounts.forEach { account ->
                                val walletEntry = walletRepository.findByIdOrNull(account.account)!!
                                when (account.type) {
                                    "amount" -> {
                                        transaction.append("--tx-out ${walletEntry.paymentAddr}+${account.amount} ")
                                        baseAmount -= account.amount!!
                                        if (alreadySpentPercentages > 0) {
                                            baseAmount -= alreadySpentPercentages
                                            alreadySpentPercentages = 0
                                        }
                                    }
                                    "percent" -> {
                                        val amount = floor(baseAmount * (account.percent!! / 100.0)).toLong()
                                        transaction.append("--tx-out ${walletEntry.paymentAddr}+${amount} ")
                                        alreadySpentPercentages += amount
                                    }
                                    else -> {
                                        throw IllegalArgumentException("Unknown account type: ${account.type}")
                                    }
                                }
                            }
                            val remaining = baseAmount - alreadySpentPercentages
                            if (remaining > 0) {
                                transaction.append("--tx-out ${fromAccount.paymentAddr}+$remaining ")
                            }

                            val queryTipString = hostConnection.command("${host.cardanoCliPath} shelley query tip $magicString").trim()
                            val ttl = queryTipAdapter.fromJson(queryTipString)?.let { it.slotNo + 1000 } ?: -1
                            transaction.append("--ttl $ttl ")
                            transaction.append("--fee ${request.txFee} ")
                            transaction.append("--out-file /tmp/transaction.txbody")
                            // build the transaction
                            hostConnection.command(transaction.toString())
                            // sign the transaction
                            fromAccount.paymentSkey?.let { skey ->
                                hostConnection.commandWriteFile("/tmp/signing.skey", skey.content)
                            } ?: throw IllegalArgumentException("Couldn't find signing key for transaction")
                            hostConnection.command("${host.cardanoCliPath} shelley transaction sign --tx-body-file /tmp/transaction.txbody --signing-key-file /tmp/signing.skey $magicString --out-file /tmp/transaction.txsigned")
                            // submit the transaction
                            hostConnection.command("${host.cardanoCliPath} shelley transaction submit --tx-file /tmp/transaction.txsigned --cardano-mode $magicString")

                            hostConnection.command("rm -f /tmp/protocol-parameters.json")
                            hostConnection.command("rm -f /tmp/signing.skey")
                            hostConnection.command("rm -f /tmp/transaction.txbody")
                            hostConnection.command("rm -f /tmp/transaction.txsigned")

                            SocketResponse.Success(type = "submittransaction", data = "transaction succeeded.")
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
                        val paymentSkeyZipEntry = ZipEntry(paymentSkey.name)
                        zipOutputStream.putNextEntry(paymentSkeyZipEntry)
                        zipOutputStream.write(paymentSkey.content.toByteArray())
                    }

                    walletEntry.paymentVkey?.let { paymentVkey ->
                        val paymentVkeyZipEntry = ZipEntry(paymentVkey.name)
                        zipOutputStream.putNextEntry(paymentVkeyZipEntry)
                        zipOutputStream.write(paymentVkey.content.toByteArray())
                    }

                    walletEntry.stakingAddr?.let { stakingAddr ->
                        val stakingAddrZipEntry = ZipEntry("${walletEntry.name}.staking.addr")
                        zipOutputStream.putNextEntry(stakingAddrZipEntry)
                        zipOutputStream.write(stakingAddr.toByteArray())
                    }

                    walletEntry.stakingSkey?.let { stakingSkey ->
                        val stakingSkeyZipEntry = ZipEntry(stakingSkey.name)
                        zipOutputStream.putNextEntry(stakingSkeyZipEntry)
                        zipOutputStream.write(stakingSkey.content.toByteArray())
                    }

                    walletEntry.stakingVkey?.let { stakingVkey ->
                        val stakingVkeyZipEntry = ZipEntry(stakingVkey.name)
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
}