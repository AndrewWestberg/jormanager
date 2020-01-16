package com.swiftmako.jormanager

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.swiftmako.jormanager.api.CompletedBlock
import com.swiftmako.jormanager.api.LeaderBlock
import com.swiftmako.jormanager.api.LeaderInfo
import com.swiftmako.jormanager.api.OutputStats
import com.swiftmako.jormanager.api.PendingBlock
import com.swiftmako.jormanager.api.PooltoolResult
import com.swiftmako.jormanager.api.RejectedBlock
import com.swiftmako.jormanager.api.Stats
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Okio
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.RestController
import retrofit2.HttpException
import retrofit2.Retrofit
import java.io.File
import java.lang.reflect.Field
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.system.measureTimeMillis


@RestController
class JormanagerController @Autowired constructor(
        private val processStartQueue: Channel<Int>,
        private val retrofitBuilder: Retrofit.Builder,
        private val moshi: Moshi,
        private val pooltool: PooltoolService,
        @Value("\${jormanager.leader_election_delay_ms}") private val leaderElectionDelayMs: Long,
        @Value("\${jormanager.max_blocks_behind}") private val maxBlocksBehind: Int,
        @Value("\${jormanager.node_probation_secs}") private val nodeProbationSecs: Long,
        @Value("\${jormanager.max_bootstrap_ms}") private val maxBootstrapMs: Long,
        @Value("\${jormanager.node_stagger_ms}") private val nodeStaggerMs: Long,
        @Value("\${jormanager.pooltool.poolId}") private val pooltoolPoolId: String,
        @Value("\${jormanager.pooltool.userId}") private val pooltoolUserId: String,
        @Value("\${jormanager.pooltool.genesisPref}") private val pooltoolGenesisPref: String,
        @Value("\${jormanager.block_log}") private val blockLogPath: String,
        @Value("\${jormanager.stats_log}") private val statsLogPath: String,
        @Value("\${jormanager.jormungandr.rest_api_url}") private val restApiUrlPattern: String,
        @Value("\${jormanager.jormungandr.log_location}") private val jormungandrLogPath: String,
        @Value("\${jormanager.jormungandr.process}") private val jormungandrProcessPath: String,
        @Value("\${jormanager.jormungandr.storage}") private val jormungandrStoragePath: String,
        @Value("\${jormanager.jormungandr.config}") private val jormungandrConfigPath: String,
        @Value("\${jormanager.jormungandr.genesis}") private val jormungandrGenesisHash: String,
        @Value("\${jormanager.jormungandr.secret}") private val jormungandrSecretPath: String,
        @Value("\${jormanager.jormungandr.secret_json}") private val jormungandrSecretJsonPath: String
) : CoroutineScope {
    private val logger = LoggerFactory.getLogger(JormanagerController::class.java)
    override val coroutineContext: CoroutineContext = Dispatchers.IO

    private val mutex = Mutex()
    private val leaderLogMutex = Mutex()

    var leaderId: Int = -1
    var leaderProcessNumber: Int = -1
    var nextBlockTime: DateTime? = null
    var nextEpochTime: DateTime? = null

    private val latestStats = Collections.synchronizedMap(mutableMapOf<Int, Stats>())
    private val processes = Collections.synchronizedMap(mutableMapOf<Int, JormungandrProcess>())
    private val services = Collections.synchronizedMap(mutableMapOf<Int, JormungandrService>())
    private val bootstrapJobs = Collections.synchronizedMap(mutableMapOf<Int, Job>())


    init {
        manageProcessStartup()
        manageLeaderElection()
        manageProcessShutdown()
    }

    /**
     * Launch a new Jormungandr process from our queue once every {nodeStaggerMs} seconds
     */
    private fun manageProcessStartup() = launch {
        for (processNumber in processStartQueue) {
            delay(1000)
            mutex.withLock {
                if (processes[processNumber] == null) {
                    logger.info("Starting Process${processNumber}...")
                    processes[processNumber] = JormungandrProcess(
                            startedAt = System.currentTimeMillis(),
                            process = launchJormungandrProcess(processNumber)
                    )
                    services[processNumber] = retrofitBuilder
                            .baseUrl(restApiUrlPattern.replace("{pid}", "$processNumber".padStart(2, '0')))
                            .build()
                            .create(JormungandrService::class.java)
                    logger.info("Active Jormungandr processes: ${processes.size}")
                    bootstrapJobs[processNumber] = manageBootstrap(processNumber)
                }
            }
            delay(nodeStaggerMs - 1000)
        }
    }

    /**
     * Add a shutdown hook to ensure all child process are terminated when this app terminates
     */
    private fun manageProcessShutdown() {
        Runtime.getRuntime().addShutdownHook(object : Thread() {
            override fun run() {
                processes.forEach {
                    it.value.process.destroy()
                }
            }
        })
    }

    private fun launchJormungandrProcess(processNumber: Int): Process {
        val pid = "$processNumber".padStart(2, '0')
        val log = File(jormungandrLogPath.replace("{pid}", pid))

//        if (leaderProcessNumber > -1) {
//            // rsync the blocks db from the leader to improve bootstrap time for this new node
//            val leaderPid = "$leaderProcessNumber".padStart(2, '0')
//            ProcessBuilder(
//                    "rsync",
//                    "-a",
//                    "${jormungandrStoragePath.replace("{pid}", leaderPid)}/",
//                    "${jormungandrStoragePath.replace("{pid}", pid)}/"
//            ).start().waitFor(5, TimeUnit.SECONDS)
//            logger.info("COPIED DB to storage for Process$processNumber")
//        }

        return ProcessBuilder(
                jormungandrProcessPath.replace("{pid}", pid),
                "--config", jormungandrConfigPath.replace("{pid}", pid),
                "--storage", jormungandrStoragePath.replace("{pid}", pid),
                "--genesis-block-hash", jormungandrGenesisHash,
                "--secret", jormungandrSecretPath
        ).apply {
            redirectErrorStream(true)
            redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        }.start()
    }

    private fun manageBootstrap(processNumber: Int) = launch {
        processes[processNumber]?.let { process ->
            services[processNumber]?.let { service ->
                delay(4000)
                while (true) {
                    delay(1000)
                    try {
                        val stats = service.nodeStats()
                        if (stats.state == "Running") {
                            if (leaderProcessNumber > -1) {
                                // Node came up! Turn off leadership immediately
                                service.removeLeadership(1)
                                logger.warn("REMOVE LEADER AFTER BOOTSTRAP: Process${processNumber}")
                            } else {
                                logger.warn("KEEP FIRST LEADER AFTER BOOTSTRAP: Process${processNumber}")
                                leaderProcessNumber = processNumber
                                leaderId = 1
                            }
                            return@launch
                        }
                    } catch (e: CancellationException) {
                        // The coroutine was canceled. Job will get restarted by the canceling coroutine
                        logger.warn("Process${processNumber}: Bootstrap canceled. Will restart later...")
                        return@launch
                    } catch (e: Throwable) {
                        logger.error("Error waiting for Process$processNumber to bootstrap!: ${e.message}")
                        shutdownProcess(processNumber)
                        return@launch
                    }

                    if (System.currentTimeMillis() - process.startedAt > maxBootstrapMs) {
                        // Stale bootstrap. restart it.
                        logger.error("STALE BOOTSTRAP: Will restart Process${processNumber}")
                        shutdownProcess(processNumber)
                        return@launch
                    }
                }
            }
        }
    }

    private suspend fun shutdownProcess(processNumber: Int) {
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
            ProcessBuilder(
                    "kill",
                    "-s", "TERM",
                    "$pid"
            ).start().waitFor()
            logger.warn("KILL Process$processNumber with pid: $pid")
            @Suppress("BlockingMethodInNonBlockingContext")
            ProcessBuilder(
                    "kill",
                    "-s", "KILL",
                    "$pid"
            ).start().waitFor()
        } else {
            logger.warn("Could not get PID for Process$processNumber shutdown.")
        }

        processStartQueue.send(processNumber)

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
        var pooltoolResult = PooltoolResult(success = false)
        val processesToRemove = mutableListOf<Int>()
        val serviceCalls = mutableListOf<Deferred<Any?>>()
        val leaderLogs = mutableListOf<List<LeaderBlock>>()

        while (true) {
            handleBlockMinting()
            calculateNextEpochCutover()
            handleEpochCutover()

            // Do leader election process
            mutex.withLock {
                serviceCalls.clear()
                leaderLogs.clear()
                services.forEach { entry ->
                    if (System.currentTimeMillis() - (processes[entry.key]?.startedAt
                                    ?: System.currentTimeMillis()) > 5000) {
                        serviceCalls.add(async {
                            val processNumber = entry.key
                            val service = entry.value
                            try {
                                val stats = service.nodeStats()
                                when (stats.state) {
                                    "Running" -> {
                                        leaderLogs.add(
                                                service.getLeaderLog()
                                        )

                                        val numberOfPeers = service.networkStats().size

                                        // Another method to get number of peers by by using established sockets from lsof
//                                        val numberOfPeers = establishedSocketsByProcessId(
//                                                processes[processNumber]?.process?.let {
//                                                    getPidOfProcess(it)
//                                                } ?: 0)

                                        latestStats[processNumber] = stats.copy(numberOfPeers = numberOfPeers)
                                        logger.info("Process${processNumber}: ${stats.lastBlockHeight} - ${stats.lastBlockHash}, peers: ${numberOfPeers}, uptime: ${stats.uptime}")
                                        maxBlockHeight = maxOf(maxBlockHeight, stats.lastBlockHeight?.toLong() ?: 0)

                                        stats.uptime?.let { uptime ->
                                            if (uptime > nodeProbationSecs) {
                                                // We've been up long enough. See if we've fallen behind the maxBlockHeight
                                                stats.lastBlockHeight?.toLong()?.let { lastBlockHeight ->
                                                    if (maxBlockHeight - lastBlockHeight > maxBlocksBehind) {
                                                        // We're more than the max blocks behind. kill this node.
                                                        logger.error("Process${processNumber}: has fallen behind, restarting...")
                                                        processesToRemove.add(processNumber)
                                                    }
                                                }
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
                                            }
                                        }
                                    }
                                    else -> {
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
                latestStats.forEach { entry ->
                    val stats = entry.value
                    if (stats.lastBlockHeight?.toLong() == maxBlockHeight) {
                        if (bestLeaderProcessCandidate == null) {
                            bestLeaderProcessCandidate = entry
                            return@forEach
                        } else {
                            // Just compare purely based on number of peers
                            if (stats.numberOfPeers!! > bestLeaderProcessCandidate?.value?.numberOfPeers!!) {
                                bestLeaderProcessCandidate = entry
                                return@forEach
                            }
                        }
                    }
                }

                val shouldPromoteNewLeader = bestLeaderProcessCandidate != null && bestLeaderProcessCandidate?.key != leaderProcessNumber
                if (leaderProcessNumber > -1 && shouldPromoteNewLeader) {
                    // remove leadership from previous leader
                    try {
                        services[leaderProcessNumber]?.removeLeadership(leaderId)
                        logger.warn("REMOVE LEADER: Process${leaderProcessNumber}")
                        leaderProcessNumber = -1
                        leaderId = -1
                    } catch (e: Throwable) {
                        logger.error("Unable to remove leadership from Process${leaderProcessNumber}!")
                    }
                }

                bestLeaderProcessCandidate?.let { entry ->
                    val processNumber = entry.key
                    val stats = entry.value

                    if (shouldPromoteNewLeader) {
                        Okio.buffer(Okio.source(File(jormungandrSecretJsonPath)))?.use { source ->
                            moshi.adapter(LeaderInfo::class.java)
                                    .fromJson(source)?.let { leaderInfo ->
                                        try {
                                            leaderId = services[processNumber]?.promoteToLeader(leaderInfo) ?: -1
                                            leaderProcessNumber = processNumber
                                            logger.warn("PROMOTE LEADER: Process${leaderProcessNumber}")
                                        } catch (e: Throwable) {
                                            logger.error("Unable to promote Process${processNumber} to leader", e)
                                        }
                                    } ?: logger.error("Unable to parse leader json file!!")
                        }
                    }

                    logger.info("CURRENT LEADER: Process${leaderProcessNumber}")

                    latestStats[leaderProcessNumber] = latestStats[leaderProcessNumber]!!.copy(leader = true)

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

                            pooltoolResult = pooltool.shareMyTip(
                                    poolId = pooltoolPoolId,
                                    userId = pooltoolUserId,
                                    genesisPref = pooltoolGenesisPref,
                                    lastBlockHeight = stats.lastBlockHeight!!,
                                    lastBlockHash = stats.lastBlockHash!!,
                                    lastPoolId = lastPoolId
                            )
                            logger.info("$pooltoolResult")
                        }
                    } catch (e: Throwable) {
                        logger.error("Error getting last block or updating pooltool!", e)
                    }
                }

                // log our status info
                val adapter = moshi.adapter(OutputStats::class.java)
                Okio.buffer(Okio.sink(File(statsLogPath))).use {
                    adapter.toJson(it, OutputStats(pooltoolResult, latestStats))
                }
            }
        }
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
    private suspend fun handleEpochCutover() {
        nextEpochTime?.let { nextEpochTime ->
            coroutineScope {
                if (DateTime.now().plusMillis(2 * leaderElectionDelayMs.toInt()).isAfter(nextEpochTime)) {
                    // lock the mutex so new nodes can't be spun up
                    mutex.withLock {
                        logger.warn("EPOCH IS ENDING SOON: Pausing Leader Election changes...")
                        val fiveSecondsBeforeEpoch = nextEpochTime.millis - System.currentTimeMillis() - 5000
                        if (fiveSecondsBeforeEpoch > 0) {
                            delay(fiveSecondsBeforeEpoch)
                        }

                        // Shutdown any nodes that are still bootstrapping so they don't interfere with epoch cutover
                        bootstrapJobs.forEach { (processNumber, job) ->
                            launch {
                                if (job.isActive) {
                                    logger.warn("CANCEL BOOTSTRAP JOB: Process$processNumber")
                                    job.cancel()
                                    shutdownProcess(processNumber)
                                }
                            }
                        }

                        // wait until 1.75 seconds before epoch cutover and make all running nodes into leaders
                        val justBeforeEpoch = nextEpochTime.millis - System.currentTimeMillis() - 1750
                        if (justBeforeEpoch > 0) {
                            delay(justBeforeEpoch)
                        }
                        val leaderPromotions = mutableListOf<Deferred<Any?>>()
                        val leaderInfo = Okio.buffer(Okio.source(File(jormungandrSecretJsonPath)))?.use { source ->
                            moshi.adapter(LeaderInfo::class.java).fromJson(source)
                        }
                        leaderInfo?.let { li ->
                            services.forEach { entry ->
                                val processNumber = entry.key
                                val service = entry.value
                                if (processNumber != leaderProcessNumber) {
                                    leaderPromotions.add(
                                            async {
                                                try {
                                                    service.promoteToLeader(li)
                                                    logger.warn("PROMOTE LEADER: Process${processNumber}")
                                                } catch (e: Throwable) {
                                                    logger.error("Unable to promote Process${processNumber} to leader", e)
                                                }
                                            }
                                    )
                                }
                            }
                        }

                        awaitAll(*leaderPromotions.toTypedArray())
                        logger.warn("EPOCH CUTOVER LEADER PROMOTIONS COMPLETED.")

                        // wait until 1.75 seconds after epoch cutover and make all leaders passive
                        val justAfterEpoch = nextEpochTime.millis - System.currentTimeMillis() + 1750
                        if (justAfterEpoch > 0) {
                            delay(justAfterEpoch)
                        }
                        val leaderDemotions = mutableListOf<Deferred<Any?>>()
                        services.forEach { entry ->
                            leaderDemotions.add(
                                    async {
                                        val processNumber = entry.key
                                        val service = entry.value
                                        if (processNumber != leaderProcessNumber) {
                                            try {
                                                service.removeLeadership(1)
                                                logger.warn("REMOVE LEADER: Process${processNumber}")
                                            } catch (e: Throwable) {
                                                logger.error("Unable to remove leadership from Process${processNumber}!")
                                            }
                                        }
                                    })
                        }
                        awaitAll(*leaderDemotions.toTypedArray())
                        logger.warn("EPOCH CUTOVER LEADER DEMOTIONS COMPLETED.")
                    }
                }
            }
        }
    }

    /**
     * Do some extra waiting if we're going to be making a block soon. We don't want to switch horses while minting a
     * block and potentially be without a leader. Otherwise, just wait the normal leader election delay timeperiod.
     */
    private suspend fun handleBlockMinting() {
        coroutineScope {
            nextBlockTime?.let {
                if (DateTime.now().plusMillis(2 * leaderElectionDelayMs.toInt()).isAfter(it)) {
                    // lock the mutex so new nodes can't be spun up
                    mutex.withLock {
                        logger.warn("BLOCK MINTING SOON: Pausing Leader Election changes...")
                        val fiveSecondsBeforeMinting = it.millis - System.currentTimeMillis() - 5000
                        if (fiveSecondsBeforeMinting > 0) {
                            delay(fiveSecondsBeforeMinting)
                        }

                        // Shutdown any nodes that are still bootstrapping so they don't interfere with minting
                        bootstrapJobs.forEach { (processNumber, job) ->
                            launch {
                                if (job.isActive) {
                                    logger.warn("CANCEL BOOTSTRAP JOB: Process$processNumber")
                                    job.cancel()
                                    shutdownProcess(processNumber)
                                }
                            }
                        }

                        delay(it.millis - System.currentTimeMillis() + 1500)
                        logger.warn("BLOCK SHOULD HAVE MINTED BY NOW: Resuming Leader Election process.")
                    }
                } else {
                    delay(leaderElectionDelayMs)
                }
            } ?: delay(leaderElectionDelayMs)
        }
    }

    private fun processLeaderLogs(leaderLogs: List<List<LeaderBlock>>) = launch {
        leaderLogMutex.withLock {
            val time = measureTimeMillis {
                val jsonAdapter = moshi.adapter<List<LeaderBlock>>(Types.newParameterizedType(List::class.java, LeaderBlock::class.java)).indent("  ")
                val leaderLogHistory = mutableListOf<LeaderBlock>()
                Okio.buffer(Okio.source(File(blockLogPath)))?.use { source ->
                    jsonAdapter.fromJson(source)?.let { leaderLog ->
                        leaderLogHistory.addAll(leaderLog)
                    } ?: logger.error("Unable to parse leader json file!!")
                }

                leaderLogs.forEach { leaderLog ->
                    leaderLog.forEach { newBlock ->
                        val historicalBlockIndex = leaderLogHistory.indexOfFirst { historicalBlock -> historicalBlock.scheduledAtDate == newBlock.scheduledAtDate }
                        if (historicalBlockIndex < 0) {
                            // Block was not found. Add it to the history
                            leaderLogHistory.add(newBlock)
                        } else {
                            // Block was found.
                            when (leaderLogHistory[historicalBlockIndex]) {
                                is PendingBlock -> {
                                    if (newBlock is RejectedBlock || newBlock is CompletedBlock) {
                                        // Replace the historical block with this one that has more information
                                        leaderLogHistory[historicalBlockIndex] = newBlock
                                    }
                                }
                                is RejectedBlock -> {
                                    if (newBlock is CompletedBlock) {
                                        // Replace the historical block that was probably from a demoted leader with this one that has more information
                                        leaderLogHistory[historicalBlockIndex] = newBlock
                                        logger.info("COMPLETED Block: ${newBlock.status.blockDetail}")
                                    } else if (newBlock is RejectedBlock && !newBlock.status.rejectedDetail.reason.contains("enclave")) {
                                        // Replace with the real rejected reason, not just that this leader was not in the enclave
                                        leaderLogHistory[historicalBlockIndex] = newBlock
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
                                        if (pooltoolPoolId == poolId) {
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
                Okio.buffer(Okio.sink(File(blockLogPath)))?.use { sink ->
                    jsonAdapter.toJson(sink, leaderLogHistory)
                }
            }
            logger.info("Updated Leader logs in : $time ms")
        }
    }

    private suspend fun establishedSocketsByProcessId(pid: Long): Int {
        return suspendCancellableCoroutine { continuation ->
            try {
                val process = ProcessBuilder(
                        "/bin/sh", "-c", "lsof -w -n -P -l -i -a -p $pid | grep ESTABLISHED | wc -l"
                ).start()

                continuation.invokeOnCancellation {
                    try {
                        process.destroy()
                    } catch (e: Throwable) {
                        logger.error("establishedSocketsByProcessId Error!", e)
                    }
                }

                val establishedSockets = Okio.buffer(Okio.source(process.inputStream)).use { source ->
                    source.readUtf8().replace('"', ' ').trim()
                }.toInt()
                continuation.resume(establishedSockets)
            } catch (e: Throwable) {
                logger.error("establishedSocketsByProcessId ERROR!", e)
                continuation.resumeWithException(e)
            }
        }
    }

    companion object {
        @Synchronized
        fun getPidOfProcess(p: Process): Long {
            var pid: Long = -1
            try {
                if (p.javaClass.name == "java.lang.UNIXProcess") {
                    val f: Field = p.javaClass.getDeclaredField("pid")
                    f.isAccessible = true
                    pid = f.getLong(p)
                    f.isAccessible = false
                }
            } catch (e: Exception) {
                pid = -1
            }
            return pid
        }
    }
}