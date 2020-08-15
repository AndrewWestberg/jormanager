package com.swiftmako.jormanager.monitors

import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.repositories.FileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * Monitors https://hydra.iohk.io/job/Cardano/cardano-node/cardano-deployment/latest-finished/download/1/index.html
 * for new or updated environment files.
 */
@Component("environmentMonitor")
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
@Lazy(false)
class EnvironmentMonitor @Autowired constructor(
        private val okHttpClient: OkHttpClient,
        private val fileRepository: FileRepository
) : SmartLifecycle, CoroutineScope {
    private val log = LoggerFactory.getLogger(EnvironmentMonitor::class.java)

    private val isRunning = AtomicBoolean(false)

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            log.error("Uncaught coroutine exception!", throwable)
        }
    }

    override fun isAutoStartup() = true

    override fun isRunning(): Boolean {
        val isRunning = job.isActive && !job.isCompleted && job.children.count() > 0
        log.info("EnvironmentMonitor isRunning: $isRunning")
        return isRunning
    }

    override fun start() {
        log.info("Starting EnvironmentMonitor...")
        launch {
            isRunning.set(true)
            while (isRunning.get()) {
                try {
                    log.info("Fetching environments...")
                    val doc = Jsoup.connect(URL).get()
                    val links = doc.select("a[href]")
                    links.forEach { link ->
                        val url = link.attr("abs:href")
                        val remoteFileName = url.substringAfterLast('/')
                        val request = Request.Builder().url(url).build()
                        okHttpClient.newCall(request).execute().use { response ->
                            response.body?.string()?.let { remoteContent ->
                                fileRepository.findByName(remoteFileName)?.let { dbFile ->
                                    if (dbFile.content != remoteContent) {
                                        fileRepository.save(dbFile.copy(content = remoteContent))
                                        log.info("Saved: $remoteFileName")
                                    }
                                } ?: run {
                                    fileRepository.save(
                                            File(
                                                    name = remoteFileName,
                                                    content = remoteContent
                                            )
                                    )
                                    log.info("Saved: $remoteFileName")
                                }
                            }
                        }
                    }
                    log.info("Fetching environments done.")
                } catch (e: Throwable) {
                    log.error("Error fetching environments!", e)
                }
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    override fun stop() {
        isRunning.set(false)
        job.cancelChildren()
        log.info("EnvironmentMonitor stopped.")
    }

    companion object {
        const val RECONNECT_DELAY_MS = 14_400_000L // 4 hours
        const val URL = "https://hydra.iohk.io/job/Cardano/cardano-node/cardano-deployment/latest-finished/download/1/index.html"
    }
}