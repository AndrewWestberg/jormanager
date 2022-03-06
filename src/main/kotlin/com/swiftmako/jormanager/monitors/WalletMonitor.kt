package com.swiftmako.jormanager.monitors

import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
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
    private val walletUtils: WalletUtils,
    private val webSocketTemplate: SimpMessagingTemplate,
    @Qualifier("newBlockChannel") private val newBlockChannel: MutableStateFlow<Long?>,
    private val shelleyGenesisAdapter: JsonAdapter<GenesisShelley>
) : SmartLifecycle, CoroutineScope {

    private val log by lazy { LoggerFactory.getLogger("WalletMonitor") }

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            log.error("Uncaught coroutine exception!", throwable)
        }
    }

    private var isShuttingDown = false

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
            newBlockChannel.filterNotNull().collect {

                try {
                    if (magicString.isBlank()) {
                        nodeRepository.findDefault()?.let { defaultNode ->
                            fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)?.let { genesisFile ->
                                val genesis = shelleyGenesisAdapter.fromJson(genesisFile.content)!!
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
                        webSocketTemplate.convertAndSend(
                            "/topic/messages",
                            SocketResponse.Success(type = "wallet", data = walletItems)
                        )
                    }
                } catch (e: Throwable) {
                    if (!isShuttingDown) {
                        log.error("Error monitoring wallet!", e)
                    }
                }
            }

        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun stop(callback: Runnable) {
        isShuttingDown = true
        GlobalScope.launch {
            job.cancelAndJoin()
            log.info("WalletMonitor stopped.")
            callback.run()
        }
    }

    override fun stop() {
    }
}