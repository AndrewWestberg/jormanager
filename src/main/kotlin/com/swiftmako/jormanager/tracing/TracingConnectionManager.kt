package com.swiftmako.jormanager.tracing

import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.utils.io.ClosedByteChannelException
import java.io.EOFException
import java.net.SocketException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

@Component("tracingConnectionManager")
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
@Lazy(false)
class TracingConnectionManager(
    private val nodeRepository: NodeRepository,
    private val hostRepository: HostRepository,
    @param:Qualifier("nodesChannel") private val nodesChannel: MutableSharedFlow<Node>,
    private val connectionRunnerFactory: TraceForwardConnectionRunnerFactory = SocketTraceForwardConnectionRunnerFactory(),
    private val messageSink: TracingRawCaptureService,
    private val reconnectDelayMillis: Long = DEFAULT_RECONNECT_DELAY_MILLIS,
) : SmartLifecycle,
    CoroutineScope {
    private val log = KotlinLogging.logger("TracingConnectionManager")
    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext =
        job + Dispatchers.IO +
            CoroutineExceptionHandler { _, throwable ->
                if (throwable !is CancellationException) {
                    log.error(throwable) { "Uncaught tracing coroutine exception" }
                }
            }

    private val mutex = Mutex()
    private val managedConnections = mutableMapOf<Long, ManagedTracingConnection>()
    private var nodeCollectorJob: Job? = null
    private var seedJob: Job? = null
    @Volatile
    private var isShuttingDown = false

    override fun isAutoStartup() = "repair" != System.getProperty("jormanager.mode")

    override fun isRunning(): Boolean =
        !isShuttingDown &&
            ((nodeCollectorJob?.isActive == true) || managedConnections.values.any { it.job.isActive })

    override fun start() {
        if (nodeCollectorJob?.isActive == true) {
            return
        }

        log.info { "Starting TracingConnectionManager..." }
        isShuttingDown = false
        nodeCollectorJob =
            launch {
                nodesChannel.collect { node ->
                    refreshNode(node)
                }
            }
        seedJob =
            launch {
                nodeRepository.findAll().forEach { node ->
                    refreshNode(node)
                }
            }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun stop(callback: Runnable) {
        isShuttingDown = true
        GlobalScope.launch(Dispatchers.IO) {
            stopAndWait()
            callback.run()
        }
    }

    override fun stop() {
        runBlocking(Dispatchers.IO) {
            stopAndWait()
        }
    }

    suspend fun stopAndWait() {
        isShuttingDown = true
        seedJob?.cancelAndJoin()
        seedJob = null
        nodeCollectorJob?.cancelAndJoin()
        nodeCollectorJob = null

        val connections =
            mutex.withLock {
                managedConnections.values.toList().also { managedConnections.clear() }
            }
        connections.forEach { it.shutdown() }
        connections.forEach { connection ->
            runCatching { connection.job.cancelAndJoin() }
        }
        job.cancelChildren()
    }

    internal suspend fun refreshNode(node: Node) {
        val nodeId = node.id ?: return
        val target = resolveTarget(node)

        mutex.withLock {
            val existing = managedConnections[nodeId]
            if (target == null) {
                messageSink.clearNode(nodeId)
                managedConnections.remove(nodeId)?.shutdown()
                return
            }

            if (existing != null && existing.target == target && existing.job.isActive) {
                return
            }

            existing?.shutdown()
            val connectionRunner = connectionRunnerFactory.create()
            val connection =
                ManagedTracingConnection(
                    target = target,
                    connectionRunner = connectionRunner,
                    job = launch { runConnectionLoop(target, connectionRunner) },
                    messageSink = messageSink,
                )
            managedConnections[nodeId] = connection
        }
    }

    internal suspend fun managedNodeIds(): Set<Long> =
        mutex.withLock {
            managedConnections.keys.toSet()
        }

    internal fun isExpectedDisconnect(throwable: Throwable): Boolean = throwable.isExpectedDisconnect()

    private suspend fun runConnectionLoop(
        target: TracingNodeTarget,
        connectionRunner: TraceForwardConnectionRunner,
    ) {
        while (shouldKeepRunning(target)) {
            try {
                log.info { "Opening tracing connection to ${target.hostname}:${target.tracingPort} for node ${target.nodeId}" }
                connectionRunner.runConnection(target.hostname, target.tracingPort) { message ->
                    messageSink.onMessage(target.nodeId, message)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (shouldKeepRunning(target)) {
                    if (isExpectedDisconnect(e)) {
                        log.info { "Tracing connection closed for node ${target.nodeId}; reconnecting in ${reconnectDelayMillis}ms (${e.rootCauseMessage()})" }
                    } else {
                        log.warn(e) { "Tracing connection ended for node ${target.nodeId}" }
                    }
                }
            }

            if (!shouldKeepRunning(target)) {
                break
            }

            messageSink.clearNode(target.nodeId)
            delay(reconnectDelayMillis)
        }
        messageSink.clearNode(target.nodeId)
        connectionRunner.close()
    }

    private suspend fun shouldKeepRunning(target: TracingNodeTarget): Boolean =
        !isShuttingDown &&
            job.isActive &&
            mutex.withLock {
                managedConnections[target.nodeId]?.target == target
            }

    private fun resolveTarget(node: Node): TracingNodeTarget? {
        if (node.id == null || node.isDeleted || node.type !in setOf("core", "relay") || node.tracingPort == null) {
            return null
        }

        val host = hostRepository.findById(node.hostId).orElse(null)
        if (host == null) {
            log.error { "Unable to start tracing for node ${node.name}: host ${node.hostId} not found" }
            return null
        }

        return TracingNodeTarget(
            nodeId = node.id,
            hostname = host.hostname,
            tracingPort = node.tracingPort,
        )
    }

    private data class ManagedTracingConnection(
        val target: TracingNodeTarget,
        val connectionRunner: TraceForwardConnectionRunner,
        val job: Job,
        val messageSink: TracingRawCaptureService,
    ) {
        fun shutdown() {
            messageSink.clearNode(target.nodeId)
            connectionRunner.close()
            job.cancel()
        }
    }

    private data class TracingNodeTarget(
        val nodeId: Long,
        val hostname: String,
        val tracingPort: Int,
    )

    companion object {
        private const val DEFAULT_RECONNECT_DELAY_MILLIS = 5_000L
    }
}

private fun Throwable.isExpectedDisconnect(): Boolean =
    this is ClosedByteChannelException ||
        this is EOFException ||
        (this is SocketException && message?.contains("connection reset", ignoreCase = true) == true) ||
        (cause?.let { nested -> nested !== this && nested.isExpectedDisconnect() } == true)

private fun Throwable.rootCauseMessage(): String =
    generateSequence(this) { current -> current.cause?.takeIf { it !== current } }
        .last()
        .message
        ?: this::class.simpleName
        ?: "unknown error"
