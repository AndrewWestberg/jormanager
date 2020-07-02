package com.swiftmako.jormanager.monitors

import com.swiftmako.jormanager.controllers.utils.HostConnection
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
import org.springframework.data.domain.Sort
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
                        HostConnection(host).use { hostConnection ->
                            walletRepository.findAll(Sort.by("id")).forEach { walletEntry->
                                //find payment_addr balance

                                //find staking_addr balance

                                //todo return result if different from last time
                            }
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
}