package com.swiftmako.jormanager.controllers

import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.model.CreateWalletEntryRequest
import com.swiftmako.jormanager.model.Genesis
import com.swiftmako.jormanager.model.WalletItem
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.io.IOException

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
            fileRepository.findByIdOrNull(defaultNode.genesisFileId)?.let { genesisFile ->
                val genesis = moshi.adapter(Genesis::class.java).fromJson(genesisFile.content)
                val magicString = if (genesis?.networkMagic != null) {
                    "--testnet-magic ${genesis.networkMagic}"
                } else {
                    "--mainnet"
                }
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
                        val paymentAddr = hostConnection.command("${host.cardanoCliPath} shelley address build --payment-verification-key-file /tmp/jormanager-pvkey --staking-verification-key-file /tmp/jormanager-svkey $magicString").trim()
                        val stakingAddr = hostConnection.command("${host.cardanoCliPath} shelley stake-address build --staking-verification-key-file /tmp/jormanager-svkey $magicString").trim()
                        hostConnection.command("rm -f /tmp/jormanager-pskey")
                        hostConnection.command("rm -f /tmp/jormanager-pvkey")
                        hostConnection.command("rm -f /tmp/jormanager-sskey")
                        hostConnection.command("rm -f /tmp/jormanager-svkey")
                        WalletEntry(
                                name = request.name,
                                type = "stake",
                                paymentAddr = paymentAddr,
                                paymentSkey = savedPaymentSKeyFile,
                                paymentVkey = savedPaymentVKeyFile,
                                stakingAddr = stakingAddr,
                                stakingSkey = savedStakingSKeyFile,
                                stakingVkey = savedStakingVKeyFile
                        )
                    }
                } ?: throw IOException("Host not found for default node!")
            } ?: throw IOException("Genesis file for default node not found!")
        } ?: throw IOException("No default node!")
    }

    private fun createPaymentWalletEntry(request: CreateWalletEntryRequest): WalletEntry {
        return nodeRepository.findDefault()?.let { defaultNode ->
            fileRepository.findByIdOrNull(defaultNode.genesisFileId)?.let { genesisFile ->
                val genesis = moshi.adapter(Genesis::class.java).fromJson(genesisFile.content)
                val magicString = if (genesis?.networkMagic != null) {
                    "--testnet-magic ${genesis.networkMagic}"
                } else {
                    "--mainnet"
                }
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
}