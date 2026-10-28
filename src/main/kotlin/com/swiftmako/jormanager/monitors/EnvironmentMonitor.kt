package com.swiftmako.jormanager.monitors

import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.repositories.FileRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component

/**
 * Monitors https://hydra.iohk.io/job/Cardano/cardano-node/cardano-deployment/latest-finished/download/1/index.html
 * for new or updated environment files.
 */
@Component("environmentMonitor")
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
@Lazy(false)
class EnvironmentMonitor
    @Autowired
    constructor(
        private val okHttpClient: OkHttpClient,
        private val fileRepository: FileRepository,
        private val networkBundleValidator: com.swiftmako.jormanager.services.NetworkBundleValidator
    ) : SmartLifecycle,
        CoroutineScope {
        private val log by lazy { LoggerFactory.getLogger("EnvironmentMonitor") }

        private val isRunning = AtomicBoolean(false)

        private val job = SupervisorJob()
        override val coroutineContext: CoroutineContext =
            job + Dispatchers.IO +
                CoroutineExceptionHandler { _, throwable ->
                    if (throwable !is CancellationException) {
                        log.error("Uncaught coroutine exception!", throwable)
                    }
                }

        override fun isAutoStartup() = "repair" != System.getProperty("jormanager.mode")

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
                        val client = okHttpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
                        val index = client.newCall(Request.Builder().url(URL).build()).execute().use { response ->
                            require(response.isSuccessful) { "Catalog returned HTTP ${response.code}" }
                            response.body.string()
                        }
                        val links = Jsoup.parse(index, URL).select("a[href]").map { it.attr("abs:href") }
                        networkBundleValidator.refresh(links, client, URL)
                        links.forEach { url ->
                            val parsed = url.toHttpUrlOrNull()
                            val origin = URL.toHttpUrl()
                            if (parsed == null || parsed.scheme != "https" || parsed.host != origin.host ||
                                parsed.port != origin.port || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() ||
                                parsed.fragment != null
                            ) return@forEach
                            val remoteFileName = parsed.pathSegments.last()
                            if (remoteFileName.startsWith("musashi-")) return@forEach
                            val request = Request.Builder().url(parsed).build()
                            client.newCall(request).execute().use { response ->
                                require(response.isSuccessful) { "$remoteFileName returned HTTP ${response.code}" }
                                val remoteContent = response.body.string()
                                val existing = fileRepository.findByName(remoteFileName)
                                if (existing == null) {
                                    fileRepository.save(File(name = remoteFileName, content = remoteContent))
                                    log.info("Saved: $remoteFileName")
                                } else if (existing.content != remoteContent) {
                                    log.warn("Catalog file $remoteFileName changed; retaining existing node selections")
                                }
                            }
                        }
                        log.info("Fetching environments done.")
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        networkBundleValidator.catalogFailure(e.message ?: e.javaClass.simpleName)
                        log.error("Error fetching environments!", e)
                    }
                    delay(RECONNECT_DELAY_MS)
                }
            }
        }

        @OptIn(DelicateCoroutinesApi::class)
        override fun stop(callback: java.lang.Runnable) {
            isRunning.set(false)
            GlobalScope.launch {
                job.cancelChildren()
                job.cancelAndJoin()
                log.info("EnvironmentMonitor stopped.")
                callback.run()
            }
        }

        override fun stop() {
        }

        companion object {
            const val RECONNECT_DELAY_MS = 14_400_000L // 4 hours

            // const val URL = "https://hydra.iohk.io/job/Cardano/cardano-node/cardano-deployment/latest-finished/download/1/index.html"
            const val URL = "https://bluecheesestakehouse.com/jormanager/index.html"
        }
    }
