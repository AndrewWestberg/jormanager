package com.swiftmako.jormanager.monitors

import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.ktx.ignoreExceptions
import com.swiftmako.jormanager.repositories.BlockRepository
import com.swiftmako.jormanager.repositories.ChainRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

@Component("blockMonitor")
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
@Lazy(false)
class BlockMonitor
    @Autowired
    constructor(
        private val blockRepository: BlockRepository,
        private val chainRepository: ChainRepository,
        private val nodeRepository: NodeRepository,
        private val webSocketTemplate: SimpMessagingTemplate,
        @param:Qualifier("nodesChannel") private val nodesChannel: MutableSharedFlow<Node>,
    ) : SmartLifecycle,
        CoroutineScope {
        private val log by lazy { LoggerFactory.getLogger("BlockMonitor") }

        private val job = SupervisorJob()
        override val coroutineContext: CoroutineContext =
            job + Dispatchers.IO +
                CoroutineExceptionHandler { _, throwable ->
                    if (throwable !is CancellationException) {
                        log.error("Uncaught coroutine exception!", throwable)
                    }
                }

        private var isShuttingDown = false

        override fun isAutoStartup() = "repair" != System.getProperty("jormanager.mode")

        override fun isRunning(): Boolean {
            val isRunning = job.isActive && !job.isCompleted && job.children.count() > 0
            log.info("BlockMonitor isRunning: $isRunning")
            return isRunning
        }

        override fun start() {
            log.info("Starting BlockMonitor...")

            launch {
                nodeRepository.findAll().filterNot(Node::isDeleted).forEach { node ->
                    nodesChannel.emit(node)
                }
            }

            validateBlocks()
        }

        private fun validateBlocks() {
            launch {
                while (!isShuttingDown) {
                    try {
                        val poolIds = nodeRepository.findPoolIds()
                        val chainTipSlotNumber = chainRepository.findSyncedTip()
                        val unvalidatedBlocks =
                            blockRepository.findUnvalidatedBlocksOlderThan(chainTipSlotNumber - 180)

                        unvalidatedBlocks.forEach { unvalidatedBlock ->
                            val chainBlock = chainRepository.findBySlot(unvalidatedBlock.slot)
                            val hash =
                                if (poolIds.contains(chainBlock?.poolId)) {
                                    chainBlock?.hash ?: unvalidatedBlock.hash
                                } else {
                                    unvalidatedBlock.hash
                                }

                            val pool =
                                chainBlock?.let {
                                    nodeRepository.findByPoolId(it.poolId)
                                } ?: unvalidatedBlock.pool

                            if (hash.isEmpty()) {
                                blockRepository.save(unvalidatedBlock.copy(pool = pool, status = "missed")).also {
                                    log.error("Missed Block: $it")
                                    webSocketTemplate.convertAndSend(
                                        "/topic/messages",
                                        SocketResponse.Success(type = "block", data = it),
                                    )
                                }
                            } else if (chainBlock?.hash?.startsWith(hash) == true) {
                                blockRepository
                                    .save(
                                        unvalidatedBlock.copy(
                                            hash = chainBlock.hash,
                                            pool = pool,
                                            status = "forged",
                                        )
                                    ).also {
                                        log.info("Forged Block: $it")
                                        webSocketTemplate.convertAndSend(
                                            "/topic/messages",
                                            SocketResponse.Success(type = "block", data = it),
                                        )
                                    }
                            } else {
                                blockRepository.save(unvalidatedBlock.copy(pool = pool, status = "orphaned")).also {
                                    log.error("Orphaned Block: $it")
                                    webSocketTemplate.convertAndSend(
                                        "/topic/messages",
                                        SocketResponse.Success(type = "block", data = it),
                                    )
                                }
                            }
                        }
                    } catch (e: EmptyResultDataAccessException) {
                        // Ignore until the chain table has enough data to validate pending blocks.
                    } catch (e: Throwable) {
                        log.error("Error validating blocks!", e)
                    }

                    delay(10_000)
                }
            }
        }

        @OptIn(DelicateCoroutinesApi::class)
        override fun stop(callback: Runnable) {
            isShuttingDown = true

            GlobalScope.launch {
                delay(3000)
                ignoreExceptions {
                    job.cancelChildren()
                }
                log.info("BlockMonitor stopped.")
                callback.run()
            }
        }

        override fun stop() {
        }
    }
