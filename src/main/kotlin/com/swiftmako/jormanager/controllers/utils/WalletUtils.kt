package com.swiftmako.jormanager.controllers.utils

import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborReader
import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.sumByBigInteger
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.ProtocolParameters
import com.swiftmako.jormanager.model.RegCert
import com.swiftmako.jormanager.model.StakeAddressInfo
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.model.WalletItem
import com.swiftmako.jormanager.model.toNativeAssetMap
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.BlockFetchProtocol
import com.swiftmako.jormanager.nodeclient.protocols.chainsync.ChainSyncProtocol
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import java.math.BigInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Scope
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.encrypt.Encryptors
import org.springframework.stereotype.Component

@Component
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
class WalletUtils
    @Autowired
    constructor(
        private val hostRepository: HostRepository,
        private val nodeRepository: NodeRepository,
        private val walletRepository: WalletRepository,
        private val fileRepository: FileRepository,
        private val argon2PasswordEncoder: Argon2PasswordEncoder,
        @param:Value("\${jormanager.spendingpassword}") private val spendingPasswordHash: String,
        private val stakingInfoAdapter: JsonAdapter<List<StakeAddressInfo>>,
        private val queryUtxoJsonAdapter: JsonAdapter<List<Utxo>>,
        private val regCertAdapter: JsonAdapter<RegCert>,
        private val ledgerDao: LedgerDao,
    ) {
        private val log by lazy { LoggerFactory.getLogger("WalletUtils") }

        suspend fun getWalletItems(
            magicString: String,
            era: String
        ): List<WalletItem> {
            val walletItems = mutableListOf<WalletItem>()
            nodeRepository.findDefault()?.let { defaultNode ->
                hostRepository.findByIdOrNull(defaultNode.hostId)?.let { host ->
                    val hostConnection = HostConnection(host, defaultNode)
                    val socketPath = "--socket-path ${host.nodeHomePath}/${defaultNode.name}/db/socket"
                    val walletEntries = walletRepository.findAllNotDeleted()
                    val total = walletEntries.size
                    walletEntries.forEachIndexed { index, walletEntry ->
                        walletItems.add(
                            getWalletItem(host, hostConnection, magicString, socketPath, era, walletEntry, index + 1, total)
                        )
                        delay(1L) // check to see if we're interrupted
                    }
                } ?: log.error("Host for default node not found!")
            } ?: log.warn("No default node set! Cannot check wallet for updates!")

            return walletItems
        }

        fun getWalletItem(
            host: Host,
            hostConnection: HostConnection,
            magicString: String,
            socketPath: String,
            era: String,
            walletEntry: WalletEntry,
            index: Int? = null,
            total: Int? = null,
        ): WalletItem {
            val utxos = getUtxos(host, hostConnection, magicString, socketPath, era, walletEntry.paymentAddr, index, total)

            // find staking_addr balance
            val start = System.currentTimeMillis()
            val stakingInfoString =
                if (walletEntry.type == "stake" || walletEntry.type == "pledge") {
                    try {
                        hostConnection.command(
                            "${host.cardanoCliPath} $era query stake-address-info --address ${walletEntry.stakingAddr} $magicString $socketPath --output-json"
                        )
                    } catch (t: Throwable) {
                        if (t.message?.contains("EraMismatch") == false) {
                            log.error("Error getting stake addr info!", t)
                        }
                        ""
                    }
                } else {
                    null
                }
            val stakingAddrLovelace =
                stakingInfoString?.let { json ->
                    try {
                        val stakeAddressInfos = stakingInfoAdapter.fromJson(json)
                        if (stakeAddressInfos?.isNotEmpty() == true) {
                            stakeAddressInfos.sumByBigInteger { it.rewardAccountBalance }
                        } else {
                            null
                        }
                    } catch (t: Throwable) {
                        log.warn("Error parsing staking info json: $json", t)
                        null
                    }
                }

            (System.currentTimeMillis() - start).takeIf { it > 1000L }?.let {
                log.warn("stakeAddressInfo: ${walletEntry.stakingAddr}, ${it}ms")
            }

            val stakingAddrRegistered = stakingAddrLovelace != null

            return WalletItem(
                walletEntry.id!!,
                walletEntry.name,
                walletEntry.type,
                walletEntry.paymentAddr,
                walletEntry.paymentSkey != null,
                utxos.size.toLong(),
                utxos.sumByBigInteger { it.lovelace },
                walletEntry.stakingAddr,
                stakingAddrRegistered,
                stakingAddrLovelace ?: BigInteger.ZERO,
                utxos.toNativeAssetMap(),
            )
        }

        fun getUtxos(
            host: Host,
            hostConnection: HostConnection,
            magicString: String,
            socketPath: String,
            era: String,
            paymentAddr: String,
            index: Int? = null,
            total: Int? = null,
        ): List<Utxo> =
            if (BlockFetchProtocol.isTip) {
                runBlocking {
                    ledgerDao.queryLiveUtxos(paymentAddr)
                }
//        } else if (ChainSyncProtocol.isTip) {
//            log.warn("Not on tip! running old queryUtxo()!")
//            // find payment_addr balance
//            val start = System.currentTimeMillis()
//            val addressInfoJson =
//                try {
//                    hostConnection
//                        .command(
//                            "${host.cardanoCliPath} $era query utxo --address $paymentAddr $magicString $socketPath --output-json"
//                        ).trim()
//                } catch (t: Throwable) {
//                    if (t.message?.contains("EraMismatch") == false) {
//                        log.error("Error getting payment addr info!", t)
//                    }
//                    ""
//                }
//            val utxos =
//                try {
//                    queryUtxoJsonAdapter.fromJson(addressInfoJson)
//                } catch (e: Throwable) {
//                    log.error("Failed to query utxos for address: $paymentAddr, json: $addressInfoJson")
//                    throw e
//                }
//
//            (System.currentTimeMillis() - start).takeIf { it > 1000L }?.let {
//                log.warn("queryUtxo $index of $total: $paymentAddr, ${it}ms")
//            }
//
//            utxos ?: emptyList()
            } else {
                log.warn("Not on tip! skipping queryUtxo()!")
                emptyList()
            }

        fun upgradeShelleyRegcertToConway(
            regCertFile: File,
            protocolParameters: ProtocolParameters
        ): File {
            log.info("Upgrading Shelley staking reg cert to Conway")
            val regCert = regCertAdapter.fromJson(regCertFile.content)!!
            val array =
                (
                    CborReader
                        .createFromByteArray(
                            regCert.cborHex.hexToByteArray()
                        ).readDataItem() as CborArray
                ).elementAt(1) as CborArray
            val regCertConwayJson =
                regCertAdapter.toJson(
                    RegCert(
                        type = "CertificateConway",
                        description = "Stake Address Registration Certificate",
                        cborHex =
                            CborArray
                                .create()
                                .apply {
                                    add(CborInteger.create(7))
                                    add(array)
                                    add(CborInteger.create(protocolParameters.stakeAddressDeposit))
                                }.toCborByteArray()
                                .toHexString()
                    )
                )

            return fileRepository.save(
                regCertFile.copy(
                    content = regCertConwayJson
                )
            )
        }

        fun getSKeyContent(
            skey: File,
            spendingPassword: String
        ): String =
            if (skey.content.isHex) {
                decryptSKeyContent(skey.content, spendingPassword)
            } else {
                val cipherText = encryptSKeyContent(skey.content, spendingPassword)
                fileRepository.save(skey.copy(content = cipherText))
                skey.content
            }

        fun isValidSpendingPassword(spendingPassword: String): Boolean = argon2PasswordEncoder.matches(spendingPassword, spendingPasswordHash)

        fun decryptSKeyContentForRepair(
            ciphertext: String,
            spendingPassword: String
        ): String? =
            try {
                val textEncryptor = Encryptors.delux(spendingPassword, S)
                textEncryptor.decrypt(ciphertext)
            } catch (e: Exception) {
                null
            }

        fun encryptSKeyContentForRepair(
            cleartext: String,
            spendingPassword: String
        ): String {
            val textEncryptor = Encryptors.delux(spendingPassword, S)
            return textEncryptor.encrypt(cleartext)
        }

        fun encryptSKeyContent(
            cleartext: String,
            spendingPassword: String
        ): String {
            if (!isValidSpendingPassword(spendingPassword)) {
                throw IllegalArgumentException("Invalid spending password!")
            }
            val textEncryptor = Encryptors.delux(spendingPassword, S)
            return textEncryptor.encrypt(cleartext)
        }

        private fun decryptSKeyContent(
            ciphertext: String,
            spendingPassword: String
        ): String {
            if (!isValidSpendingPassword(spendingPassword)) {
                throw IllegalArgumentException("Invalid spending password!")
            }
            val textEncryptor = Encryptors.delux(spendingPassword, S)
            return textEncryptor.decrypt(ciphertext)
        }

        companion object {
            const val S = "4b38652a506b513742655764375270794e3273473961596266670a"
            private val HEX_REGEX = Regex("^[0-9a-fA-F]+$")
            val String.isHex: Boolean
                get() = this.matches(HEX_REGEX)
        }
    }
