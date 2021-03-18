package com.swiftmako.jormanager.controllers.utils

import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.ktx.sumByBigInteger
import com.swiftmako.jormanager.model.StakeAddressInfo
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.model.WalletItem
import com.swiftmako.jormanager.model.toNativeAssetMap
import com.swiftmako.jormanager.moshi.adapters.QueryUtxoJsonAdapter
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Scope
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.encrypt.Encryptors
import org.springframework.stereotype.Component
import java.math.BigInteger


@Component
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
class WalletUtils @Autowired constructor(
        private val hostRepository: HostRepository,
        private val nodeRepository: NodeRepository,
        private val walletRepository: WalletRepository,
        private val fileRepository: FileRepository,
        private val argon2PasswordEncoder: Argon2PasswordEncoder,
        @Value("\${jormanager.spendingpassword}") private val spendingPasswordHash: String,
        private val stakingInfoAdapter: JsonAdapter<List<StakeAddressInfo>>,
        private val queryUtxoJsonAdapter: JsonAdapter<List<Utxo>>,
) {
    private val log = LoggerFactory.getLogger(WalletUtils::class.java)

    fun getWalletItems(magicString: String): List<WalletItem> {
        val walletItems = mutableListOf<WalletItem>()
        nodeRepository.findDefault()?.let { defaultNode ->
            hostRepository.findByIdOrNull(defaultNode.hostId)?.let { host ->
                val hostConnection = HostConnection(host, defaultNode)
                val eraString = hostConnection.calculateEraString(magicString)
                walletRepository.findAllNotDeleted().forEach { walletEntry ->
                    walletItems.add(
                            getWalletItem(host, hostConnection, eraString, magicString, walletEntry)
                    )
                }
            } ?: log.error("Host for default node not found!")
        } ?: log.warn("No default node set! Cannot check wallet for updates!")

        return walletItems
    }

    fun getWalletItem(
            host: Host,
            hostConnection: HostConnection,
            eraString: String,
            magicString: String,
            walletEntry: WalletEntry
    ): WalletItem {
        val utxos = getUtxos(host, hostConnection, eraString, magicString, walletEntry.paymentAddr)

        // find staking_addr balance
        val stakingInfoString = if (walletEntry.type == "stake" || walletEntry.type == "pledge") {
            try {
                hostConnection.command("${host.cardanoCliPath} query stake-address-info --address ${walletEntry.stakingAddr} $eraString $magicString")
            } catch (t: Throwable) {
                if (t.message?.contains("EraMismatch") == false) {
                    log.error("Error getting stake addr info!", t)
                }
                ""
            }
        } else {
            null
        }
        val stakingAddrLovelace = stakingInfoString?.let { json ->
            try {
                val stakeAddressInfos = stakingInfoAdapter.fromJson(json)
                if(stakeAddressInfos?.isNotEmpty() == true) {
                    stakeAddressInfos.sumByBigInteger { it.rewardAccountBalance }
                } else {
                    null
                }
            } catch (t: Throwable) {
                log.warn("Error parsing staking info json: $json", t)
                null
            }
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
            eraString: String,
            magicString: String,
            paymentAddr: String
    ): List<Utxo> {
        // find payment_addr balance
        val addressInfoJson = try {
            hostConnection.command("${host.cardanoCliPath} query utxo --address $paymentAddr $eraString $magicString --out-file=/dev/stdout")
                    .trim()
        } catch (t: Throwable) {
            if (t.message?.contains("EraMismatch") == false) {
                log.error("Error getting payment addr info!", t)
            }
            ""
        }
        val utxos = queryUtxoJsonAdapter.fromJson(addressInfoJson)

        return utxos ?: emptyList()
    }

    fun getSKeyContent(skey: File, spendingPassword: String): String {
        return if (skey.content.isHex) {
            decryptSKeyContent(skey.content, spendingPassword)
        } else {
            val cipherText = encryptSKeyContent(skey.content, spendingPassword)
            fileRepository.save(skey.copy(content = cipherText))
            skey.content
        }
    }

    fun isValidSpendingPassword(spendingPassword: String): Boolean {
        return argon2PasswordEncoder.matches(spendingPassword, spendingPasswordHash)
    }

    fun encryptSKeyContent(cleartext: String, spendingPassword: String): String {
        if (!isValidSpendingPassword(spendingPassword)) {
            throw IllegalArgumentException("Invalid spending password!")
        }
        val textEncryptor = Encryptors.delux(spendingPassword, S)
        return textEncryptor.encrypt(cleartext)
    }

    private fun decryptSKeyContent(ciphertext: String, spendingPassword: String): String {
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