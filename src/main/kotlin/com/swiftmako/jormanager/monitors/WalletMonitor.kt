package com.swiftmako.jormanager.monitors

import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.ktx.sumByLong
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.model.WalletItem
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import kotlin.coroutines.CoroutineContext

@Component("walletMonitor")
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
@Lazy(false)
class WalletMonitor @Autowired constructor(
        private val hostRepository: HostRepository,
        private val nodeRepository: NodeRepository,
        private val walletRepository: WalletRepository,
        private val webSocketTemplate: SimpMessagingTemplate,
        @Qualifier("newBlockChannel") private val newBlockChannel: BroadcastChannel<Long>
) : SmartLifecycle, CoroutineScope {

    private val log = LoggerFactory.getLogger(WalletMonitor::class.java)

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            log.error("Uncaught coroutine exception!", throwable)
        }
    }

    override fun isAutoStartup() = true

    override fun isRunning(): Boolean {
        val isRunning = job.isActive && !job.isCompleted && job.children.count() > 0
        log.info("WalletMonitor isRunning: $isRunning")
        return isRunning
    }

    override fun start() {
        log.info("Starting WalletMonitor...")

        monitorWallet()
    }

    private fun monitorWallet() {
        launch {
            newBlockChannel.openSubscription().consumeEach {
                // A new block has arrived.
                nodeRepository.findDefault()?.let { defaultNode ->
                    hostRepository.findByIdOrNull(defaultNode.hostId)?.let { host ->
                        HostConnection(host, defaultNode).use { hostConnection ->
                            val walletItems = mutableListOf<WalletItem>()
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
                            webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success(type = "wallet", data = walletItems))
                        }
                    } ?: log.error("Host for default node not found!")
                } ?: log.error("No default node set! Cannot monitor wallet for updates!")
            }
        }
    }

    override fun stop() {
        job.cancelChildren()
        log.info("BlockMonitor stopped.")
    }

    companion object {
        private val TESTNET_BASE_ENTERPRISE_ADDRESS = Regex("(60[0-9a-fA-F]{56}|00[0-9a-fA-F]{112})")
        private val MAINNET_BASE_ENTERPRISE_ADDRESS = Regex("(61[0-9a-fA-F]{56}|01[0-9a-fA-F]{112})")
        private val UTXO_MATCHER = Regex("([a-fA-F\\d]{64})\\s+(\\d+)\\s+(\\d+)")
    }
}