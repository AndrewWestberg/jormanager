package com.swiftmako.jormanager.controllers.utils

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.ktx.sumByLong
import com.swiftmako.jormanager.model.AddressInfo
import com.swiftmako.jormanager.model.StakeAddressInfo
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.model.WalletItem
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Scope
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component


@Component
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
class WalletUtils @Autowired constructor(
        private val hostRepository: HostRepository,
        private val nodeRepository: NodeRepository,
        private val walletRepository: WalletRepository,
        moshi: Moshi
) {
    private val log = LoggerFactory.getLogger(WalletUtils::class.java)
    private val addressInfoAdapter = moshi.adapter(AddressInfo::class.java)
    private val stakingInfoAdapter: JsonAdapter<Map<String, StakeAddressInfo>> by lazy {
        val type = Types.newParameterizedType(Map::class.java, String::class.java, StakeAddressInfo::class.java)
        moshi.adapter<Map<String, StakeAddressInfo>>(type)
    }

    fun getWalletItems(): List<WalletItem> {
        val walletItems = mutableListOf<WalletItem>()
        nodeRepository.findDefault()?.let { defaultNode ->
            hostRepository.findByIdOrNull(defaultNode.hostId)?.let { host ->
                HostConnection(host, defaultNode).use { hostConnection ->
                    walletRepository.findAllNotDeleted().forEach { walletEntry ->
                        walletItems.add(
                                getWalletItem(host, hostConnection, walletEntry)
                        )
                    }
                }
            } ?: log.error("Host for default node not found!")
        } ?: log.warn("No default node set! Cannot check wallet for updates!")

        return walletItems
    }

    fun getWalletItem(host: Host, hostConnection: HostConnection, walletEntry: WalletEntry): WalletItem {
        val utxos = getUtxos(host, hostConnection, walletEntry.paymentAddr)

        // find staking_addr balance
        val stakingInfoString = if (walletEntry.type == "stake") {
            val stakeAddressInfoJson = hostConnection.command("${host.cardanoCliPath} shelley address info --address ${walletEntry.stakingAddr}")
            val stakeAddressInfo = addressInfoAdapter.fromJson(stakeAddressInfoJson)
//                            when {
//                                stakeAddressInfo?.base16?.matches(TESTNET_STAKING_ADDRESS) == true -> {
            try {
                hostConnection.command("${host.cardanoCliPath} shelley query stake-address-info --address ${walletEntry.stakingAddr} --cardano-mode --testnet-magic 42")
            } catch (t: Throwable) {
                if (t.message?.contains("EraMismatch") == false) {
                    log.error("Error getting stake addr info!", t)
                }
                ""
            }
//                                }
//                                stakeAddressInfo?.base16?.matches(MAINNET_STAKING_ADDRESS) == true -> {
//                                    hostConnection.command("${host.cardanoCliPath} shelley query stake-address-info --address ${walletEntry.stakingAddr} --cardano-mode --mainnet")
//                                }
//                                else -> {
//                                    log.error("Invalid staking address format: ${walletEntry.paymentAddr}")
//                                    return@forEach
//                                }
//                            }
        } else {
            null
        }
        val stakingAddrLovelace = stakingInfoString?.let { json ->
            stakingInfoAdapter.fromJson(json)?.values?.firstOrNull()?.rewardAccountBalance
        } ?: 0L

        return WalletItem(walletEntry.id!!, walletEntry.name, walletEntry.type, walletEntry.paymentAddr, utxos.size.toLong(), utxos.sumByLong { it.lovelace }, walletEntry.stakingAddr, stakingAddrLovelace)
    }

    fun getUtxos(host: Host, hostConnection: HostConnection, paymentAddr: String): List<Utxo> {
        // find payment_addr balance
        val addressInfoJson = hostConnection.command("${host.cardanoCliPath} shelley address info --address $paymentAddr")
        val addressInfo = addressInfoAdapter.fromJson(addressInfoJson)
        val addressInfoString = //when {
                //addressInfo?.base16?.matches(TESTNET_BASE_ENTERPRISE_ADDRESS) == true -> {
                try {
                    hostConnection.command("${host.cardanoCliPath} shelley query utxo --address $paymentAddr --cardano-mode --testnet-magic 42")
                } catch (t: Throwable) {
                    if (t.message?.contains("EraMismatch") == false) {
                        log.error("Error getting payment addr info!", t)
                    }
                    ""
                }
//                            }
//                            addressInfo?.base16?.matches(MAINNET_BASE_ENTERPRISE_ADDRESS) == true -> {
//                                hostConnection.command("${host.cardanoCliPath} shelley query utxo --address $paymentAddr --cardano-mode --mainnet")
//                            }
//                            else -> {
//                                log.error("Invalid payment address format: ${walletEntry.paymentAddr}")
//                                return@forEach
//                            }
//                        }
        val utxos = mutableListOf<Utxo>()
        UTXO_MATCHER.findAll(addressInfoString).forEach { matchResult ->
            utxos.add(
                    Utxo(
                            hash = matchResult.groupValues[1],
                            ix = matchResult.groupValues[2].toLong(),
                            lovelace = matchResult.groupValues[3].toLong()
                    )
            )
        }

        return utxos
    }

    companion object {
        private val TESTNET_BASE_ENTERPRISE_ADDRESS = Regex("(60[0-9a-fA-F]{56}|00[0-9a-fA-F]{112})")
        private val MAINNET_BASE_ENTERPRISE_ADDRESS = Regex("(61[0-9a-fA-F]{56}|01[0-9a-fA-F]{112})")
        private val TESTNET_STAKING_ADDRESS = Regex("e0[0-9a-fA-F]{60}")
        private val MAINNET_STAKING_ADDRESS = Regex("e1[0-9a-fA-F]{60}")
        private val UTXO_MATCHER = Regex("\"?([a-fA-F\\d]{64})\"?\\s+(\\d+)\\s+(\\d+)")
    }

}