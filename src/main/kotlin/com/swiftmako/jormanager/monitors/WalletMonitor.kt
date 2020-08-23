package com.swiftmako.jormanager.monitors

import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.Genesis
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.NodeRepository
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
        private val nodeRepository: NodeRepository,
        private val fileRepository: FileRepository,
        moshi: Moshi,
        private val walletUtils: WalletUtils,
        private val webSocketTemplate: SimpMessagingTemplate,
        @Qualifier("newBlockChannel") private val newBlockChannel: BroadcastChannel<Long>
) : SmartLifecycle, CoroutineScope {

    private val log = LoggerFactory.getLogger(WalletMonitor::class.java)
    private val genesisAdapter by lazy { moshi.adapter(Genesis::class.java) }

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
            var magicString = ""
            newBlockChannel.openSubscription().consumeEach {

                try {
                    if (magicString.isBlank()) {
                        nodeRepository.findDefault()?.let { defaultNode ->
                            fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)?.let { genesisFile ->
                                val genesis = genesisAdapter.fromJson(genesisFile.content)!!
                                magicString = if (genesis.networkId.equals("testnet", ignoreCase = true)) {
                                    "--testnet-magic ${genesis.networkMagic}"
                                } else {
                                    "--mainnet"
                                }
                            }
                        }
                    }
                    if (magicString.isNotBlank()) {
                        // A new block has arrived.
                        val walletItems = walletUtils.getWalletItems(magicString)
                        webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success(type = "wallet", data = walletItems))
                    }
                } catch (e: Throwable) {
                    log.error("Error monitoring wallet!", e)
                }
            }

        }
    }

    override fun stop() {
        job.cancelChildren()
        log.info("BlockMonitor stopped.")
    }

}