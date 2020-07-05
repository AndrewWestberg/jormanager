package com.swiftmako.jormanager.controllers.utils

import com.swiftmako.jormanager.ktx.sumByLong
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
        private val walletRepository: WalletRepository
) {
    private val log = LoggerFactory.getLogger(WalletUtils::class.java)

    fun getWalletItems(): List<WalletItem> {
        val walletItems = mutableListOf<WalletItem>()
        nodeRepository.findDefault()?.let { defaultNode ->
            hostRepository.findByIdOrNull(defaultNode.hostId)?.let { host ->
                HostConnection(host, defaultNode).use { hostConnection ->
                    walletRepository.findAllNotDeleted().forEach { walletEntry ->
                        //find payment_addr balance
                        val addressInfoString = when {
                            walletEntry.paymentAddr.matches(TESTNET_BASE_ENTERPRISE_ADDRESS) -> {
                                hostConnection.command("${host.cardanoCliPath} shelley query utxo --address ${walletEntry.paymentAddr} --testnet-magic 42")
                            }
                            walletEntry.paymentAddr.matches(MAINNET_BASE_ENTERPRISE_ADDRESS) -> {
                                hostConnection.command("${host.cardanoCliPath} shelley query utxo --address ${walletEntry.paymentAddr} --mainnet")
                            }
                            else -> {
                                log.error("Invalid payment address format: ${walletEntry.paymentAddr}")
                                return@forEach
                            }
                        }

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

                        // TODO: find staking_addr balance

                        walletItems.add(
                                WalletItem(walletEntry.id!!, walletEntry.name, walletEntry.type, walletEntry.paymentAddr, utxos.size.toLong(), utxos.sumByLong { it.lovelace }, walletEntry.stakingAddr, null)
                        )
                    }
                }
            } ?: log.error("Host for default node not found!")
        } ?: log.error("No default node set! Cannot check wallet for updates!")

        return walletItems
    }

    companion object {
        private val TESTNET_BASE_ENTERPRISE_ADDRESS = Regex("(60[0-9a-fA-F]{56}|00[0-9a-fA-F]{112})")
        private val MAINNET_BASE_ENTERPRISE_ADDRESS = Regex("(61[0-9a-fA-F]{56}|01[0-9a-fA-F]{112})")
        private val UTXO_MATCHER = Regex("([a-fA-F\\d]{64})\\s+(\\d+)\\s+(\\d+)")
    }

}