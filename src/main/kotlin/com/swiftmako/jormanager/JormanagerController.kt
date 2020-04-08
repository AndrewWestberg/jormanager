package com.swiftmako.jormanager

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.swiftmako.jormanager.api.CompletedBlock
import com.swiftmako.jormanager.api.LeaderBlock
import com.swiftmako.jormanager.api.LeaderInfo
import com.swiftmako.jormanager.api.OutputStats
import com.swiftmako.jormanager.api.PendingBlock
import com.swiftmako.jormanager.api.PooltoolResult
import com.swiftmako.jormanager.api.PooltoolSendSlotsResultJsonAdapter
import com.swiftmako.jormanager.api.PooltoolSlots
import com.swiftmako.jormanager.api.PooltoolSlotsJsonAdapter
import com.swiftmako.jormanager.api.RejectedBlock
import com.swiftmako.jormanager.api.Stats
import com.swiftmako.jormanager.utils.CircularQueue
import com.swiftmako.jormanager.utils.PGPUtil
import com.swiftmako.jormanager.utils.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okio.buffer
import okio.sink
import okio.source
import org.apache.commons.math3.stat.descriptive.moment.Mean
import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation
import org.bouncycastle.util.encoders.Base64
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import retrofit2.HttpException
import retrofit2.Retrofit
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.annotation.PreDestroy
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.random.Random
import kotlin.system.measureTimeMillis


@RestController
class JormanagerController @Autowired constructor(
        private val okHttpClientBuilder: OkHttpClient.Builder,
        private val retrofitBuilder: Retrofit.Builder,
        private val moshi: Moshi,
        @Qualifier("pooltool") private val pooltool: PooltoolService,
        @Qualifier("pooltoolstats") private val pooltoolStats: PooltoolService,
        private val simpleMessagingTemplate: SimpMessagingTemplate
) : CoroutineScope, ApplicationContextAware {
    override val coroutineContext: CoroutineContext = Dispatchers.IO

    private lateinit var applicationContext: ApplicationContext
    private lateinit var config: JormanagerConfig

    private val processStartQueue = Channel<Int>(Channel.UNLIMITED)
    private val mutex = Mutex()
    private val leaderLogMutex = Mutex()
    private val firewallMutex = Mutex()
    private val configMutex = Mutex()
    private val configYamlMutex = Mutex()

    var leaderProcessNumber: Int = -1
    var nextBlockTime: DateTime? = null
    var nextEpochTime: DateTime? = null
    var lastPooltoolTimestamp: Long = -1
    var currentEpoch = "0"
    var pooltoolSlots: PooltoolSlots? = null

    private val leaderLogJsonAdapter = moshi.adapter<List<LeaderBlock>>(Types.newParameterizedType(List::class.java, LeaderBlock::class.java)).indent("  ")

    private val latestStats = Collections.synchronizedMap(mutableMapOf<Int, Stats>())
    private val processes = mutableMapOf<Int, JormungandrProcess>()
    private val services = mutableMapOf<Int, JormungandrService>()
    private val bootstrapJobs = mutableMapOf<Int, Job>()
    private val pastPeerCounts = Collections.synchronizedMap(mutableMapOf<Int, CircularQueue<Int>>())
    private var outputStats: OutputStats? = null
    private val leaderLogHistory = mutableListOf<LeaderBlock>()

    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.applicationContext = applicationContext
        logger.info("JAVA_VERSION: ${System.getProperty("java.version")}")
        runBlocking {
            refreshConfig()
            readLeaderLogHistory()
        }

        manageProcessStartup()
        manageLeaderElection()
    }

    private suspend fun refreshConfig() {
        configMutex.withLock {
            if (::config.isInitialized) {
                val newConfig = applicationContext.getBean(JormanagerConfig::class.java)
                if (newConfig != config) {
                    logger.warn("Configuration Updated!")
                    when {
                        newConfig.nodeCount > config.nodeCount -> {
                            logger.warn("jormanager.nodecount: ${config.nodeCount} -> ${newConfig.nodeCount}")
                            val delta = newConfig.nodeCount - config.nodeCount
                            val oldNodeCount = config.nodeCount
                            config = newConfig // need to set this early so the new node will actually spin up
                            for (processNumber in 0 until delta) {
                                processStartQueue.send(oldNodeCount + processNumber)
                            }
                        }
                        newConfig.nodeCount < config.nodeCount -> {
                            logger.warn("jormanager.nodecount: ${config.nodeCount} -> ${newConfig.nodeCount}")
                            val delta = config.nodeCount - newConfig.nodeCount
                            for (processNumber in 0 until delta) {
                                shutdownProcess(newConfig.nodeCount + processNumber, false)
                            }
                        }
                        newConfig.passiveNodeList != config.passiveNodeList -> {
                            for (i in newConfig.passiveNodeList.indices) {
                                val newPassiveFlag = newConfig.passiveNodeList.getOrNull(i)
                                val oldPassiveFlag = config.passiveNodeList.getOrNull(i)
                                if (newPassiveFlag != null && oldPassiveFlag != null && newPassiveFlag != oldPassiveFlag && i < config.nodeCount) {
                                    // restart this process
                                    logger.warn("RESTART Process$i as ${if (newPassiveFlag) "PASSIVE" else "LEADER"}")
                                    shutdownProcess(i)
                                }
                            }
                        }
                        newConfig.nodeStatsTimeoutMs != config.nodeStatsTimeoutMs -> {
                            // re-create our retrofit service with new okhttp timeouts for Running nodes
                            services.keys.forEach { processNumber ->
                                if (latestStats[processNumber]?.state == "Running") {
                                    val okHttpClient = okHttpClientBuilder
                                            .readTimeout(newConfig.nodeStatsTimeoutMs, TimeUnit.MILLISECONDS)
                                            .writeTimeout(newConfig.nodeStatsTimeoutMs, TimeUnit.MILLISECONDS)
                                            .connectTimeout(newConfig.nodeStatsTimeoutMs, TimeUnit.MILLISECONDS)
                                            .build()
                                    services[processNumber] = retrofitBuilder
                                            .client(okHttpClient)
                                            .baseUrl(config.restApiUrlPattern.replace("{pid}", "$processNumber".padStart(2, '0')))
                                            .build()
                                            .create(JormungandrService::class.java)
                                }
                            }
                        }
                        newConfig.standbyMode != config.standbyMode -> {
                            try {
                                if (newConfig.standbyMode && leaderProcessNumber > -1) {
                                    demoteLeader(services[leaderProcessNumber])
                                    logger.warn("DEMOTE LEADER to STANDBY LEADER: Process${leaderProcessNumber}")
                                } else {
                                    logger.warn("LEAVING STANDBY MODE. New Leader will be promoted soon.")
                                    leaderProcessNumber = -1
                                }
                            } catch (e: Throwable) {
                                logger.error("Unable to remove leadership from Process${leaderProcessNumber}!")
                                shutdownProcess(leaderProcessNumber)
                                leaderProcessNumber = -1
                            }
                        }
                        else -> {
                        }
                    }

                    config = newConfig
                    if (logger.isDebugEnabled) {
                        logger.debug(config.toString())
                    }
                }
            } else {
                config = applicationContext.getBean(JormanagerConfig::class.java)
            }
        }
    }


    /**
     * Launch a new Jormungandr process from our queue once every {nodeStaggerMs} seconds
     */
    private fun manageProcessStartup() = launch {

        if (config.nodeStaggerByBootstrap) {
            processStartQueue.send(0)
        } else {
            // populate initial set of processes
            for (processNumber in 0 until config.nodeCount) {
                processStartQueue.send(processNumber)
            }
        }

        for (processNumber in processStartQueue) {
            delay(1000)
            mutex.withLock {
                refreshConfig()
                if (processNumber < config.nodeCount) {
                    if (processes[processNumber] == null) {
                        logger.info("Starting Process${processNumber}...")
                        val firewallOpen = openFirewall(processNumber)
                        val process = launchJormungandrProcess(processNumber, config.passiveNodeList[processNumber])
                        logger.info("Process$processNumber pid: ${getPidOfProcess(process)}")
                        processes[processNumber] = JormungandrProcess(
                                startedAt = System.currentTimeMillis(),
                                process = process,
                                firewallOpen = firewallOpen,
                                isPassive = config.passiveNodeList[processNumber]
                        )
                        // new nodes get 15 second timeouts while bootstrapping
                        val okHttpClient = okHttpClientBuilder
                                .readTimeout(15, TimeUnit.SECONDS)
                                .writeTimeout(15, TimeUnit.SECONDS)
                                .connectTimeout(15, TimeUnit.SECONDS)
                                .build()
                        services[processNumber] = retrofitBuilder
                                .client(okHttpClient)
                                .baseUrl(config.restApiUrlPattern.replace("{pid}", "$processNumber".padStart(2, '0')))
                                .build()
                                .create(JormungandrService::class.java)
                        pastPeerCounts[processNumber] = CircularQueue(60) // 20 minutes worth of peer counts
                        logger.info("Active Jormungandr processes: ${processes.size}")
                        bootstrapJobs[processNumber] = manageBootstrap(processNumber)
                    } else {
                        logger.warn("Tried to start process$processNumber, but it looks to already be running.")
                    }
                } else {
                    logger.warn("Tried to start process$processNumber, but jormungandr.nodecount = ${config.nodeCount}")
                }
            }
            delay(config.nodeStaggerMs - 1000)
        }
    }

    /**
     * Add a shutdown hook to ensure all child process are terminated when this app terminates
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    @PreDestroy
    fun shutdownCallback() {
        runBlocking {
            processes.keys.toSet().forEach { key -> shutdownProcess(key, false) }
        }
    }

    private suspend fun launchJormungandrProcess(processNumber: Int, isPassive: Boolean): Process {
        val pid = "$processNumber".padStart(2, '0')
        val log = File(config.jormungandrLogPath.replace("{pid}", pid))
        val configYamlPath = config.jormungandrConfigPath.replace("{pid}", pid)

        configYamlMutex.withLock {
            if (config.incrementPublicIdEnabled) {
                incrementPublicId(configYamlPath)
            }

            scanForReachablePeers(configYamlPath)
        }

        val processParams = mutableListOf(
                config.jormungandrProcessPath.replace("{pid}", pid),
                "--config", configYamlPath,
                "--storage", config.jormungandrStoragePath.replace("{pid}", pid),
                "--genesis-block-hash", config.jormungandrGenesisHash
        )

        if (!isPassive) {
            processParams.add("--secret")
            processParams.add(config.jormungandrSecretPath)
        }

        return ProcessBuilder(
                *processParams.toTypedArray()
        ).apply {
            redirectErrorStream(true)
            redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        }.start()
    }

    /**
     * Run through all of the config.yaml files and incrment the public_id by 0x1
     */
    private fun incrementPublicId(configYamlPath: String) {
        val publicIdRegex = Regex("^\\s*public_id:\\s*\"?([0-9a-fA-F]{48}).*\$")
        var currentPublicId = ""
        var newPublicId = ""
        File(configYamlPath).bufferedReader().lines().use { lines ->
            for (line in lines) {
                publicIdRegex.matchEntire(line)?.let { matchResult ->
                    currentPublicId = matchResult.groupValues[1]
                    newPublicId = BigInteger(currentPublicId, 16).add(BigInteger.ONE).toString(16).padStart(currentPublicId.length, '0')
                } ?: continue
                break
            }
        }
        logger.info("Increment public_id $currentPublicId -> $newPublicId")

        // Process up to 100 config.yaml files
        for (processNumber in 0 until 100) {
            val pid = "$processNumber".padStart(2, '0')
            val configYamlPathToUpdate = config.jormungandrConfigPath.replace("{pid}", pid)
            val yamlBuilder = StringBuilder()
            val configYaml = File(configYamlPathToUpdate)
            if (!configYaml.exists() || configYaml.length() == 0L) {
                break
            }
            configYaml.forEachLine { line ->
                yamlBuilder.append(line.replace(currentPublicId, newPublicId))
                yamlBuilder.append('\n')
            }

            File(configYamlPathToUpdate).sink().buffer().use { sink -> sink.writeString(yamlBuilder.toString(), Charset.forName("UTF-8")) }
        }
    }

    /**
     * Comment/Uncomment config.yaml based on the reachability of trusted peers.
     */
    private fun scanForReachablePeers(configYamlPath: String) {
        // Fix the config.yaml file so that only reachable peers are uncommented
        var ignoring = false
        val ignoreRegex = Regex("^.*JorManager_ignore.*\$")
        val commentedAddressRegex = Regex("^\\s*#\\s*- address:.*\"/ip4/(.*)/tcp/(.*)\".*\$")
        val uncommentedAddressRegex = Regex("^\\s*- address:.*\"/ip4/(.*)/tcp/(.*)\".*\$")
        val idRegex = Regex("^\\s*#?\\s*id:\\s*\"?([0-9a-fA-F]{48})\"?\\s*\$")

        val yamlBuilder = StringBuilder()
        var uncommentIdLine = false
        var commentIdLine = false
        File(configYamlPath).forEachLine { line ->
            if (uncommentIdLine) {
                idRegex.matchEntire(line)?.let { matchResult ->
                    val idString = matchResult.groupValues[1]
                    yamlBuilder.append("    id: \"$idString\"")
                } ?: logger.error("line wasn't an id line!!")
                yamlBuilder.append('\n')
                uncommentIdLine = false
                return@forEachLine
            }

            if (commentIdLine) {
                idRegex.matchEntire(line)?.let { matchResult ->
                    val idString = matchResult.groupValues[1]
                    yamlBuilder.append("  #   id: \"$idString\"")
                } ?: logger.error("line wasn't an id line!!")
                yamlBuilder.append('\n')
                commentIdLine = false
                return@forEachLine
            }

            ignoreRegex.matchEntire(line)?.let { _ ->
                ignoring = !ignoring
            }

            if (!ignoring) {
                val matchResult = commentedAddressRegex.matchEntire(line) ?: uncommentedAddressRegex.matchEntire(line)
                matchResult?.let { match ->
                    val ipAddress = match.groupValues[1]
                    val port = match.groupValues[2].toInt()
                    if (isNodeReachable(ipAddress, port)) {
                        yamlBuilder.append("  - address: \"/ip4/$ipAddress/tcp/$port\"")
                        uncommentIdLine = true
                    } else {
                        yamlBuilder.append("  # - address: \"/ip4/$ipAddress/tcp/$port\"")
                        commentIdLine = true
                    }
                } ?: yamlBuilder.append(line)
            } else {
                yamlBuilder.append(line)
            }

            yamlBuilder.append('\n')
        }

        File(configYamlPath).sink().buffer().use { sink -> sink.writeString(yamlBuilder.toString(), Charset.forName("UTF-8")) }
    }

    private fun manageBootstrap(processNumber: Int) = launch {
        var probationCount = 0
        processes[processNumber]?.let { process ->
            services[processNumber]?.let { service ->
                delay(4000)
                while (true) {
                    delay(1000)
                    try {
                        val stats = service.nodeStats()
                        if (stats.state == "Running") {
                            if (!process.isPassive) {
                                if (leaderProcessNumber > -1) {
                                    // Node came up! Turn off leadership immediately
                                    demoteLeader(service)
                                    logger.warn("REMOVE LEADER AFTER BOOTSTRAP: Process${processNumber}")
                                } else {
                                    if (config.standbyMode) {
                                        demoteLeader(service)
                                        logger.warn("REMOVE LEADER AFTER BOOTSTRAP: Process${processNumber}")
                                    } else {
                                        logger.warn("KEEP FIRST LEADER AFTER BOOTSTRAP: Process${processNumber}")
                                    }
                                    leaderProcessNumber = processNumber
                                }
                            } else {
                                logger.warn("PASSIVE BOOTSTRAP: Process${processNumber}")
                            }
                            if (config.nodeStaggerByBootstrap && processNumber + 1 < config.nodeCount && processes[processNumber + 1] == null) {
                                // Launch the next process
                                processStartQueue.send(processNumber + 1)
                            }
                            return@launch
                        }
                    } catch (e: CancellationException) {
                        // The coroutine was canceled. Job will get restarted by the canceling coroutine
                        logger.warn("Process${processNumber}: Bootstrap canceled. Will restart later...")
                        return@launch
                    } catch (e: Throwable) {
                        logger.error("Error waiting for Process$processNumber to bootstrap!: ${e.message}")
                        if (probationCount > 2) {
                            mutex.withLock {
                                shutdownProcess(processNumber)
                            }
                            return@launch
                        } else {
                            probationCount++
                            logger.warn("Process$processNumber is now on bootstrap probation: $probationCount")
                        }
                    }

                    val now = System.currentTimeMillis()
                    if (now - process.startedAt > config.maxBootstrapMs) {
                        // Stale bootstrap. restart it.
                        if (logger.isDebugEnabled) {
                            logger.debug("process.startedAt: ${process.startedAt}, System.currentTimeMillis(): ${now}, config.maxBootstrapMs: ${config.maxBootstrapMs}")
                        }
                        logger.error("STALE BOOTSTRAP: Will restart Process${processNumber}")
                        mutex.withLock {
                            shutdownProcess(processNumber)
                        }
                        return@launch
                    }
                }
            }
        }
    }

    private suspend fun shutdownProcess(processNumber: Int, restart: Boolean = true) {
        logger.error("Shutting down Process$processNumber...")
        try {
            services[processNumber]?.shutdown()
        } catch (e: Throwable) {
            logger.warn("Shutdown Error: ${e.message}")
        }
        val process = processes.remove(processNumber)
        services.remove(processNumber)
        val pid = process?.process?.let {
            getPidOfProcess(it)
        }
        process?.process?.destroy()

        if (pid != null && pid > -1) {
            logger.warn("TERM Process$processNumber with pid: $pid")
            @Suppress("BlockingMethodInNonBlockingContext")
            val termExitCode = ProcessBuilder(
                    config.killPath.trim(),
                    "-s", "TERM",
                    "$pid"
            ).start().waitFor()
            logger.warn("TERM Process$processNumber exited with: $termExitCode")
            logger.warn("KILL Process$processNumber with pid: $pid")
            @Suppress("BlockingMethodInNonBlockingContext")
            val killExitCode = ProcessBuilder(
                    config.killPath.trim(),
                    "-s", "KILL",
                    "$pid"
            ).start().waitFor()
            logger.warn("KILL Process$processNumber exited with: $killExitCode")
        } else {
            logger.warn("Could not get PID for Process$processNumber shutdown.")
        }

        latestStats.remove(processNumber)

        if (restart) {
            processStartQueue.send(processNumber)
        }

        if (processNumber == leaderProcessNumber) {
            logger.warn("Process${leaderProcessNumber}: Removed as leader")
            leaderProcessNumber = -1
        }
    }

    /**
     * Manage the node health and potentially promote a new leader whenever election is triggered (normally every 20 seconds)
     */
    private fun manageLeaderElection() = launch {
        var maxBlockHeight: Long = 0
        var pooltoolMajorityMax:Long = 0
        var pooltoolResult = PooltoolResult(success = false)
        val processesToRemove = mutableListOf<Int>()
        val serviceCalls = mutableListOf<Deferred<Any?>>()
        val leaderLogs = mutableMapOf<Int, List<LeaderBlock>>()

        while (true) {
            mutex.withLock {
                refreshConfig()
            }

            handleBlockMinting()
            calculateNextEpochCutover()
            handleEpochCutover()

            // Do leader election process
            mutex.withLock {
                logger.info("STARTING Leader Election.")
                serviceCalls.clear()
                leaderLogs.clear()

                // Make sure to shut down if our nodes are behind pooltool majority max
                try {
                    val pooltoolStats = pooltoolStats.getPooltoolStats()
                    pooltoolMajorityMax = pooltoolStats.majoritymax
                    if (!config.pooltoolEnabled) {
                        pooltoolResult = PooltoolResult(success = true, pooltoolmax = pooltoolStats.max, confidence = true)
                    }
                } catch (e: Throwable) {
                    logger.error("Error getting pooltool stats: ${e.message}")
                }

                services.forEach { entry ->
                    if (System.currentTimeMillis() - (processes[entry.key]?.startedAt
                                    ?: System.currentTimeMillis()) > 5000) {
                        serviceCalls.add(async {
                            val processNumber = entry.key
                            val service = entry.value
                            try {
                                val stats = try {
                                    service.nodeStats()
                                } catch (e: Throwable) {
                                    processes[processNumber]?.let { process ->
                                        if (process.apiFailureCount >= config.nodeSequentialApiFailuresAllowed) {
                                            logger.error("Process${processNumber}: nodeStats error!: apiFailureCount=${processes[processNumber]?.apiFailureCount}")
                                            throw e
                                        }
                                    }
                                    processes[processNumber]?.let { process -> process.apiFailureCount++ }
                                    logger.warn("Process${processNumber}: nodeStats error!: apiFailureCount=${processes[processNumber]?.apiFailureCount}")
                                    return@async
                                }
                                processes[processNumber]?.apiFailureCount = 0

                                when (stats.state) {
                                    "Running" -> {
                                        if (processes[processNumber]?.isPassive == false) {
                                            try {
                                                leaderLogs[processNumber] = service.getLeaderLog()
                                            } catch (e: Throwable) {
                                                logger.error("Process${processNumber}: getLeaderLog error!")
                                                throw e
                                            }
                                        }

                                        val numberOfPeers = stats.peerConnectedCnt?.toInt()
                                                ?: if (config.useLightweightPeerCount) {
                                                    // Another method to get number of peers by by using sockets from ss
                                                    establishedSocketsByProcessId(
                                                            processes[processNumber]?.process?.let {
                                                                getPidOfProcess(it)
                                                            } ?: 0)
                                                } else {
                                                    try {
                                                        service.networkStats().size
                                                    } catch (e: Throwable) {
                                                        logger.error("Process${processNumber}: getLeaderLog error!")
                                                        throw e
                                                    }
                                                }

                                        pastPeerCounts[processNumber]?.add(numberOfPeers)

                                        // Enable/Disable firewall based on limits
                                        val fw = if (config.jormanagerUfwEnabled) {
                                            if (config.jormanagerUfwPassiveEnabled || processes[processNumber]?.isPassive == false) {
                                                if (numberOfPeers <= config.jormanagerUfwLowerLimit && processes[processNumber]?.firewallOpen == false) {
                                                    // We dropped below number lower limit of connections we'd like to have. Open the firewall
                                                    firewallMutex.withLock {
                                                        val firewallOpen = openFirewall(processNumber)
                                                        processes[processNumber]?.firewallOpen = firewallOpen
                                                    }
                                                } else if (numberOfPeers >= config.jormanagerUfwUpperLimit && processes[processNumber]?.firewallOpen == true) {
                                                    // We have enough connections. Close the firewall
                                                    firewallMutex.withLock {
                                                        val firewallClosed = closeFirewall(processNumber)
                                                        processes[processNumber]?.firewallOpen = !firewallClosed
                                                    }
                                                }
                                            }

                                            if (processes[processNumber]?.firewallOpen == true) {
                                                "ALLOW"
                                            } else {
                                                "REJECT"
                                            }
                                        } else {
                                            "---"
                                        }

                                        // Validate we're in the correct leadership state
                                        if (!config.standbyMode && processNumber == leaderProcessNumber) {
                                            // Validate that we ARE a leader
                                            promoteLeader(services[processNumber])
                                        } else {
                                            // Validate that we ARE NOT a leader
                                            demoteLeader(services[processNumber])
                                        }

                                        if (latestStats[processNumber]?.state != "Running") {
                                            // Moving to Running state for the first time. Set the new rest timeouts
                                            logger.debug("Process$processNumber came up. Set REST timeout to ${config.nodeStatsTimeoutMs}ms")
                                            val okHttpClient = okHttpClientBuilder
                                                    .readTimeout(config.nodeStatsTimeoutMs, TimeUnit.MILLISECONDS)
                                                    .writeTimeout(config.nodeStatsTimeoutMs, TimeUnit.MILLISECONDS)
                                                    .connectTimeout(config.nodeStatsTimeoutMs, TimeUnit.MILLISECONDS)
                                                    .build()
                                            services[processNumber] = retrofitBuilder
                                                    .client(okHttpClient)
                                                    .baseUrl(config.restApiUrlPattern.replace("{pid}", "$processNumber".padStart(2, '0')))
                                                    .build()
                                                    .create(JormungandrService::class.java)
                                        }

                                        val leadershipProbationEndTimestamp = latestStats[processNumber]?.leadershipProbationEndTimestamp
                                                ?: 0

                                        latestStats[processNumber] = stats.copy(
                                                numberOfPeers = numberOfPeers,
                                                passive = processes[processNumber]?.isPassive == true,
                                                leadershipProbationEndTimestamp = if (leadershipProbationEndTimestamp < System.currentTimeMillis()) {
                                                    0L
                                                } else {
                                                    leadershipProbationEndTimestamp
                                                }
                                        )
                                        logger.info("Process${processNumber}: ${stats.lastBlockHeight} - ${stats.lastBlockHash?.substring(0, 4)}..., peers: ${numberOfPeers}, avail: ${stats.peerAvailableCnt}, uptime: ${stats.uptime}, probation: ${config.leadershipProbationEnabled && latestStats[processNumber]?.leadershipProbationEndTimestamp != 0L}, fw: $fw")
                                        maxBlockHeight = maxOf(maxBlockHeight, stats.lastBlockHeight?.toLong() ?: 0)

                                        stats.uptime?.let { uptime ->
                                            if (uptime > config.nodeProbationSecs) {
                                                // We've been up long enough. See if we've fallen behind the maxBlockHeight
                                                stats.lastBlockHeight?.toLong()?.let { lastBlockHeight ->
                                                    if (maxOf(maxBlockHeight, pooltoolMajorityMax) - lastBlockHeight > config.maxBlocksBehind) {
                                                        // We're more than the max blocks behind. kill this node.
                                                        logger.error("Process${processNumber}: has fallen behind, restarting...")
                                                        processesToRemove.add(processNumber)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    else -> {
                                        // Bootstrapping or otherwise
                                        latestStats[processNumber] = stats
                                        logger.info("Process${processNumber}: state: ${stats.state}")
                                    }
                                }
                            } catch (e: Throwable) {
                                when (e) {
                                    is SocketTimeoutException, is HttpException -> {
                                        logger.error("Process${processNumber}: ${e.message}")
                                    }
                                    else -> {
                                        logger.error("Error checking Process${processNumber}!: ${e.message}")
                                    }
                                }
                                processesToRemove.add(processNumber)
                            }
                            return@async
                        })
                    }
                }

                awaitAll(*serviceCalls.toTypedArray())

                if (leaderLogs.isNotEmpty()) {
                    processLeaderLogs(leaderLogs)
                }

                processesToRemove.forEach { processNumber ->
                    shutdownProcess(processNumber)
                    latestStats.remove(processNumber)
                }
                processesToRemove.clear()

                // Find the best leader candidate
                var bestLeaderProcessCandidate: Map.Entry<Int, Stats>? = null

                latestStats.iterator().forEach { entry ->
                    val processNumber = entry.key
                    val stats = entry.value

                    if (logger.isDebugEnabled && config.minPeersForSDCalculationEnabled) {
                        logger.debug("SD Process$processNumber: ${calculateStandardDeviationOfLastPeerCount(pastPeerCounts[processNumber]!!)}")
                    }

                    if (stats.passive) {
                        // ignore passive nodes
                        return@forEach
                    }
                    if (processes[processNumber]?.apiFailureCount ?: 1 > 0) {
                        // ignore nodes with api failures
                        return@forEach
                    }
                    if (stats.lastBlockHeight?.toLong() == maxBlockHeight) {
                        if (bestLeaderProcessCandidate == null) {
                            bestLeaderProcessCandidate = entry
                            return@forEach
                        } else {
                            if (!config.minPeersForSDCalculationEnabled || (stats.numberOfPeers ?: 0 < config.minPeersForSDCalculation && bestLeaderProcessCandidate?.value?.numberOfPeers ?: 0 < config.minPeersForSDCalculation)) {
                                // node not on probation wins or node having probation ending soonest wins
                                if (config.leadershipProbationEnabled && stats.leadershipProbationEndTimestamp < bestLeaderProcessCandidate?.value?.leadershipProbationEndTimestamp ?: 0) {
                                    bestLeaderProcessCandidate = entry
                                    return@forEach
                                } else {
                                    // Just compare purely based on number of peers
                                    if (stats.numberOfPeers ?: 0 > bestLeaderProcessCandidate?.value?.numberOfPeers ?: 0) {
                                        bestLeaderProcessCandidate = entry
                                        return@forEach
                                    }
                                }
                            } else {
                                val bestLeaderCandidatePeersStandardDeviation = calculateStandardDeviationOfLastPeerCount(pastPeerCounts[bestLeaderProcessCandidate!!.key]!!)
                                val peersStandardDeviation = calculateStandardDeviationOfLastPeerCount(pastPeerCounts[processNumber]!!)

                                if (bestLeaderCandidatePeersStandardDeviation > config.minPeersBadSDLimit && peersStandardDeviation > config.minPeersBadSDLimit) {
                                    // choose the lowest standard deviation as it's further from death
                                    if (peersStandardDeviation < bestLeaderCandidatePeersStandardDeviation) {
                                        bestLeaderProcessCandidate = entry
                                        return@forEach
                                    }
                                } else if (bestLeaderCandidatePeersStandardDeviation < config.minPeersBadSDLimit && peersStandardDeviation < config.minPeersBadSDLimit) {
                                    // node not on probation wins or node having probation ending soonest wins
                                    if (config.leadershipProbationEnabled && stats.leadershipProbationEndTimestamp < bestLeaderProcessCandidate?.value?.leadershipProbationEndTimestamp ?: 0) {
                                        bestLeaderProcessCandidate = entry
                                        return@forEach
                                    } else {
                                        // Just compare purely based on number of peers
                                        if (stats.numberOfPeers ?: 0 > bestLeaderProcessCandidate?.value?.numberOfPeers ?: 0) {
                                            bestLeaderProcessCandidate = entry
                                            return@forEach
                                        }
                                    }
                                } else if (peersStandardDeviation < config.minPeersBadSDLimit) {
                                    bestLeaderProcessCandidate = entry
                                    return@forEach
                                }
                            }
                        }
                    } else {
                        if (stats.uptime ?: 0 > config.nodeProbationSecs) {
                            // This node has fallen behind and is now on leadership probation
                            entry.setValue(
                                    stats.copy(
                                            leadershipProbationEndTimestamp = System.currentTimeMillis() + config.leadershipProbationDurationMs
                                    )
                            )
                            if (config.leadershipProbationEnabled) {
                                logger.warn("Process$processNumber is on Leadership Probation")
                            }
                        }
                    }
                }

                var oldLeaderProcessNumber = -1
                val shouldPromoteNewLeader = bestLeaderProcessCandidate != null && bestLeaderProcessCandidate?.key != leaderProcessNumber
                if (leaderProcessNumber > -1 && shouldPromoteNewLeader) {
                    // remove leadership from previous leader
                    try {
                        if (config.standbyMode) {
                            logger.warn("REMOVE STANDBY LEADER: Process${leaderProcessNumber}")
                        } else {
                            demoteLeader(services[leaderProcessNumber])
                            logger.warn("REMOVE LEADER: Process${leaderProcessNumber}")
                        }
                        oldLeaderProcessNumber = leaderProcessNumber
                        leaderProcessNumber = -1
                    } catch (e: Throwable) {
                        logger.error("Unable to remove leadership from Process${leaderProcessNumber}!")
                        shutdownProcess(leaderProcessNumber)
                        oldLeaderProcessNumber = -1
                        leaderProcessNumber = -1
                    }
                }

                bestLeaderProcessCandidate?.let { entry ->
                    val processNumber = entry.key
                    val stats = entry.value

                    if (shouldPromoteNewLeader) {
                        File(config.jormungandrSecretJsonPath).source().buffer().use { source ->
                            moshi.adapter(LeaderInfo::class.java)
                                    .fromJson(source)?.let { leaderInfo ->
                                        try {
                                            if (config.standbyMode) {
                                                leaderProcessNumber = processNumber
                                                logger.warn("PROMOTE STANDBY LEADER: Process${leaderProcessNumber}")
                                            } else {
                                                promoteLeader(services[processNumber], leaderInfo)
                                                leaderProcessNumber = processNumber
                                                logger.warn("PROMOTE LEADER: Process${leaderProcessNumber}")
                                            }
                                        } catch (e: Throwable) {
                                            logger.error("Unable to promote Process${processNumber} to leader: ${e.message}")
                                            shutdownProcess(processNumber)
                                            if (oldLeaderProcessNumber > -1) {
                                                // re-promote last leader
                                                try {
                                                    if (config.standbyMode) {
                                                        leaderProcessNumber = oldLeaderProcessNumber
                                                        logger.warn("RE-PROMOTE STANDBY LEADER: Process${oldLeaderProcessNumber}")
                                                    } else {
                                                        promoteLeader(services[oldLeaderProcessNumber], leaderInfo)
                                                        leaderProcessNumber = oldLeaderProcessNumber
                                                        logger.warn("RE-PROMOTE LEADER: Process${oldLeaderProcessNumber}")
                                                    }
                                                } catch (ex: Throwable) {
                                                    logger.error("Unable to re-promote Process${oldLeaderProcessNumber} to leader: ${e.message}")
                                                    shutdownProcess(oldLeaderProcessNumber)
                                                }
                                            }
                                        }
                                    } ?: logger.error("Unable to parse leader json file!!")
                        }
                    }

                    if (leaderProcessNumber > -1) {
                        if (config.standbyMode) {
                            logger.info("CURRENT STANDBY LEADER: Process${leaderProcessNumber}")
                        } else {
                            logger.info("CURRENT LEADER: Process${leaderProcessNumber}")
                        }

                        latestStats[leaderProcessNumber] = latestStats[leaderProcessNumber]!!.copy(leader = true)

                        val epoch = latestStats[leaderProcessNumber]?.lastBlockDate?.substringBefore('.', "0") ?: "0"

                        if (config.pooltoolEnabled) {
                            if (System.currentTimeMillis() - lastPooltoolTimestamp >= config.pooltoolDelayMs) {
                                // Update pooltool with our info
                                try {
                                    val responseBody = try {
                                        services[processNumber]?.getBlock(stats.lastBlockHash!!)
                                    } catch (e: HttpException) {
                                        if (e.code() == 404) {
                                            null
                                        } else {
                                            throw e
                                        }
                                    }
                                    responseBody?.bytes()?.let { responseBytes ->
                                        val blockString = responseBytes.toHex()
                                        val lastPoolId = blockString.substring(168, 232)
                                        val lastParent = blockString.substring(104, 168)
                                        val lastSlot = blockString.substring(24, 32).toLong(16).toString()
                                        val lastEpoch = blockString.substring(16, 24).toLong(16).toString()

                                        // if (logger.isDebugEnabled) {
                                        // logger.debug("Sending Pooltool Data:\n\tpoolId = ${config.pooltoolPoolId}\n\tuserId = ${config.pooltoolUserId}\n\tgenesisPref = ${config.pooltoolGenesisPref}\n\tlastBlockHeight = ${stats.lastBlockHeight!!}\n\tlastBlockHash = ${stats.lastBlockHash!!}\n\tlastPoolId = $lastPoolId\n\tlastParent = $lastParent\n\tlastSlot = $lastSlot\n\tlastEpoch = $lastEpoch\n\tjormVersion = ${if (config.pooltoolJormverEnabled) stats.version else null}")
                                        // }
                                        pooltoolResult = pooltool.shareMyTip(
                                                poolId = config.pooltoolPoolId,
                                                userId = config.pooltoolUserId,
                                                genesisPref = config.pooltoolGenesisPref,
                                                lastBlockHeight = stats.lastBlockHeight!!,
                                                lastBlockHash = stats.lastBlockHash!!,
                                                lastPoolId = lastPoolId,
                                                lastParent = lastParent,
                                                lastSlot = lastSlot,
                                                lastEpoch = lastEpoch,
                                                platform = "JorManager",
                                                jormVersion = if (config.pooltoolJormverEnabled) stats.version.replace("+", "") else null
                                        )
                                        lastPooltoolTimestamp = System.currentTimeMillis()
                                    }
                                } catch (e: Throwable) {
                                    logger.error("Error getting last block or updating pooltool!: ${e.message}")
                                }
                            } else {
                                logger.info("Skipping Pooltool: not enough elapsed time since last update")
                            }

                            if (currentEpoch != epoch) {
                                try {
                                    if (!File("${config.pooltoolKeystorage}/response_${epoch.toInt()}.json").exists()) {
                                        val epochBlocks = leaderLogHistory.filter { block -> block.scheduledAtDate.startsWith("$epoch.") }
                                        val epochBlocksJson = leaderLogJsonAdapter.toJson(epochBlocks)

                                        val previousEpochKeyFile = File("${config.pooltoolKeystorage}/passphrase_${epoch.toInt() - 1}")
                                        previousEpochKeyFile.parentFile.mkdirs()
                                        val previousEpochKey = if (previousEpochKeyFile.exists()) {
                                            previousEpochKeyFile.readText().replace(0.toChar().toString(), "").trim()
                                        } else {
                                            ""
                                        }

                                        val currentEpochKeyFile = File("${config.pooltoolKeystorage}/passphrase_$epoch")
                                        if (!currentEpochKeyFile.exists()) {
                                            currentEpochKeyFile.writeBytes(Base64.encode(Random.nextBytes(32)))
                                        }
                                        val currentEpochKey = currentEpochKeyFile.readText().replace(0.toChar().toString(), "").trim()

                                        val encryptedSlots = PGPUtil.encrypt(epochBlocksJson, currentEpochKey)

                                        pooltoolSlots = PooltoolSlots(
                                                currentepoch = epoch,
                                                poolid = config.pooltoolPoolId,
                                                genesispref = config.pooltoolGenesisPref,
                                                userid = config.pooltoolUserId,
                                                assignedSlots = epochBlocks.size.toString(),
                                                previousEpochKey = previousEpochKey,
                                                encryptedSlots = encryptedSlots
                                        )
                                        val requestJson = PooltoolSlotsJsonAdapter(moshi).indent(" ").toJson(pooltoolSlots)
                                        File("${config.pooltoolKeystorage}/request_${epoch.toInt()}.json").writeText(requestJson)
                                        logger.debug("Sending leader logs to PoolTool: $requestJson")
                                        val slotsResults = pooltool.sendSlots(pooltoolSlots!!)
                                        val responseJson = PooltoolSendSlotsResultJsonAdapter(moshi).indent(" ").toJson(slotsResults)
                                        File("${config.pooltoolKeystorage}/response_${epoch.toInt()}.json").writeText(responseJson)
                                        if (slotsResults.success) {
                                            logger.info("Sent leader Logs to PoolTool: $responseJson")
                                        } else {
                                            logger.error("Error sending leader Logs to PoolTool: $responseJson")
                                        }
                                    } else {
                                        logger.warn("Already sent logs to pooltool for epoch $epoch because ${config.pooltoolKeystorage}/response_${epoch.toInt()}.json exists.")
                                    }
                                    currentEpoch = epoch
                                } catch (e: Throwable) {
                                    logger.error("Error sending leader logs to pooltool!", e)
                                }
                            }
                        }
                    } else {
                        logger.error("NO CURRENT LEADER Process!")
                    }
                }

                if (pooltoolResult.success || pooltoolResult.error != null) {
                    logger.info(pooltoolResult.toString())
                }

                if (config.peersOutputEnabled) {
                    // output our current peer info
                    File(config.peersOutputLogPath).sink().buffer().use { sink ->
                        val charset = Charset.forName("UTF-8")
                        sink.writeString("  # JorManager Nodes\n", charset)
                        latestStats.keys.sorted().forEach { processNumber ->
                            if (latestStats[processNumber]?.state == "Running") {
                                sink.writeString("  - address: \"/ip4/${config.peersOutputIp}/tcp/${config.peersOutputPort.replace("{pid}", "$processNumber".padStart(2, '0'))}\"\n", charset)
                                sink.writeString("    id: \"${latestStats[processNumber]?.nodeId}\"\n", charset)
                            }
                        }
                    }
                }

                // log our status info
                outputStats = OutputStats(pooltoolResult, latestStats)
                // send latest status on the websocket
                postStatusUpdate()

                val adapter = moshi.adapter(OutputStats::class.java).indent("  ")
                File(config.statsLogPath).sink().buffer().use { sink ->
                    // output without nodeId for security reasons
                    adapter.toJson(sink, OutputStats(pooltoolResult, latestStats.mapValues { entry -> entry.value.copy(nodeId = null) }))
                }
            }
        }
    }

    /**
     * Calculates the standard deviation of the past peer counts. Finally, we calculate how many standard deviations away from the mean the
     * last peer count is. If it is an anomaly, that's bad and will result in a higher value out of this function.
     */
    private fun calculateStandardDeviationOfLastPeerCount(pastPeerCounts: CircularQueue<Int>): Double {
        // If we haven't collected much data yet, just assume no deviation
        if (pastPeerCounts.size < 10) return 0.0

        val peerCounts = pastPeerCounts.map { it.toDouble() }.toDoubleArray()
        val mean = Mean().evaluate(peerCounts)
        val sd = StandardDeviation(false).evaluate(peerCounts, mean)

        // How many standard deviations away from the mean is our last peer count? IOW, is it exceptional?
        return abs(peerCounts.last() - mean) / sd
    }

    /**
     * Calculate the next epoch cutover timeperiod based on network settings
     */
    private suspend fun calculateNextEpochCutover() {
        if (leaderProcessNumber > -1 && (nextEpochTime == null || nextEpochTime?.isBeforeNow == true)) {
            var multiplier = 1024
            services[leaderProcessNumber]?.settings()?.let { nodeSettings ->
                val epochLengthSecs = (nodeSettings.slotDurationSec * nodeSettings.slotsPerEpoch).toInt()
                nextEpochTime = DateTime.parse(nodeSettings.block0Time)
                val df = DateTimeFormat.fullDateTime()
                // logger.info("nextEpochTime test: ${df.print(nextEpochTime?.withZone(DateTimeZone.getDefault()))}")
                var isNextEpochTimeBeforeNow = nextEpochTime!!.isBeforeNow
                var isNextEpochTimeMinusOneAfterNow = nextEpochTime!!.minusSeconds(epochLengthSecs).isAfterNow
                while (isNextEpochTimeBeforeNow || isNextEpochTimeMinusOneAfterNow) {
                    nextEpochTime = if (isNextEpochTimeBeforeNow) {
                        nextEpochTime!!.plusSeconds(multiplier * epochLengthSecs)
                    } else {
                        nextEpochTime!!.minusSeconds(multiplier * epochLengthSecs)
                    }
                    // logger.info("nextEpochTime test: ${df.print(nextEpochTime?.withZone(DateTimeZone.getDefault()))}")

                    isNextEpochTimeBeforeNow = nextEpochTime!!.isBeforeNow
                    isNextEpochTimeMinusOneAfterNow = nextEpochTime!!.minusSeconds(epochLengthSecs).isAfterNow
                    if (isNextEpochTimeBeforeNow || isNextEpochTimeMinusOneAfterNow) {
                        multiplier /= 2
                    }
                }

                logger.info("NEXT EPOCH SCHEDULED AT: ${df.print(nextEpochTime?.withZone(DateTimeZone.getDefault()))}")
            }
        }
    }

    /**
     * Promote all nodes to leader just before epoch cutover and demote them just afterward. This should ensure all
     * nodes get the leader logs.
     */
    private suspend fun handleEpochCutover() = coroutineScope {
        nextEpochTime?.let { epochTime ->
            if (epochTime.isAfterNow && DateTime.now().plusMillis(2 * config.leaderElectionDelayMs.toInt()).isAfter(epochTime)) {
                // lock the mutex so new nodes can't be spun up
                mutex.withLock {
                    logger.warn("EPOCH IS ENDING SOON: Pausing Leader Election changes...")
                    val fiveSecondsBeforeEpoch = epochTime.millis - System.currentTimeMillis() - 5000
                    if (fiveSecondsBeforeEpoch > 0) {
                        delay(fiveSecondsBeforeEpoch)
                    }

                    // Shutdown any nodes that are still bootstrapping so they don't interfere with epoch cutover
                    bootstrapJobs.forEach { (processNumber, job) ->
                        if (job.isActive && processes[processNumber]?.isPassive == false) {
                            logger.warn("CANCEL BOOTSTRAP JOB: Process$processNumber")
                            job.cancel()
                            shutdownProcess(processNumber)
                        }
                    }

                    // wait until 1.75 seconds before epoch cutover and make all running nodes into leaders
                    val justBeforeEpoch = epochTime.millis - System.currentTimeMillis() - 1750
                    if (justBeforeEpoch > 0) {
                        delay(justBeforeEpoch)
                    }
                    val leaderPromotions = mutableListOf<Deferred<Any?>>()
                    val leaderInfo = File(config.jormungandrSecretJsonPath).source().buffer().use { source ->
                        moshi.adapter(LeaderInfo::class.java).fromJson(source)
                    }

                    val processesToRemove = Collections.synchronizedSet(mutableSetOf<Int>())
                    leaderInfo?.let { li ->
                        services.forEach { entry ->
                            val processNumber = entry.key
                            val service = entry.value
                            if ((processNumber != leaderProcessNumber || config.standbyMode) && processes[processNumber]?.isPassive == false) {
                                leaderPromotions.add(
                                        async(Dispatchers.IO) {
                                            try {
                                                promoteLeader(service, li)
                                                logger.warn("PROMOTE LEADER: Process${processNumber}")
                                            } catch (e: Throwable) {
                                                logger.error("Unable to promote Process${processNumber} to leader", e)
                                                processesToRemove.add(processNumber)
                                            }
                                        }
                                )
                            }
                        }
                    }

                    awaitAll(*leaderPromotions.toTypedArray())
                    processesToRemove.forEach { processNumber -> shutdownProcess(processNumber) }
                    processesToRemove.clear()
                    logger.warn("EPOCH CUTOVER LEADER PROMOTIONS COMPLETED.")

                    // wait until 5 seconds after epoch cutover and make all leaders passive
                    val justAfterEpoch = epochTime.millis - System.currentTimeMillis() + 5000
                    if (justAfterEpoch > 0) {
                        logger.debug("DELAY for ${justAfterEpoch}ms")
                        delay(justAfterEpoch)
                        logger.debug("DELAY complete")
                    }
                    val leaderLogsSizeMap = Collections.synchronizedMap(mutableMapOf<Int, Int>())
                    val leaderDemotions = mutableListOf<Deferred<Any?>>()
                    services.forEach { (processNumber, service) ->
                        logger.debug("DELAY complete $processNumber")
                        if ((processNumber != leaderProcessNumber || config.standbyMode) && processes[processNumber]?.isPassive == false) {
                            leaderDemotions.add(
                                    async(Dispatchers.IO) {
                                        try {
                                            logger.debug("Before removeLeadership $processNumber")
                                            // Demoting gets 30 seconds to complete
                                            val okHttpClient = okHttpClientBuilder
                                                    .readTimeout(30, TimeUnit.SECONDS)
                                                    .writeTimeout(30, TimeUnit.SECONDS)
                                                    .connectTimeout(30, TimeUnit.SECONDS)
                                                    .build()
                                            val demoteService = retrofitBuilder
                                                    .client(okHttpClient)
                                                    .baseUrl(config.restApiUrlPattern.replace("{pid}", "$processNumber".padStart(2, '0')))
                                                    .build()
                                                    .create(JormungandrService::class.java)

                                            demoteLeader(demoteService)
                                            logger.warn("REMOVE LEADER: Process${processNumber}")

                                            val upcomingBlockCount = service.getLeaderLog().filter { block -> DateTime.parse(block.scheduledAtTime).isAfterNow }.size
                                            leaderLogsSizeMap[processNumber] = upcomingBlockCount
                                        } catch (e: Throwable) {
                                            logger.error("Unable to remove leadership or get leader logs from Process${processNumber}!: ${e.message}")
                                            processesToRemove.add(processNumber)
                                        }
                                    }
                            )
                        }
                    }
                    awaitAll(*leaderDemotions.toTypedArray())
                    processesToRemove.forEach { processNumber -> shutdownProcess(processNumber) }
                    processesToRemove.clear()
                    logger.warn("EPOCH CUTOVER LEADER DEMOTIONS COMPLETED.")

                    // See if all nodes have the right size of leader logs
                    val leaderLogsSize = leaderLogsSizeMap.values.max() ?: 0
                    leaderLogsSizeMap.forEach { (processNumber, logsSize) ->
                        if (logsSize < leaderLogsSize) {
                            logger.error("Process${processNumber} only had $logsSize leader slots, but should have been $leaderLogsSize!")
                            processesToRemove.add(processNumber)
                        }
                    }
                    processesToRemove.forEach { processNumber -> shutdownProcess(processNumber) }
                    processesToRemove.clear()

                    nextEpochTime = null
                }
            }
        }
    }

    /**
     * Demotes a node to no longer be a leader, or validates that is is already not a leader.
     * Throws an exception if this node is a leader and was not able to be demoted
     */
    @Throws(Exception::class)
    private suspend fun demoteLeader(service: JormungandrService?) {
        service?.let {
            val leaders = service.getLeaders()
            if (leaders.isNotEmpty()) {
                leaders.forEach { leaderId -> service.removeLeadership(leaderId) }
                val updatedLeaders = service.getLeaders()
                if (updatedLeaders.isNotEmpty()) {
                    throw Exception("Leaders contains $updatedLeaders")
                }
            }
        }
    }

    /**
     * Promotes a node to be a leader, or validates that is is already a leader.
     * Throws an exception if this node is not a leader and was not able to be promoted
     */
    @Throws(Exception::class)
    private suspend fun promoteLeader(service: JormungandrService?, leaderInfo: LeaderInfo? = null) {
        service?.let {
            val leaders = service.getLeaders()
            if (leaders.isEmpty()) {
                leaderInfo?.let {
                    val leaderId = service.promoteToLeader(leaderInfo)
                    val updatedLeaders = service.getLeaders()
                    if (!updatedLeaders.contains(leaderId)) {
                        throw Exception("Leaders does not contain $leaderId, instead was '$updatedLeaders'")
                    }
                } ?: throw Exception("Expected to be a leader and we weren't")
            }
        }
    }

    /**
     * Do some extra waiting if we're going to be making a block soon. We don't want to switch horses while minting a
     * block and potentially be without a leader. Otherwise, just wait the normal leader election delay timeperiod.
     */
    private suspend fun handleBlockMinting() = coroutineScope {
        nextBlockTime?.let { blockTime ->
            if (blockTime.isAfterNow && DateTime.now().plusMillis(2 * config.leaderElectionDelayMs.toInt()).isAfter(blockTime)) {
                // lock the mutex so new nodes can't be spun up
                mutex.withLock {
                    logger.warn("BLOCK MINTING SOON: Pausing Leader Election changes...")

                    // sanity check to make sure we only have 1 leader
                    val processesToRemove = Collections.synchronizedSet(mutableSetOf<Int>())
                    services.forEach { entry ->
                        val processNumber = entry.key
                        try {
                            val service = entry.value
                            val leaders = service.getLeaders()
                            if ((config.standbyMode || processNumber != leaderProcessNumber) && leaders.isNotEmpty()) {
                                logger.error("Process$processNumber is a leader and shouldn't be!")
                                processesToRemove.add(processNumber)
                            } else if (!config.standbyMode && processNumber == leaderProcessNumber && leaders.isEmpty()) {
                                logger.error("Process$processNumber should be a leader and isn't. We might miss this block!")
                                processesToRemove.add(processNumber)
                            }
                        } catch (e: Throwable) {
                            logger.error("Error checking leadership on Process$processNumber")
                            processesToRemove.add(processNumber)
                        }
                    }

                    processesToRemove.forEach { processNumber -> shutdownProcess(processNumber) }

                    // Increase process priority if configured
                    try {
                        processes[leaderProcessNumber]?.let { leaderProcess ->
                            setProcessPriorityHigh(leaderProcessNumber, getPidOfProcess(leaderProcess.process))
                        }
                    } catch (e: Throwable) {
                        logger.error("Could not set Process$leaderProcessNumber priority to HIGH")
                    }

                    val fiveSecondsBeforeMinting = blockTime.millis - System.currentTimeMillis() - 5000
                    if (fiveSecondsBeforeMinting > 0) {
                        delay(fiveSecondsBeforeMinting)
                    }

                    // Shutdown any nodes that are still bootstrapping so they don't interfere with minting
                    bootstrapJobs.forEach { (processNumber, job) ->
                        if (job.isActive && processes[processNumber]?.isPassive == false) {
                            logger.warn("CANCEL BOOTSTRAP JOB: Process$processNumber")
                            job.cancel()
                            shutdownProcess(processNumber)
                        }
                    }

                    delay(blockTime.millis - System.currentTimeMillis() + 1500)
                    logger.warn("BLOCK SHOULD HAVE MINTED BY NOW: Resuming Leader Election process.")
                    nextBlockTime = null

                    // Reset process priority if configured
                    try {
                        processes[leaderProcessNumber]?.let { leaderProcess ->
                            setProcessPriorityNormal(leaderProcessNumber, getPidOfProcess(leaderProcess.process))
                        }
                    } catch (e: Throwable) {
                        logger.error("Could not set Process$leaderProcessNumber priority to NORMAL")
                    }
                }
            } else {
                delay(config.leaderElectionDelayMs)
            }
        } ?: delay(config.leaderElectionDelayMs)
    }

    private fun readLeaderLogHistory() {
        leaderLogHistory.clear()
        File(config.blockLogPath).source().buffer().use { source ->
            leaderLogJsonAdapter.fromJson(source)?.let { leaderLog ->
                leaderLogHistory.addAll(leaderLog)
            } ?: logger.error("Unable to parse leader json file!!")
        }
    }

    private fun processLeaderLogs(leaderLogs: Map<Int, List<LeaderBlock>>) = launch {
        leaderLogMutex.withLock {
            val time = measureTimeMillis {
                readLeaderLogHistory()

                leaderLogs.forEach { mapEntry ->
                    val processNumber = mapEntry.key
                    val leaderLog = mapEntry.value
                    leaderLog.forEach { newBlock ->
                        val historicalBlockIndex = leaderLogHistory.indexOfFirst { historicalBlock -> historicalBlock.scheduledAtDate == newBlock.scheduledAtDate }
                        if (historicalBlockIndex < 0) {
                            // Block was not found. Add it to the history
                            leaderLogHistory.add(
                                    when (newBlock) {
                                        is PendingBlock -> newBlock.copy(processId = processNumber)
                                        is CompletedBlock -> newBlock.copy(processId = processNumber)
                                        is RejectedBlock -> newBlock.copy(processId = processNumber)
                                    }
                            )
                        } else {
                            // Block was found.
                            when (leaderLogHistory[historicalBlockIndex]) {
                                is PendingBlock -> {
                                    // Replace the historical block with this one that has more information
                                    when (newBlock) {
                                        is CompletedBlock -> {
                                            leaderLogHistory[historicalBlockIndex] = newBlock.copy(processId = processNumber)
                                            logger.info("COMPLETED Block ($processNumber): ${newBlock.status.blockDetail}")
                                        }
                                        is RejectedBlock -> {
                                            leaderLogHistory[historicalBlockIndex] = newBlock.copy(processId = processNumber)
                                        }
                                        else -> {
                                        }
                                    }
                                }
                                is RejectedBlock -> {
                                    when (newBlock) {
                                        is CompletedBlock -> {
                                            // Replace the historical block that was probably from a demoted leader with this one that has more information
                                            leaderLogHistory[historicalBlockIndex] = newBlock.copy(processId = processNumber)
                                            logger.info("COMPLETED Block ($processNumber): ${newBlock.status.blockDetail}")
                                        }
                                        is RejectedBlock -> {
                                            if (!newBlock.status.rejectedDetail.reason.contains("enclave")) {
                                                // Replace with the real rejected reason, not just that this leader was not in the enclave
                                                leaderLogHistory[historicalBlockIndex] = newBlock.copy(processId = processNumber)
                                            }
                                        }
                                        else -> {
                                        }
                                    }
                                }
                                is CompletedBlock -> {
                                    // do nothing. we already have the latest info about what we attempted to mint
                                    // safely in our history.
                                }
                            }
                        }
                    }
                }

                leaderLogHistory.sortWith(Comparator { block0, block1 ->
                    val block0Epoch = block0.scheduledAtDate.substringBefore('.').toLong()
                    val block1Epoch = block1.scheduledAtDate.substringBefore('.').toLong()
                    when {
                        block0Epoch > block1Epoch -> -1
                        block0Epoch < block1Epoch -> 1
                        else -> {
                            // compare the slot values in descending order
                            val block0Slot = block0.scheduledAtDate.substringAfter('.').toLong()
                            val block1Slot = block1.scheduledAtDate.substringAfter('.').toLong()
                            -1 * block0Slot.compareTo(block1Slot)
                        }
                    }
                })

                // Find next upcoming block time
                val nextBlock = leaderLogHistory.filter { block -> DateTime.parse(block.scheduledAtTime).isAfterNow }.minBy { block -> block.scheduledAtDate.substringAfter('.').toLong() }
                nextBlockTime = nextBlock?.let { block ->
                    DateTime.parse(block.scheduledAtTime)
                }

                // See if there are any CompletedBlocks that we need to check for minting
                val mintCandidateIndex = leaderLogHistory.indexOfFirst { historicalBlock ->
                    historicalBlock is CompletedBlock &&
                            historicalBlock.minted == null &&
                            DateTime.parse(historicalBlock.finishedAtTime)
                                    .isBefore(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(5))
                }

                if (mintCandidateIndex > -1) {
                    // Found a block to check for minting
                    mutex.withLock {
                        try {
                            if (leaderProcessNumber > -1) {
                                val mintCandidateBlock = leaderLogHistory[mintCandidateIndex]
                                val responseBody = try {
                                    services[leaderProcessNumber]?.getBlock((mintCandidateBlock as CompletedBlock).status.blockDetail.block)
                                } catch (e: HttpException) {
                                    if (e.code() == 404) {
                                        logger.warn("Completed block not found!: ${(mintCandidateBlock as CompletedBlock).status.blockDetail}")
                                        null
                                    } else {
                                        throw e
                                    }
                                }
                                responseBody?.bytes()?.let { responseBytes ->
                                    val blockString = responseBytes.toHex()
                                    if (blockString.length > 232) {
                                        val poolId = blockString.substring(168, 232)
                                        if (config.pooltoolPoolId == poolId) {
                                            val nextBlockResponseBody = try {
                                                services[leaderProcessNumber]?.getNextBlock((mintCandidateBlock as CompletedBlock).status.blockDetail.block)
                                            } catch (e: HttpException) {
                                                if (e.code() == 404) {
                                                    logger.warn("Completed block's next_id not found!: ${(mintCandidateBlock as CompletedBlock).status.blockDetail}")
                                                    null
                                                } else {
                                                    throw e
                                                }
                                            }
                                            if (nextBlockResponseBody?.bytes()?.size ?: -1 > 0) {
                                                leaderLogHistory[mintCandidateIndex] = (mintCandidateBlock as CompletedBlock).copy(minted = true)
                                                logger.info("MINTED Block: ${mintCandidateBlock.status.blockDetail}")
                                            } else {
                                                leaderLogHistory[mintCandidateIndex] = (mintCandidateBlock as CompletedBlock).copy(minted = false)
                                                logger.info("SNIPED Block (missing nextBlockId): ${mintCandidateBlock.status.blockDetail}")
                                            }
                                        } else {
                                            leaderLogHistory[mintCandidateIndex] = (mintCandidateBlock as CompletedBlock).copy(minted = false)
                                            logger.info("SNIPED Block (poolId didn't match): ${mintCandidateBlock.status.blockDetail}")
                                        }
                                    } else {
                                        leaderLogHistory[mintCandidateIndex] = (mintCandidateBlock as CompletedBlock).copy(minted = false)
                                        logger.info("SNIPED Block (block length too short): ${mintCandidateBlock.status.blockDetail}")
                                    }
                                }
                                        ?: run {
                                            leaderLogHistory[mintCandidateIndex] = (mintCandidateBlock as CompletedBlock).copy(minted = false)
                                            logger.info("SNIPED Block (block not found): ${mintCandidateBlock.status.blockDetail}")
                                        }
                            }
                        } catch (e: Throwable) {
                            logger.error("Error getting block info!", e)
                        }
                    }
                }

                // overwrite the historical log
                File(config.blockLogPath).sink().buffer().use { sink ->
                    leaderLogJsonAdapter.toJson(sink, leaderLogHistory)
                }
            }
            logger.info("Updated Leader logs in : $time ms")
        }
    }

    private suspend fun openFirewall(processNumber: Int): Boolean {
        return suspendCancellableCoroutine { continuation ->
            if (!config.jormanagerUfwEnabled) {
                continuation.resume(true)
            } else {
                try {
                    val args = config.jormanagerUfwAllowCmd.replace("{pid}", "$processNumber".padStart(2, '0')).split(' ')
                    val process = ProcessBuilder(
                            *args.toTypedArray()
                    ).start()

                    continuation.invokeOnCancellation {
                        try {
                            process.destroy()
                        } catch (e: Throwable) {
                            logger.error("openFirewall Error!", e)
                        }
                    }

                    val exitValue = process.waitFor()
                    if (exitValue == 0) {
                        continuation.resume(true)
                    } else {
                        continuation.resume(false)
                    }
                } catch (e: Throwable) {
                    logger.error("openFirewall ERROR!", e)
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    private suspend fun closeFirewall(processNumber: Int): Boolean {
        return suspendCancellableCoroutine { continuation ->
            if (!config.jormanagerUfwEnabled) {
                continuation.resume(true)
            } else {
                try {
                    val args = config.jormanagerUfwDenyCmd.replace("{pid}", "$processNumber".padStart(2, '0')).split(' ')
                    val process = ProcessBuilder(
                            *args.toTypedArray()
                    ).start()

                    continuation.invokeOnCancellation {
                        try {
                            process.destroy()
                        } catch (e: Throwable) {
                            logger.error("openFirewall Error!", e)
                        }
                    }

                    val exitValue = process.waitFor()
                    if (exitValue == 0) {
                        continuation.resume(true)
                    } else {
                        continuation.resume(false)
                    }
                } catch (e: Throwable) {
                    logger.error("closeFirewall ERROR!", e)
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    private suspend fun setProcessPriorityHigh(processNumber: Int, pid: Long): Boolean {
        return suspendCancellableCoroutine { continuation ->
            if (!config.processPriorityEnabled) {
                continuation.resume(true)
            } else {
                try {
                    val args = config.processPriorityHighCmd.replace("{pid}", "$pid").split(' ')
                    val process = ProcessBuilder(
                            *args.toTypedArray()
                    ).start()

                    continuation.invokeOnCancellation {
                        try {
                            process.destroy()
                        } catch (e: Throwable) {
                            logger.error("processPriorityHigh Error!", e)
                        }
                    }

                    val exitValue = process.waitFor()
                    if (exitValue == 0) {
                        logger.warn("Process$processNumber priority set to HIGH")
                        continuation.resume(true)
                    } else {
                        logger.warn("Process$processNumber priority set to HIGH exit code $exitValue")
                        continuation.resume(false)
                    }
                } catch (e: Throwable) {
                    logger.error("processPriorityHigh ERROR!", e)
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    private suspend fun setProcessPriorityNormal(processNumber: Int, pid: Long): Boolean {
        return suspendCancellableCoroutine { continuation ->
            if (!config.processPriorityEnabled) {
                continuation.resume(true)
            } else {
                try {
                    val args = config.processPriorityNormalCmd.replace("{pid}", "$pid").split(' ')
                    val process = ProcessBuilder(
                            *args.toTypedArray()
                    ).start()

                    continuation.invokeOnCancellation {
                        try {
                            process.destroy()
                        } catch (e: Throwable) {
                            logger.error("processPriorityNormal Error!", e)
                        }
                    }

                    val exitValue = process.waitFor()
                    if (exitValue == 0) {
                        logger.warn("Process$processNumber priority set to NORMAL")
                        continuation.resume(true)
                    } else {
                        logger.warn("Process$processNumber priority set to NORMAL exit code $exitValue")
                        continuation.resume(false)
                    }
                } catch (e: Throwable) {
                    logger.error("processPriorityNormal ERROR!", e)
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    private suspend fun establishedSocketsByProcessId(pid: Long): Int {
        return suspendCancellableCoroutine { continuation ->
            try {
                val process = ProcessBuilder(
                        "/bin/sh", "-c", "ss -n -p -4 state synchronized | grep pid=$pid | wc -l"
                ).redirectErrorStream(true).start()

                continuation.invokeOnCancellation {
                    try {
                        process.destroy()
                    } catch (e: Throwable) {
                        logger.error("establishedSocketsByProcessId Error!", e)
                    }
                }

                val establishedSockets = process.inputStream.source().buffer().use { source ->
                    val outputValue = source.readUtf8()
//                    logger.debug("establishedSocketsByProcessId output: '$outputValue'")
                    outputValue.replace('"', ' ').trim()
                }.toInt()
                continuation.resume(establishedSockets)
            } catch (e: Throwable) {
                logger.error("establishedSocketsByProcessId ERROR!", e)
                continuation.resumeWithException(e)
            }
        }
    }

    private fun isNodeReachable(ipAddress: String, port: Int, timeoutMs: Int = 500): Boolean {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ipAddress, port), timeoutMs)
                logger.info("Pinging $ipAddress:$port: SUCCESS!")
                return true
            }
        } catch (e: Throwable) {
            logger.warn("Pinging $ipAddress:$port: FAILURE!")
            return false
        }
    }

    @GetMapping("/api/status")
    fun getStatus(): OutputStats? = outputStats

    @GetMapping("/api/blocks")
    fun getBlocks(): List<LeaderBlock> = leaderLogHistory

    private fun postStatusUpdate() {
        outputStats?.let {
            simpleMessagingTemplate.convertAndSend("/topic/status", it)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(JormanagerController::class.java)

        @Synchronized
        fun getPidOfProcess(p: Process): Long {
            var pid: Long = -1
            try {
                try {
                    //logger.debug("Process.toString(): $p")
                    Regex("^.*pid=(\\d+).*\$").matchEntire(p.toString())?.let { matchResult ->
                        pid = matchResult.groupValues[1].toLong()
                        //logger.debug("got pid from toString()")
                    } ?: run {
                        val m: Method = Process::class.java.getMethod("pid")
                        pid = m.invoke(p) as Long
                        //logger.debug("got pid from Process.pid()")
                    }
                } catch (e: Throwable) {
                    val f: Field = p.javaClass.getDeclaredField("pid")
                    f.isAccessible = true
                    pid = f.getLong(p)
                    f.isAccessible = false
                    //logger.debug("got pid from pid private field")
                }
            } catch (e: Exception) {
                logger.error("getPidOfProcess!", e)
                pid = -1
            }
            return pid
        }
    }
}