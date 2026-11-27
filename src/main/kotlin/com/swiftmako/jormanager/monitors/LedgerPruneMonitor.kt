package com.swiftmako.jormanager.monitors

import com.swiftmako.jormanager.services.LedgerPruningService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

@Component("ledgerPruneMonitor")
@Lazy(false)
class LedgerPruneMonitor(
    private val ledgerPruningService: LedgerPruningService,
    @param:Value("\${jormanager.ledger.prune.enabled:true}")
    private val enabled: Boolean = true,
    @param:Value("\${jormanager.ledger.prune.initial-delay-ms:$DEFAULT_INITIAL_DELAY_MS}")
    private val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    @param:Value("\${jormanager.ledger.prune.interval-ms:$DEFAULT_INTERVAL_MS}")
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) : SmartLifecycle {
    private val log = KotlinLogging.logger("LedgerPruneMonitor")
    private var root: Job? = null

    init {
        require(initialDelayMs >= 0L) { "initialDelayMs ($initialDelayMs) cannot be negative" }
        require(intervalMs > 0L) { "intervalMs ($intervalMs) must be positive" }
    }

    override fun isAutoStartup(): Boolean = enabled && System.getProperty("jormanager.mode") != "repair"

    override fun isRunning(): Boolean = root?.isActive == true

    override fun start() {
        if (!enabled || isRunning) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val rootJob = scope.coroutineContext[Job]
        root = rootJob
        scope.launch {
            if (initialDelayMs > 0L) {
                delay(initialDelayMs)
            }
            while (isActive) {
                try {
                    ledgerPruningService.pruneOnce {
                        rootJob?.ensureActive()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error(e) { "Background ledger pruning sweep failed" }
                }
                delay(intervalMs)
            }
        }
    }

    override fun stop(callback: Runnable) {
        val job = root
        root = null
        if (job == null) {
            callback.run()
        } else {
            job.invokeOnCompletion { callback.run() }
            job.cancel()
        }
    }

    override fun stop() {
        root?.cancel()
        root = null
    }

    companion object {
        const val DEFAULT_INITIAL_DELAY_MS = 60_000L
        const val DEFAULT_INTERVAL_MS = 600_000L
    }
}
