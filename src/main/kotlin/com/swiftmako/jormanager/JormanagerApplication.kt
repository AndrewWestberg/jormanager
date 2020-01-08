package com.swiftmako.jormanager

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.swiftmako.jormanager.api.LeaderInfo
import com.swiftmako.jormanager.api.Stats
import com.swiftmako.jormanager.utils.toHex
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okio.Okio
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.io.IOException
import java.lang.ProcessBuilder.Redirect
import java.net.SocketTimeoutException
import java.util.*
import java.util.concurrent.TimeUnit


@SpringBootApplication
class JormanagerApplication

private val mutex = Mutex()

fun main(args: Array<String>) {

    var leaderId: Int = -1
    var leaderProcessNumber: Int = -1

    val logger = LoggerFactory.getLogger("jormanager")

    val processStartQueue = Channel<Int>(10)
    runBlocking {
        for (processNumber in 0..9) {
            processStartQueue.send(processNumber)
        }
    }

    val latestStats = Collections.synchronizedMap(mutableMapOf<Int, Stats>())
    val processes = Collections.synchronizedMap(mutableMapOf<Int, JormungandrProcess>())
    val services = Collections.synchronizedMap(mutableMapOf<Int, JormungandrService>())

    val okHttpClient = OkHttpClient.Builder()
            /*
            .addInterceptor(HttpLoggingInterceptor {
                println(it)
            }.apply { level = HttpLoggingInterceptor.Level.BODY })
             */
            .build()

    val moshi = Moshi.Builder().build()

    val retrofitBuilder = Retrofit.Builder()
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))

    val pooltool = retrofitBuilder
            .baseUrl("https://api.pooltool.io/v0/")
            .build()
            .create(PooltoolService::class.java)

    /*
    GlobalScope.launch(Dispatchers.IO) {
        val service = retrofitBuilder.baseUrl("http://127.0.0.1:3100/api/")
                .build()
                .create(JormungandrService::class.java)

        val responseBody = service.getBlock("30ef475f67c8e5007be51fb6915b0708476032516c5c6708ab47935ead8bffbe")
        responseBody.bytes().let { responseBytes ->
            val blockString = responseBytes.toHex()
            val lastPoolId = blockString.substring(168, 232)
            logger.info("lastPoolId: $lastPoolId")
        }
    }
    */

    // Launch processes delayed 30 seconds each
    GlobalScope.launch(Dispatchers.IO) {
        for (processNumber in processStartQueue) {
            delay(5000)
            logger.info("Starting Process${processNumber}...")
            mutex.withLock {
                processes[processNumber] = JormungandrProcess(
                        startedAt = System.currentTimeMillis(),
                        process = launchJormungandrProcesses(processNumber)
                )
                services[processNumber] = retrofitBuilder
                        .baseUrl("http://127.0.0.1:340${processNumber}/api/")
                        .build()
                        .create(JormungandrService::class.java)
                logger.info("Active Jormungandr processes: ${processes.size}")
            }
            delay(TimeUnit.SECONDS.toMillis(30))
        }
    }

    Runtime.getRuntime().addShutdownHook(object : Thread() {
        override fun run() {
            processes.forEach {
                it.value.process.destroy()
            }
        }
    })

    GlobalScope.launch(Dispatchers.IO) {
        var maxBlockHeight: Long = 0
        val processesToRemove = mutableListOf<Int>()
        while (true) {
            delay(TimeUnit.SECONDS.toMillis(20))
            mutex.withLock {
                val serviceCalls = mutableListOf<Deferred<Any?>>()
                services.forEach { entry ->
                    serviceCalls.add(async {
                        val processNumber = entry.key
                        val service = entry.value
                        try {
                            val stats = service.nodeStats()
                            when (stats.state) {
                                "Running" -> {
                                    val networkStats = service.networkStats()
                                    val justBootstrapped = latestStats[processNumber]?.state == "Bootstrapping"
                                    latestStats[processNumber] = stats.copy(numberOfPeers = networkStats.size)
                                    logger.info("Process${processNumber}: ${stats.lastBlockHeight} - ${stats.lastBlockHash}, peers: ${networkStats.size}, uptime: ${stats.uptime}")
                                    maxBlockHeight = maxOf(maxBlockHeight, stats.lastBlockHeight?.toLong() ?: 0)

                                    stats.uptime?.let { uptime ->
                                        if (uptime > TimeUnit.MINUTES.toSeconds(2)) {
                                            // We've been up long enough. See if we've fallen behind the maxBlockHeight
                                            stats.lastBlockHeight?.toLong()?.let { lastBlockHeight ->
                                                if (maxBlockHeight - lastBlockHeight > 3) {
                                                    // We're more than 3 blocks behind. kill this node.
                                                    logger.error("Process${processNumber}: has fallen behind, restarting...")
                                                    processesToRemove.add(processNumber)
                                                    latestStats.remove(processNumber)
                                                }
                                            }
                                        }
                                    }

                                    if (justBootstrapped) {
                                        // Turn off our leadership until we're ready and have more peers
                                        try {
                                            services[processNumber]?.removeLeadership(1)
                                            logger.warn("REMOVE LEADER: Process${processNumber}")
                                        } catch (e: IOException) {
                                            logger.error("Unable to remove leadership from Process${processNumber}!")
                                        }
                                    }
                                }
                                "Bootstrapping" -> {
                                    latestStats[processNumber] = stats
                                    logger.info("Process${processNumber}: state: ${stats.state}")
                                    processes[processNumber]?.let { process ->
                                        if (System.currentTimeMillis() - process.startedAt > TimeUnit.MINUTES.toMillis(5)) {
                                            // Stale bootstrap. restart it.
                                            logger.error("Process${processNumber}: Stale bootstrap, restarting...")
                                            processesToRemove.add(processNumber)
                                            latestStats.remove(processNumber)
                                        }
                                    }
                                }
                                else -> {
                                    latestStats[processNumber] = stats
                                    logger.info("Process${processNumber}: state: ${stats.state}")
                                }
                            }
                        } catch (e: IOException) {
                            when (e) {
                                is SocketTimeoutException -> {
                                    logger.error("Process${processNumber}: SocketTimeoutException checking node!")
                                }
                                else -> {
                                    logger.error("Process${processNumber}: Exception checking node!", e)
                                }
                            }
                            processesToRemove.add(processNumber)
                            latestStats.remove(processNumber)
                        }
                        return@async
                    })
                }

                awaitAll(*serviceCalls.toTypedArray())

                processesToRemove.forEach { processNumber ->
                    logger.error("Shutting down Process$processNumber...")
                    val process = processes.remove(processNumber)
                    services.remove(processNumber)
                    process?.process?.destroy()
                    processStartQueue.send(processNumber)

                    if (processNumber == leaderProcessNumber) {
                        logger.warn("Process${leaderProcessNumber}: Removed as leader")
                        leaderProcessNumber = -1
                    }
                }
                processesToRemove.clear()

                // Find the best leader candidate
                var bestLeaderProcessCandidate: Map.Entry<Int, Stats>? = null
                latestStats.forEach { entry ->
                    val stats = entry.value
                    if (stats.lastBlockHeight?.toLong() == maxBlockHeight) {
                        if (bestLeaderProcessCandidate == null) {
                            bestLeaderProcessCandidate = entry
                            return@forEach
                        } else {
                            if (bestLeaderProcessCandidate?.value?.numberOfPeers!! > 250 && stats.numberOfPeers!! > 250) {
                                if (bestLeaderProcessCandidate?.value?.uptime!! > stats.uptime!!) {
                                    // both have good number of peers, but this one has lower uptime
                                    bestLeaderProcessCandidate = entry
                                    return@forEach
                                }
                            } else if (stats.numberOfPeers!! > 250) {
                                // we have more peers than the previous leader candidate
                                bestLeaderProcessCandidate = entry
                                return@forEach
                            } else {
                                // neither have over 250 peers, just pick the one with the most
                                if (stats.numberOfPeers > bestLeaderProcessCandidate?.value?.numberOfPeers!!) {
                                    bestLeaderProcessCandidate = entry
                                    return@forEach
                                }
                            }
                        }
                    }
                }

                val shouldPromoteNewLeader = bestLeaderProcessCandidate != null && bestLeaderProcessCandidate?.key != leaderProcessNumber
                if (leaderProcessNumber > -1 && shouldPromoteNewLeader) {
                    // dump leader logs in case we attempted to mint a block
                    val log = File("/home/westbam/cardano/jormanager-log-blocks.log")
                    ProcessBuilder(
                            "/home/westbam/cardano/jormanager-log-blocks.sh"
                    ).apply {
                        with(environment()) {
                            put("JORMUNGANDR_PORT", "340$leaderProcessNumber")
                        }
                        redirectErrorStream(true)
                        redirectOutput(Redirect.appendTo(log))
                    }.start().waitFor(5, TimeUnit.SECONDS)

                    // remove leadership from previous leader
                    try {
                        services[leaderProcessNumber]?.removeLeadership(leaderId)
                        logger.warn("REMOVE LEADER: Process${leaderProcessNumber}")
                        leaderProcessNumber = -1
                        leaderId = -1
                    } catch (e: IOException) {
                        logger.error("Unable to remove leadership from Process${leaderProcessNumber}!")
                    }
                }

                bestLeaderProcessCandidate?.let { entry ->
                    val processNumber = entry.key
                    val stats = entry.value

                    if (shouldPromoteNewLeader) {
                        Okio.buffer(Okio.source(File("/home/westbam/cardano/bluecheesestakehouse_poolsecret.json")))?.use { source ->
                            moshi.adapter(LeaderInfo::class.java)
                                    .fromJson(source)?.let { leaderInfo ->
                                        try {
                                            leaderId = services[processNumber]?.promoteToLeader(leaderInfo) ?: -1
                                            leaderProcessNumber = processNumber
                                            logger.warn("PROMOTE LEADER: Process${leaderProcessNumber}")
                                        } catch (e: IOException) {
                                            logger.error("Unable to promote Process${processNumber} to leader", e)
                                        }
                                    } ?: logger.error("Unable to parse leader json file!!")
                        }
                    } else {
                        // dump leader logs in case we attempted to mint a block
                        val log = File("/home/westbam/cardano/jormanager-log-blocks.log")
                        ProcessBuilder(
                                "/home/westbam/cardano/jormanager-log-blocks.sh"
                        ).apply {
                            with(environment()) {
                                put("JORMUNGANDR_PORT", "340$processNumber")
                            }
                            redirectErrorStream(true)
                            redirectOutput(Redirect.appendTo(log))
                        }.start()
                    }

                    logger.info("CURRENT LEADER: Process${leaderProcessNumber}")

                    // Update pooltool with our info
                    try {
                        val responseBody = services[processNumber]?.getBlock(stats.lastBlockHash!!)
                        responseBody?.bytes()?.let { responseBytes ->
                            val blockString = responseBytes.toHex()
                            val lastPoolId = blockString.substring(168, 232)

                            val pooltoolResult = pooltool.shareMyTip(
                                    poolId = "634f6d2be73e85db13a934523b6958947ca21c3449bd5af8469dbdce49731930",
                                    userId = "660ce0e3-f149-4c24-a7c3-d054f246a22a",
                                    genesisPref = "8e4d2a343f3dcf93",
                                    lastBlockHeight = stats.lastBlockHeight!!,
                                    lastBlockHash = stats.lastBlockHash!!,
                                    lastPoolId = lastPoolId
                            )
                            logger.info("$pooltoolResult")
                        }
                    } catch (e: IOException) {
                        logger.error("Error getting last block or updating pooltool!", e)
                    }
                }

                // log our status info
                val type = Types.newParameterizedType(MutableMap::class.java, Integer::class.java, Stats::class.java)
                val adapter: JsonAdapter<Map<Int, Stats>> = moshi.adapter(type)
                Okio.buffer(Okio.sink(File("/var/www/html/jormanager-stats.json"))).use {
                    adapter.toJson(it, latestStats)
                }
            }
        }
    }

    runApplication<JormanagerApplication>(*args)
}

fun launchJormungandrProcesses(processNumber: Int): Process {
    val log = File("/home/westbam/cardano/jormungandr${processNumber}.log")

    return ProcessBuilder(
            "/home/westbam/.cargo/bin/jormungandr${processNumber}",
            "--config", "/home/westbam/cardano/itn_rewards_v1-config${processNumber}.yaml",
            "--genesis-block-hash", "8e4d2a343f3dcf9330ad9035b3e8d168e6728904262f2c434a4f8f934ec7b676",
            "--secret", "/home/westbam/cardano/bluecheesestakehouse_poolsecret.yaml"
    ).apply {
        redirectErrorStream(true)
        redirectOutput(Redirect.appendTo(log))
    }.start()
}