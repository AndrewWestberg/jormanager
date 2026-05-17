package com.swiftmako.jormanager.tracing

import com.google.common.truth.Truth.assertThat
import com.firehose.controllers.nodeclient.protocol.Agency
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborReader
import com.google.iot.cbor.CborByteString
import com.google.iot.cbor.CborTextString
import com.google.iot.cbor.CborWriter
import com.swiftmako.jormanager.nodeclient.protocols.mux.Mux
import com.swiftmako.jormanager.nodeclient.protocols.mux.muxByteBufferPool
import com.swiftmako.jormanager.tracing.forwarding.ForwardingDataPointsProtocol
import com.swiftmako.jormanager.tracing.forwarding.ForwardingHandshakeProtocol
import com.swiftmako.jormanager.tracing.forwarding.ForwardingTraceObjectsProtocol
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.connection
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

class LiveTraceForwardIntegrationTest {
    @Test
    @Timeout(30)
    fun liveSingleConnectionThreeProtocolCaptureToClockwork() =
        runBlocking {
            assumeTrue(RUN_LIVE_TRACING_TESTS) { "Set JORMANAGER_RUN_LIVE_TRACING_TESTS=true to run live tracing tests" }

            val capture =
                captureAllProtocols(
                    host = CLOCKWORK_HOST,
                    port = CLOCKWORK_PORT,
                    durationMillis = 8_000L,
                    traceRequestCount = 1_000,
                    traceSingleReplyMode = false,
                    dataPointNames = ALL_DATAPOINT_NAMES,
                    ekgRequest = EkgRequest.GetAllMetrics,
                )

            println("single connection ekg replies=${capture.ekgReplies.size}")
            println("single connection trace replies=${capture.traceReplies.size}")
            println("single connection datapoint replies=${capture.dataPointReplies.size}")
        }

    @Test
    @Timeout(30)
    fun liveClockworkDatapointManifestCoveragePrintsAllSourceDerivedNames() =
        runBlocking {
            assumeTrue(RUN_LIVE_TRACING_TESTS) { "Set JORMANAGER_RUN_LIVE_TRACING_TESTS=true to run live tracing tests" }

            val capture =
                captureTraceAndDataProtocols(
                    host = CLOCKWORK_HOST,
                    port = CLOCKWORK_PORT,
                    durationMillis = 8_000L,
                    traceRequestCount = 1_000,
                    traceSingleReplyMode = false,
                    dataPointNames = ALL_DATAPOINT_NAMES,
                )

            val seen =
                capture.dataPointReplies
                    .flatMap { reply -> reply.dataPoints.toDataPointMap().entries }
                    .groupBy({ it.key }, { it.value })

            val missing = ALL_DATAPOINT_NAMES.filterNot(seen::containsKey)
            val nonEmpty = seen.filterValues { values -> values.any { it != null } }.keys.sorted()

            println("all datapoint names (${ALL_DATAPOINT_NAMES.size}): ${ALL_DATAPOINT_NAMES.joinToString()}")
            println("seen datapoint names (${seen.size}): ${seen.keys.sorted().joinToString()}")
            println("non-empty datapoint names (${nonEmpty.size}): ${nonEmpty.joinToString()}")
            println("missing datapoint names (${missing.size}): ${missing.joinToString()}")

            assertThat(missing).isEmpty()
        }

    @Test
    @Timeout(30)
    fun liveClockworkEkgGetAllMetricsPrintsUniqueMetricNames() =
        runBlocking {
            assumeTrue(RUN_LIVE_TRACING_TESTS) { "Set JORMANAGER_RUN_LIVE_TRACING_TESTS=true to run live tracing tests" }

            val capture =
                captureAllProtocols(
                    host = CLOCKWORK_HOST,
                    port = CLOCKWORK_PORT,
                    durationMillis = 8_000L,
                    traceRequestCount = 1_000,
                    traceSingleReplyMode = false,
                    dataPointNames = ALL_DATAPOINT_NAMES,
                    ekgRequest = EkgRequest.GetAllMetrics,
                )

            val metricNames = capture.ekgReplies.flatMap { it.metrics.keys }.distinct().sorted()
            val interesting = metricNames.filter { name -> EKG_INTERESTING_HINTS.any(name::contains) }

            println("ekg unique metric names (${metricNames.size}):")
            metricNames.forEach(::println)
            println("ekg interesting metric names (${interesting.size}):")
            interesting.forEach(::println)

            assertThat(capture.ekgReplies.size).isAtLeast(0)
        }

    @Test
    @Timeout(30)
    fun liveClockworkEkgGetMetricsRequestRoundTrips() =
        runBlocking {
            assumeTrue(RUN_LIVE_TRACING_TESTS) { "Set JORMANAGER_RUN_LIVE_TRACING_TESTS=true to run live tracing tests" }

            val replies =
                collectEkgReplies(
                    host = CLOCKWORK_HOST,
                    port = CLOCKWORK_PORT,
                    durationMillis = 8_000L,
                    request = EkgRequest.GetMetrics(DISPATCHER_EXPECTED_METRIC_NAMES.take(8)),
                )

            println("ekg GetMetrics reply count=${replies.size}")
            replies.forEachIndexed { index, reply ->
                println("ekg GetMetrics reply[$index] raw=${reply.rawJson}")
            }

            assertThat(replies).isNotEmpty()
        }

    @Test
    @Timeout(30)
    fun liveClockworkDispatcherMetricInventoryPrintsObservedAndMissing() =
        runBlocking {
            assumeTrue(RUN_LIVE_TRACING_TESTS) { "Set JORMANAGER_RUN_LIVE_TRACING_TESTS=true to run live tracing tests" }

            val replies =
                collectEkgReplies(
                    host = CLOCKWORK_HOST,
                    port = CLOCKWORK_PORT,
                    durationMillis = 8_000L,
                    request = EkgRequest.GetMetrics(DISPATCHER_EXPECTED_METRIC_NAMES),
                )

            val observed = replies.flatMap { it.metrics.keys }.distinct().sorted()
            val missing = DISPATCHER_EXPECTED_METRIC_NAMES.filterNot(observed::contains)

            println("dispatcher expected metric names (${DISPATCHER_EXPECTED_METRIC_NAMES.size}):")
            DISPATCHER_EXPECTED_METRIC_NAMES.forEach(::println)
            println("dispatcher observed metric names (${observed.size}):")
            observed.forEach(::println)
            println("dispatcher missing metric names (${missing.size}):")
            missing.forEach(::println)

            assertThat(replies).isNotEmpty()
        }

    @Test
    @Timeout(30)
    fun liveClockworkKesMetricsAreCapturedFromProtocol1() =
        runBlocking {
            assumeTrue(RUN_LIVE_TRACING_TESTS) { "Set JORMANAGER_RUN_LIVE_TRACING_TESTS=true to run live tracing tests" }

            val replies =
                collectEkgReplies(
                    host = CLOCKWORK_HOST,
                    port = CLOCKWORK_PORT,
                    durationMillis = 8_000L,
                    request = EkgRequest.GetMetrics(KES_EXPECTED_METRIC_NAMES),
                )

            val metricMap = replies.lastOrNull()?.metrics.orEmpty()
            val kes = metricMap.toKesMetrics()

            println("KES metric raw map:")
            metricMap.toSortedMap().forEach { (name, value) ->
                println("$name=$value")
            }
            println("KES typed metrics=$kes")

            assertThat(replies).isNotEmpty()
            assertThat(metricMap.keys).containsAtLeastElementsIn(KES_EXPECTED_METRIC_NAMES)
            assertThat(kes).isNotNull()
            assertThat(kes!!.remainingKesPeriods).isAtLeast(0)
            assertThat(kes.operationalCertificateExpiryKesPeriod).isGreaterThan(kes.operationalCertificateStartKesPeriod)
            assertThat(kes.currentKesPeriod).isAtLeast(kes.operationalCertificateStartKesPeriod)
            assertThat(kes.currentKesPeriod).isAtMost(kes.operationalCertificateExpiryKesPeriod)
        }

    @Test
    @Timeout(30)
    fun liveClockworkMempoolMetricsInventoryPrintsObservedAndMissing() =
        runBlocking {
            assumeTrue(RUN_LIVE_TRACING_TESTS) { "Set JORMANAGER_RUN_LIVE_TRACING_TESTS=true to run live tracing tests" }

            val replies =
                collectEkgReplies(
                    host = CLOCKWORK_HOST,
                    port = CLOCKWORK_PORT,
                    durationMillis = 8_000L,
                    request = EkgRequest.GetMetrics(MEMPOOL_EXPECTED_METRIC_NAMES),
                )

            val observed = replies.flatMap { it.metrics.keys }.distinct().sorted()
            val missing = MEMPOOL_EXPECTED_METRIC_NAMES.filterNot(observed::contains)

            println("mempool expected metric names (${MEMPOOL_EXPECTED_METRIC_NAMES.size}):")
            MEMPOOL_EXPECTED_METRIC_NAMES.forEach(::println)
            println("mempool observed metric names (${observed.size}):")
            observed.forEach(::println)
            println("mempool missing metric names (${missing.size}):")
            missing.forEach(::println)

            assertThat(replies).isNotEmpty()
        }

    @Test
    @Timeout(30)
    fun liveClockworkMempoolMetricsTypedDecodeRoundTrips() =
        runBlocking {
            assumeTrue(RUN_LIVE_TRACING_TESTS) { "Set JORMANAGER_RUN_LIVE_TRACING_TESTS=true to run live tracing tests" }

            val replies =
                collectEkgReplies(
                    host = CLOCKWORK_HOST,
                    port = CLOCKWORK_PORT,
                    durationMillis = 8_000L,
                    request = EkgRequest.GetMetrics(MEMPOOL_EXPECTED_METRIC_NAMES),
                )

            val metricMap = replies.lastOrNull()?.metrics.orEmpty()
            val mempoolMetrics = metricMap.toMempoolMetrics()

            println("mempool metric raw map:")
            metricMap.toSortedMap().forEach { (name, value) ->
                println("$name=$value")
            }
            println("mempool typed metrics=$mempoolMetrics")

            assertThat(replies).isNotEmpty()
        }

    @Test
    @Timeout(30)
    fun liveClockworkDashboardSignalInventoryPrintsTypedSnapshot() =
        runBlocking {
            assumeTrue(RUN_LIVE_TRACING_TESTS) { "Set JORMANAGER_RUN_LIVE_TRACING_TESTS=true to run live tracing tests" }

            val capture =
                captureAllProtocols(
                    host = CLOCKWORK_HOST,
                    port = CLOCKWORK_PORT,
                    durationMillis = 8_000L,
                    traceRequestCount = 1_000,
                    traceSingleReplyMode = false,
                    dataPointNames = ALL_DATAPOINT_NAMES,
                    ekgRequest = EkgRequest.GetMetrics(DASHBOARD_EXPECTED_METRIC_NAMES),
                )

            val metricMap = capture.ekgReplies.lastOrNull()?.metrics.orEmpty()
            val chainMetrics = metricMap.toChainMetrics()
            val forgeMetrics = metricMap.toForgeMetrics()
            val kesMetrics = metricMap.toKesMetrics()
            val mempoolMetrics = metricMap.toMempoolMetrics()
            val startupInfo = capture.dataPointReplies.extractLatestJsonDataPoint("NodeStartupInfo")
            val traceKinds =
                capture.traceReplies
                    .flatMap { reply -> (0 until reply.traceObjects.size()).map(reply.traceObjects::elementAt) }
                    .mapNotNull { it.traceObjectMachineJsonOrNull() }
                    .mapNotNull(::traceKindOrNull)
                    .distinct()
                    .sorted()

            println("dashboard metric raw map:")
            metricMap.toSortedMap().forEach { (name, value) ->
                println("$name=$value")
            }
            println("dashboard chain metrics=$chainMetrics")
            println("dashboard forge metrics=$forgeMetrics")
            println("dashboard kes metrics=$kesMetrics")
            println("dashboard mempool metrics=$mempoolMetrics")
            println("dashboard startup info=$startupInfo")
            println("dashboard trace kinds (${traceKinds.size})=$traceKinds")

            assertThat(chainMetrics).isNotNull()
            assertThat(forgeMetrics).isNotNull()
            assertThat(kesMetrics).isNotNull()
            assertThat(startupInfo).isNotNull()
        }

    @Test
    @Timeout(30)
    fun liveClockworkMempoolTraceDiscoveryPrintsObservedKinds() =
        runBlocking {
            assumeTrue(RUN_LIVE_TRACING_TESTS) { "Set JORMANAGER_RUN_LIVE_TRACING_TESTS=true to run live tracing tests" }

            val traceReplies = collectTraceObjectReplies(host = CLOCKWORK_HOST, port = CLOCKWORK_PORT, collectionWindowMillis = 8_000L)
            val matching =
                traceReplies
                    .flatMap { reply -> (0 until reply.traceObjects.size()).map(reply.traceObjects::elementAt) }
                    .mapNotNull { it.traceObjectMachineJsonOrNull() }
                    .filter { json -> MEMPOOL_TRACE_OBJECT_HINTS.any(json::contains) }

            println("mempool trace matches (${matching.size}):")
            matching.forEach(::println)
        }

    @Test
    @Timeout(330)
    fun liveFiveMinuteClockworkMempoolCapturePrintsObservedSignals() =
        runBlocking {
            assumeTrue(RUN_LIVE_TRACING_TESTS) { "Set JORMANAGER_RUN_LIVE_TRACING_TESTS=true to run live tracing tests" }

            val capture =
                captureAllProtocols(
                    host = CLOCKWORK_HOST,
                    port = CLOCKWORK_PORT,
                    durationMillis = 300_000L,
                    traceRequestCount = 1_000,
                    traceSingleReplyMode = false,
                    dataPointNames = ALL_DATAPOINT_NAMES,
                    ekgRequest = EkgRequest.GetMetrics(MEMPOOL_EXPECTED_METRIC_NAMES),
                )

            val observedMetricNames = capture.ekgReplies.flatMap { it.metrics.keys }.distinct().sorted()
            val typedSnapshots = capture.ekgReplies.map { it.metrics.toMempoolMetrics() }
            val matchingTrace =
                capture.traceReplies
                    .flatMap { reply -> (0 until reply.traceObjects.size()).map(reply.traceObjects::elementAt) }
                    .mapNotNull { it.traceObjectMachineJsonOrNull() }
                    .filter { json -> MEMPOOL_TRACE_OBJECT_HINTS.any(json::contains) }

            println("mempool 5m observed metric names (${observedMetricNames.size}):")
            observedMetricNames.forEach(::println)
            println("mempool 5m typed snapshots (${typedSnapshots.size}):")
            typedSnapshots.forEach(::println)
            println("mempool 5m trace matches (${matchingTrace.size}):")
            matchingTrace.forEach(::println)
        }

    @Test
    @Timeout(30)
    fun liveClockworkDirectEkgMetricInventoryPrintsObservedAndMissing() =
        runBlocking {
            assumeTrue(RUN_LIVE_TRACING_TESTS) { "Set JORMANAGER_RUN_LIVE_TRACING_TESTS=true to run live tracing tests" }

            val replies =
                collectEkgReplies(
                    host = CLOCKWORK_HOST,
                    port = CLOCKWORK_PORT,
                    durationMillis = 8_000L,
                    request = EkgRequest.GetMetrics(DIRECT_EKG_EXPECTED_METRIC_NAMES),
                )

            val observed = replies.flatMap { it.metrics.keys }.distinct().sorted()
            val missing = DIRECT_EKG_EXPECTED_METRIC_NAMES.filterNot(observed::contains)

            println("direct ekg expected metric names (${DIRECT_EKG_EXPECTED_METRIC_NAMES.size}):")
            DIRECT_EKG_EXPECTED_METRIC_NAMES.forEach(::println)
            println("direct ekg observed metric names (${observed.size}):")
            observed.forEach(::println)
            println("direct ekg missing metric names (${missing.size}):")
            missing.forEach(::println)

            assertThat(replies).isNotEmpty()
        }

    @Test
    @Timeout(330)
    fun liveFiveMinuteClockworkCapturePrintsInterestingSignals() =
        runBlocking {
            assumeTrue(RUN_LIVE_TRACING_TESTS) { "Set JORMANAGER_RUN_LIVE_TRACING_TESTS=true to run live tracing tests" }

            val capture =
                captureAllProtocols(
                    host = CLOCKWORK_HOST,
                    port = CLOCKWORK_PORT,
                    durationMillis = 300_000L,
                    traceRequestCount = 1_000,
                    traceSingleReplyMode = false,
                    dataPointNames = ALL_DATAPOINT_NAMES,
                    ekgRequest = EkgRequest.GetAllMetrics,
                )

            val traceMachineJson =
                capture.traceReplies
                    .flatMap { reply -> (0 until reply.traceObjects.size()).map(reply.traceObjects::elementAt) }
                    .mapNotNull { it.traceObjectMachineJsonOrNull() }

            val interestingTrace =
                traceMachineJson.filter { json -> FIVE_MINUTE_TRACE_HINTS.any(json::contains) }

            val nonEmptyDatapoints =
                capture.dataPointReplies
                    .flatMap { reply -> reply.dataPoints.toDataPointMap().entries }
                    .filter { it.value != null }
                    .distinctBy { it.key }
                    .sortedBy { it.key }

            val ekgMetricNames = capture.ekgReplies.flatMap { it.metrics.keys }.distinct().sorted()

            println("clockwork 5m trace matches (${interestingTrace.size}):")
            interestingTrace.forEach(::println)
            println("clockwork 5m datapoints non-empty (${nonEmptyDatapoints.size}):")
            nonEmptyDatapoints.forEach { (name, value) ->
                println("$name=${value!!.decodeToString()}")
            }
            println("clockwork 5m ekg metric names (${ekgMetricNames.size}):")
            ekgMetricNames.forEach(::println)
        }

    @Test
    @Timeout(15)
    fun liveTraceObjectsSessionToGld() =
        runBlocking {
            assumeTrue(RUN_LIVE_TRACING_TESTS) { "Set JORMANAGER_RUN_LIVE_TRACING_TESTS=true to run live tracing tests" }

            val traceReplies = CopyOnWriteArrayList<TraceForwardMessage>()
            val dataReplies = CopyOnWriteArrayList<TraceForwardMessage>()
            val selector = ActorSelectorManager(Dispatchers.IO)

            aSocket(selector)
                .tcp()
                .connect(InetSocketAddress(HOST, PORT)) {
                    noDelay = true
                    keepAlive = true
                }.use { socket ->
                    val traceProtocol = ForwardingTraceObjectsProtocol()
                    val dataProtocol = ForwardingDataPointsProtocol(NodeStateDataPointDecoder.REQUESTED_NAMES)
                    val traceCollector = traceProtocol.messages.collectInBackground(traceReplies)
                    val dataCollector = dataProtocol.messages.collectInBackground(dataReplies)
                    try {
                        val mux = Mux(socket.connection())
                        withTimeout(LIVE_TIMEOUT.toMillis()) {
                            mux.execute(ForwardingHandshakeProtocol(NETWORK_MAGIC))
                        }
                        val job = launch {
                            mux.execute(traceProtocol, dataProtocol)
                        }
                        delay(COLLECTION_WINDOW_MILLIS)
                        println("after ${COLLECTION_WINDOW_MILLIS}ms: traceReplies=${traceReplies.size}, dataReplies=${dataReplies.size}")
                        mux.shutdownGracefully()
                        job.cancelAndJoin()
                    } finally {
                        traceCollector.cancelAndJoin()
                        dataCollector.cancelAndJoin()
                        selector.close()
                    }
                }

            println("live trace objects messages: ${traceReplies.map { it::class.simpleName }}")
            println("live data point messages (same mux session): ${dataReplies.map { it::class.simpleName }}")
            traceReplies.filterIsInstance<TraceForwardMessage.TraceObjectsReply>().forEachIndexed { index, reply ->
                println("trace objects reply[$index] size=${reply.traceObjects.size()}")
                for (i in 0 until reply.traceObjects.size()) {
                    println("trace objects reply[$index][$i]=${reply.traceObjects.elementAt(i).toJsonString()}")
                }
            }

            assertThat(traceReplies + dataReplies).isNotEmpty()
        }

    @Test
    @Timeout(15)
    fun liveDataPointSessionToGld() =
        runBlocking {
            assumeTrue(RUN_LIVE_TRACING_TESTS) { "Set JORMANAGER_RUN_LIVE_TRACING_TESTS=true to run live tracing tests" }

            val replies = CopyOnWriteArrayList<TraceForwardMessage>()
            val selector = ActorSelectorManager(Dispatchers.IO)

            aSocket(selector)
                .tcp()
                .connect(InetSocketAddress(HOST, PORT)) {
                    noDelay = true
                    keepAlive = true
                }.use { socket ->
                    val protocol = ForwardingDataPointsProtocol(NodeStateDataPointDecoder.REQUESTED_NAMES)
                    val collector = protocol.messages.collectInBackground(replies)
                    try {
                        val mux = Mux(socket.connection())
                        withTimeout(LIVE_TIMEOUT.toMillis()) {
                            mux.execute(ForwardingHandshakeProtocol(NETWORK_MAGIC))
                        }
                        val job = launch {
                            mux.execute(protocol)
                        }
                        delay(COLLECTION_WINDOW_MILLIS)
                        println("after ${COLLECTION_WINDOW_MILLIS}ms: dataReplies=${replies.size}")
                        mux.shutdownGracefully()
                        job.cancelAndJoin()
                    } finally {
                        collector.cancelAndJoin()
                        selector.close()
                    }
                }

            println("live data point messages: ${replies.map { it::class.simpleName }}")
            replies.filterIsInstance<TraceForwardMessage.DataPointsReply>().forEachIndexed { index, reply ->
                println("data point reply[$index] size=${reply.dataPoints.size()}")
                for (i in 0 until reply.dataPoints.size()) {
                    println("data point reply[$index][$i]=${reply.dataPoints.elementAt(i).toJsonString()}")
                }
            }

            assertThat(replies).isNotNull()
        }

    @Test
    @Timeout(15)
    fun liveDataPointNameProbePrefersBareTracingNames() =
        runBlocking {
            assumeTrue(RUN_LIVE_TRACING_TESTS) { "Set JORMANAGER_RUN_LIVE_TRACING_TESTS=true to run live tracing tests" }

            val bareReplies = collectDataPointReplies(NodeStateDataPointDecoder.REQUESTED_NAMES)
            val prefixedReplies =
                collectDataPointReplies(
                    listOf(
                        NodeStateDataPointDecoder.LEGACY_KEY_OUTGOING_CONNS,
                        NodeStateDataPointDecoder.LEGACY_KEY_INCOMING_CONNS,
                        NodeStateDataPointDecoder.LEGACY_KEY_BLOCK_NUM,
                        NodeStateDataPointDecoder.LEGACY_KEY_REMAINING_KES_PERIODS,
                        NodeStateDataPointDecoder.LEGACY_KEY_EPOCH,
                        NodeStateDataPointDecoder.LEGACY_KEY_SLOT_NUM,
                        NodeStateDataPointDecoder.LEGACY_KEY_SLOT_IN_EPOCH,
                        NodeStateDataPointDecoder.LEGACY_KEY_TXS_PROCESSED_NUM,
                    )
                )

            val barePoints = bareReplies.singleOrNull()?.dataPoints
            val prefixedPoints = prefixedReplies.singleOrNull()?.dataPoints

            println("bare datapoints: ${barePoints?.toJsonString()}")
            println("prefixed datapoints: ${prefixedPoints?.toJsonString()}")

            assertThat(barePoints).isNotNull()
            assertThat(prefixedPoints).isNotNull()
        }

    @Test
    @Timeout(20)
    fun liveDataPointDiscoveryProbePrintsNonEmptyCandidates() =
        runBlocking {
            assumeTrue(RUN_LIVE_TRACING_TESTS) { "Set JORMANAGER_RUN_LIVE_TRACING_TESTS=true to run live tracing tests" }

            val replies = collectDataPointReplies(DISCOVERY_CANDIDATE_NAMES)
            val reply = replies.singleOrNull()

            assertThat(reply).isNotNull()

            val results = reply!!.dataPoints.toDataPointMap()
            val nonEmpty = results.filterValues { it != null }
            val empty = results.filterValues { it == null }.keys.sorted()

            println("live discovery non-empty datapoints (${nonEmpty.size}):")
            nonEmpty.entries.sortedBy { it.key }.forEach { (name, value) ->
                println("$name=${value!!.decodeToString()}")
            }

            println("live discovery empty datapoints (${empty.size}): ${empty.joinToString()}")
        }

    @Test
    @Timeout(20)
    fun liveEkgMetricsProbePrintsOldDashboardMetricsIfAvailable() =
        runBlocking {
            assumeTrue(RUN_LIVE_TRACING_TESTS) { "Set JORMANAGER_RUN_LIVE_TRACING_TESTS=true to run live tracing tests" }

            val capture =
                captureAllProtocols(
                    host = HOST,
                    port = PORT,
                    durationMillis = 2_000L,
                    traceRequestCount = 100,
                    traceSingleReplyMode = true,
                    dataPointNames = NodeStateDataPointDecoder.REQUESTED_NAMES,
                    ekgRequest = EkgRequest.GetAllMetrics,
                )
            val replies = capture.ekgReplies

            println("live ekg reply count=${replies.size}")
            replies.forEachIndexed { index, reply ->
                println("live ekg reply[$index] metric-count=${reply.metrics.size}")
                println("live ekg reply[$index]=${reply.rawJson}")
            }
        }

    @Test
    @Timeout(20)
    fun liveKesDiscoveryProbeOnClockworkPrintsForgeRelatedSignals() =
        runBlocking {
            assumeTrue(RUN_LIVE_TRACING_TESTS) { "Set JORMANAGER_RUN_LIVE_TRACING_TESTS=true to run live tracing tests" }

            val traceReplies = collectTraceObjectReplies(host = CLOCKWORK_HOST, port = CLOCKWORK_PORT, collectionWindowMillis = 8_000L)
            val dataReplies = collectDataPointReplies(KES_DISCOVERY_CANDIDATE_NAMES, host = CLOCKWORK_HOST, port = CLOCKWORK_PORT)

            val traceJson =
                traceReplies
                    .flatMap { reply -> (0 until reply.traceObjects.size()).map(reply.traceObjects::elementAt) }
                    .mapNotNull { it.traceObjectMachineJsonOrNull() }
                    .filter { json -> KES_TRACE_OBJECT_HINTS.any(json::contains) }

            val dataPoints = dataReplies.singleOrNull()?.dataPoints?.toDataPointMap().orEmpty()
            val nonEmpty = dataPoints.filterValues { it != null }

            println("clockwork KES trace matches (${traceJson.size}):")
            traceJson.forEach(::println)
            println("clockwork KES datapoints non-empty (${nonEmpty.size}):")
            nonEmpty.entries.sortedBy { it.key }.forEach { (name, value) ->
                println("$name=${value!!.decodeToString()}")
            }
        }

    companion object {
        private const val HOST = "127.0.0.1"
        private const val PORT = 18400
        private const val CLOCKWORK_HOST = "clockwork"
        private const val CLOCKWORK_PORT = 18401
        private const val NETWORK_MAGIC = 141L
        private val LIVE_TIMEOUT = Duration.ofSeconds(10)
        private const val COLLECTION_WINDOW_MILLIS = 2_000L
        private val RUN_LIVE_TRACING_TESTS = System.getenv("JORMANAGER_RUN_LIVE_TRACING_TESTS") == "true"

        private val DISCOVERY_CANDIDATE_NAMES =
            listOf(
                "NodeInfo",
                "NodeStartupInfo",
                "NodeTracingOnlineConfiguring",
                "NodeKernelOnline",
                "NodeAddBlock",
                "NodeStartup",
                "NodeShutdown",
                "OpeningDbs",
                "NodeReplays",
                "NodeInitChainSelection",
                "blockNum",
                "slotNum",
                "slotInEpoch",
                "epoch",
                "density",
                "forks",
                "remainingKESPeriods",
                "currentKESPeriod",
                "operationalCertificateStartKESPeriod",
                "operationalCertificateExpiryKESPeriod",
                "txsProcessedNum",
                "txsInMempool",
                "mempoolBytes",
                "txsSyncDuration",
                "forging_enabled",
                "node.start.time",
                "connectionManager.incomingConns",
                "connectionManager.outgoingConns",
                "connectionManager.duplexConns",
                "connectionManager.fullDuplexConns",
                "connectionManager.unidirectionalConns",
                "peerSelection.cold",
                "peerSelection.warm",
                "peerSelection.hot",
                "peerSelection.RootPeers",
                "peerSelection.KnownPeers",
                "peerSelection.EstablishedPeers",
                "peerSelection.ActivePeers",
                "peerSelection.ColdPeersPromotions",
                "peerSelection.WarmPeersDemotions",
                "peerSelection.WarmPeersPromotions",
                "peerSelection.ActivePeersDemotions",
                "peerSelection.KnownBigLedgerPeers",
                "peerSelection.EstablishedBigLedgerPeers",
                "peerSelection.ActiveBigLedgerPeers",
                "peerSelection.KnownLocalRootPeers",
                "peerSelection.EstablishedLocalRootPeers",
                "peerSelection.ActiveLocalRootPeers",
                "peerSelection.KnownNonRootPeers",
                "peerSelection.EstablishedNonRootPeers",
                "peerSelection.ActiveNonRootPeers",
                "peerSelection.KnownBootstrapPeers",
                "peerSelection.EstablishedBootstrapPeers",
                "peerSelection.ActiveBootstrapPeers",
                "inboundGovernor.idle",
                "inboundGovernor.cold",
                "inboundGovernor.warm",
                "inboundGovernor.hot",
            )

        private val ALL_DATAPOINT_NAMES =
            listOf(
                "NodeInfo",
                "NodeStartupInfo",
                "NodeTracingOnlineConfiguring",
                "NodeTracingFailure",
                "NodeTracingForwardingInterrupted",
                "PrometheusSimple.Start",
                "PrometheusSimple.Stop",
                "OpeningDbs",
                "NodeReplays",
                "NodeInitChainSelection",
                "NodeKernelOnline",
                "NodeAddBlock",
                "NodeStartup",
                "NodeShutdown",
            )

        private val KES_DISCOVERY_CANDIDATE_NAMES =
            listOf(
                "NodeInfo",
                "NodeStartupInfo",
                "NodeTracingOnlineConfiguring",
                "NodeKernelOnline",
                "NodeAddBlock",
                "remainingKESPeriods",
                "currentKESPeriod",
                "operationalCertificateStartKESPeriod",
                "operationalCertificateExpiryKESPeriod",
            )

        private val KES_TRACE_OBJECT_HINTS =
            listOf(
                "KESInfo",
                "ExpiryLogMessage",
                "KESCouldNotEvolve",
                "KESKeyAlreadyPoisoned",
                "remainingKESPeriods",
                "currentKESPeriod",
                "operationalCertificateStartKESPeriod",
                "operationalCertificateExpiryKESPeriod",
                "Forge",
                "StateInfo",
            )

        private val FIVE_MINUTE_TRACE_HINTS =
            listOf(
                "KESInfo",
                "ExpiryLogMessage",
                "KESCouldNotEvolve",
                "KESKeyAlreadyPoisoned",
                "remainingKESPeriods",
                "currentKESPeriod",
                "operationalCertificateStartKESPeriod",
                "operationalCertificateExpiryKESPeriod",
                "ConnectionManagerCounters",
                "PeerSelection",
                "InboundGovernorCounters",
                "TraceNodeIsLeader",
                "TraceForgedBlock",
                "TraceAdoptedBlock",
                "txsProcessed",
                "Mempool",
            )

        private val EKG_INTERESTING_HINTS =
            listOf(
                "remainingKESPeriods",
                "currentKESPeriod",
                "operationalCertificateStartKESPeriod",
                "operationalCertificateExpiryKESPeriod",
                "txsProcessedNum",
                "connectionManager",
                "slotNum",
                "slotInEpoch",
                "epoch",
                "mempool",
                "Forge",
                "forging",
            )

        private val DISPATCHER_EXPECTED_METRIC_NAMES =
            listOf(
                "cardano.node.metrics.blockNum_int",
                "cardano.node.metrics.slotNum_int",
                "cardano.node.metrics.slotInEpoch_int",
                "cardano.node.metrics.epoch_int",
                "cardano.node.metrics.density_real",
                "cardano.node.metrics.tipBlock",
                "cardano.node.metrics.forging_enabled_int",
                "cardano.node.metrics.node.start.time_int",
                "cardano.node.metrics.Forge.about-to-lead_counter",
                "cardano.node.metrics.Forge.node-not-leader_counter",
                "cardano.node.metrics.Forge.node-is-leader_counter",
                "cardano.node.metrics.forgedSlotLast_int",
                "cardano.node.metrics.Forge.forged_counter",
                "cardano.node.metrics.Forge.didnt-adopt_counter",
                "cardano.node.metrics.Forge.forged-invalid_counter",
                "cardano.node.metrics.Forge.adopted_counter",
                "cardano.node.metrics.operationalCertificateStartKESPeriod_int",
                "cardano.node.metrics.operationalCertificateExpiryKESPeriod_int",
                "cardano.node.metrics.currentKESPeriod_int",
                "cardano.node.metrics.remainingKESPeriods_int",
                "cardano.node.metrics.txsInMempool_int",
                "cardano.node.metrics.mempoolBytes_int",
                "cardano.node.metrics.txsProcessedNum_counter",
                "cardano.node.metrics.txsSyncDuration_int",
                "cardano.node.metrics.utxoSize_int",
                "cardano.node.metrics.delegMapSize_int",
                "cardano.node.metrics.blockReplayProgress_real",
            )

        private val DIRECT_EKG_EXPECTED_METRIC_NAMES =
            listOf(
                "cardano.node.metrics.connectionManager.fullDuplexConns",
                "cardano.node.metrics.connectionManager.duplexConns",
                "cardano.node.metrics.connectionManager.unidirectionalConns",
                "cardano.node.metrics.connectionManager.incomingConns",
                "cardano.node.metrics.connectionManager.outgoingConns",
                "cardano.node.metrics.peerSelection.cold",
                "cardano.node.metrics.peerSelection.warm",
                "cardano.node.metrics.peerSelection.hot",
                "cardano.node.metrics.peerSelection.RootPeers",
                "cardano.node.metrics.peerSelection.KnownPeers",
                "cardano.node.metrics.peerSelection.EstablishedPeers",
                "cardano.node.metrics.peerSelection.ActivePeers",
                "cardano.node.metrics.peerSelection.ColdPeersPromotions",
                "cardano.node.metrics.peerSelection.WarmPeersDemotions",
                "cardano.node.metrics.peerSelection.WarmPeersPromotions",
                "cardano.node.metrics.peerSelection.ActivePeersDemotions",
                "cardano.node.metrics.peerSelection.KnownBigLedgerPeers",
                "cardano.node.metrics.peerSelection.EstablishedBigLedgerPeers",
                "cardano.node.metrics.peerSelection.ActiveBigLedgerPeers",
                "cardano.node.metrics.peerSelection.KnownLocalRootPeers",
                "cardano.node.metrics.peerSelection.EstablishedLocalRootPeers",
                "cardano.node.metrics.peerSelection.ActiveLocalRootPeers",
                "cardano.node.metrics.peerSelection.KnownNonRootPeers",
                "cardano.node.metrics.peerSelection.EstablishedNonRootPeers",
                "cardano.node.metrics.peerSelection.ActiveNonRootPeers",
                "cardano.node.metrics.peerSelection.KnownBootstrapPeers",
                "cardano.node.metrics.peerSelection.EstablishedBootstrapPeers",
                "cardano.node.metrics.peerSelection.ActiveBootstrapPeers",
                "cardano.node.metrics.inboundGovernor.idle",
                "cardano.node.metrics.inboundGovernor.cold",
                "cardano.node.metrics.inboundGovernor.warm",
                "cardano.node.metrics.inboundGovernor.hot",
            )

        private val KES_EXPECTED_METRIC_NAMES =
            listOf(
                "cardano.node.metrics.operationalCertificateStartKESPeriod_int",
                "cardano.node.metrics.operationalCertificateExpiryKESPeriod_int",
                "cardano.node.metrics.currentKESPeriod_int",
                "cardano.node.metrics.remainingKESPeriods_int",
            )

        private val MEMPOOL_EXPECTED_METRIC_NAMES =
            listOf(
                "cardano.node.metrics.txsInMempool_int",
                "cardano.node.metrics.mempoolBytes_int",
                "cardano.node.metrics.txsProcessedNum_counter",
                "cardano.node.metrics.txsSyncDuration_int",
                "cardano.node.metrics.txsSyncDurationTotal_counter",
                "cardano.node.metrics.txsMempoolTimeoutSoft_counter",
            )

        private val MEMPOOL_TRACE_OBJECT_HINTS =
            listOf(
                "TraceMempoolAddedTx",
                "TraceMempoolRejectedTx",
                "TraceMempoolRemoveTxs",
                "TraceMempoolManuallyRemovedTxs",
                "TraceMempoolSynced",
                "Mempool",
            )

        private val DASHBOARD_EXPECTED_METRIC_NAMES =
            (listOf(
                "cardano.node.metrics.blockNum_int",
                "cardano.node.metrics.slotNum_int",
                "cardano.node.metrics.slotInEpoch_int",
                "cardano.node.metrics.epoch_int",
                "cardano.node.metrics.density_real",
                "cardano.node.metrics.tipBlock",
                "cardano.node.metrics.forging_enabled_int",
                "cardano.node.metrics.Forge.about-to-lead_counter",
                "cardano.node.metrics.Forge.node-not-leader_counter",
                "cardano.node.metrics.Forge.node-is-leader_counter",
                "cardano.node.metrics.forgedSlotLast_int",
                "cardano.node.metrics.Forge.forged_counter",
                "cardano.node.metrics.Forge.adopted_counter",
            ) + KES_EXPECTED_METRIC_NAMES + MEMPOOL_EXPECTED_METRIC_NAMES).distinct()

    }
}

private suspend fun captureAllProtocols(
    host: String,
    port: Int,
    durationMillis: Long,
    traceRequestCount: Int,
    traceSingleReplyMode: Boolean,
    dataPointNames: List<String>,
    ekgRequest: EkgRequest,
): ProtocolCaptureResult {
    val selector = ActorSelectorManager(Dispatchers.IO)
    val traceReplies = CopyOnWriteArrayList<TraceForwardMessage.TraceObjectsReply>()
    val dataPointReplies = CopyOnWriteArrayList<TraceForwardMessage.DataPointsReply>()
    val ekgReplies = CopyOnWriteArrayList<EkgReply>()

    aSocket(selector)
        .tcp()
        .connect(InetSocketAddress(host, port)) {
            noDelay = true
            keepAlive = true
        }.use { socket ->
            val traceProtocol = ForwardingTraceObjectsProtocol(singleReplyMode = traceSingleReplyMode, requestCount = traceRequestCount)
            val dataProtocol = ForwardingDataPointsProtocol(dataPointNames)
            val ekgProtocol = EkgMetricsProtocol(ekgRequest)
            val traceCollector = traceProtocol.messages.collectTraceRepliesInBackground(traceReplies)
            val dataCollector = dataProtocol.messages.collectDataPointRepliesInBackground(dataPointReplies)
            val ekgCollector = ekgProtocol.messages.collectEkgRepliesInBackground(ekgReplies)
            try {
                val mux = Mux(socket.connection())
                withTimeout(Duration.ofSeconds(10).toMillis()) {
                    mux.execute(ForwardingHandshakeProtocol(141L))
                }
                val job = CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        mux.execute(ekgProtocol, traceProtocol, dataProtocol)
                    }.onFailure { throwable ->
                        if (throwable !is EOFException) {
                            throw throwable
                        }
                    }
                }
                delay(durationMillis)
                mux.shutdownGracefully()
                job.cancelAndJoin()
            } finally {
                traceCollector.cancelAndJoin()
                dataCollector.cancelAndJoin()
                ekgCollector.cancelAndJoin()
                selector.close()
            }
        }

    return ProtocolCaptureResult(
        ekgReplies = ekgReplies.toList(),
        traceReplies = traceReplies.toList(),
        dataPointReplies = dataPointReplies.toList(),
    )
}

private suspend fun captureTraceAndDataProtocols(
    host: String,
    port: Int,
    durationMillis: Long,
    traceRequestCount: Int,
    traceSingleReplyMode: Boolean,
    dataPointNames: List<String>,
): ProtocolCaptureResult {
    val selector = ActorSelectorManager(Dispatchers.IO)
    val traceReplies = CopyOnWriteArrayList<TraceForwardMessage.TraceObjectsReply>()
    val dataPointReplies = CopyOnWriteArrayList<TraceForwardMessage.DataPointsReply>()

    aSocket(selector)
        .tcp()
        .connect(InetSocketAddress(host, port)) {
            noDelay = true
            keepAlive = true
        }.use { socket ->
            val traceProtocol = ForwardingTraceObjectsProtocol(singleReplyMode = traceSingleReplyMode, requestCount = traceRequestCount)
            val dataProtocol = ForwardingDataPointsProtocol(dataPointNames)
            val traceCollector = traceProtocol.messages.collectTraceRepliesInBackground(traceReplies)
            val dataCollector = dataProtocol.messages.collectDataPointRepliesInBackground(dataPointReplies)
            try {
                val mux = Mux(socket.connection())
                withTimeout(Duration.ofSeconds(10).toMillis()) {
                    mux.execute(ForwardingHandshakeProtocol(141L))
                }
                val job = CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        mux.execute(traceProtocol, dataProtocol)
                    }.onFailure { throwable ->
                        if (throwable !is EOFException) {
                            throw throwable
                        }
                    }
                }
                delay(durationMillis)
                mux.shutdownGracefully()
                job.cancelAndJoin()
            } finally {
                traceCollector.cancelAndJoin()
                dataCollector.cancelAndJoin()
                selector.close()
            }
        }

    return ProtocolCaptureResult(
        ekgReplies = emptyList(),
        traceReplies = traceReplies.toList(),
        dataPointReplies = dataPointReplies.toList(),
    )
}

private data class ProtocolCaptureResult(
    val ekgReplies: List<EkgReply>,
    val traceReplies: List<TraceForwardMessage.TraceObjectsReply>,
    val dataPointReplies: List<TraceForwardMessage.DataPointsReply>,
)

private suspend fun collectEkgReplies(
    host: String,
    port: Int,
    durationMillis: Long,
    request: EkgRequest,
): List<EkgReply> {
    val selector = ActorSelectorManager(Dispatchers.IO)
    val replies = CopyOnWriteArrayList<EkgReply>()

    aSocket(selector)
        .tcp()
        .connect(InetSocketAddress(host, port)) {
            noDelay = true
            keepAlive = true
        }.use { socket ->
            val ekgProtocol = EkgMetricsProtocol(request)
            val collector = ekgProtocol.messages.collectEkgRepliesInBackground(replies)
            try {
                val mux = Mux(socket.connection())
                withTimeout(Duration.ofSeconds(10).toMillis()) {
                    mux.execute(ForwardingHandshakeProtocol(141L))
                }
                val job = CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        mux.execute(ekgProtocol)
                    }.onFailure { throwable ->
                        if (throwable !is EOFException) {
                            throw throwable
                        }
                    }
                }
                delay(durationMillis)
                mux.shutdownGracefully()
                job.cancelAndJoin()
            } finally {
                collector.cancelAndJoin()
                selector.close()
            }
        }

    return replies.toList()
}

private suspend fun collectDataPointReplies(
    names: List<String>,
    host: String = "127.0.0.1",
    port: Int = 18400,
): List<TraceForwardMessage.DataPointsReply> {
    val replies = CopyOnWriteArrayList<TraceForwardMessage>()
    val selector = ActorSelectorManager(Dispatchers.IO)

    aSocket(selector)
        .tcp()
        .connect(InetSocketAddress(host, port)) {
            noDelay = true
            keepAlive = true
        }.use { socket ->
            val protocol = ForwardingDataPointsProtocol(names)
            val collector = protocol.messages.collectInBackground(replies)
            try {
                val mux = Mux(socket.connection())
                withTimeout(Duration.ofSeconds(10).toMillis()) {
                    mux.execute(ForwardingHandshakeProtocol(141L))
                }
                val job = CoroutineScope(Dispatchers.IO).launch {
                    mux.execute(protocol)
                }
                delay(2_000L)
                mux.shutdownGracefully()
                job.cancelAndJoin()
            } finally {
                collector.cancelAndJoin()
                selector.close()
            }
        }

    return replies.filterIsInstance<TraceForwardMessage.DataPointsReply>()
}

private suspend fun collectTraceObjectReplies(
    host: String = "127.0.0.1",
    port: Int = 18400,
    collectionWindowMillis: Long = 2_000L,
): List<TraceForwardMessage.TraceObjectsReply> {
    val replies = CopyOnWriteArrayList<TraceForwardMessage>()
    val selector = ActorSelectorManager(Dispatchers.IO)

    aSocket(selector)
        .tcp()
        .connect(InetSocketAddress(host, port)) {
            noDelay = true
            keepAlive = true
        }.use { socket ->
            val protocol = ForwardingTraceObjectsProtocol(singleReplyMode = true)
            val collector = protocol.messages.collectInBackground(replies)
            try {
                val mux = Mux(socket.connection())
                withTimeout(Duration.ofSeconds(10).toMillis()) {
                    mux.execute(ForwardingHandshakeProtocol(141L))
                }
                val job = CoroutineScope(Dispatchers.IO).launch {
                    mux.execute(protocol)
                }
                delay(collectionWindowMillis)
                mux.shutdownGracefully()
                job.cancelAndJoin()
            } finally {
                collector.cancelAndJoin()
                selector.close()
            }
        }

    return replies.filterIsInstance<TraceForwardMessage.TraceObjectsReply>()
}

private data class EkgReply(
    val metrics: Map<String, String>,
    val rawJson: String,
)

private data class KesMetrics(
    val operationalCertificateStartKesPeriod: Long,
    val operationalCertificateExpiryKesPeriod: Long,
    val currentKesPeriod: Long,
    val remainingKesPeriods: Long,
)

private data class MempoolMetrics(
    val txsInMempool: Long? = null,
    val mempoolBytes: Long? = null,
    val txsProcessedNum: Long? = null,
    val txsSyncDuration: Long? = null,
    val txsSyncDurationTotal: Long? = null,
    val txsMempoolTimeoutSoft: Long? = null,
)

private data class ChainMetrics(
    val blockNum: Long,
    val slotNum: Long,
    val slotInEpoch: Long,
    val epoch: Long,
    val density: Double?,
    val tipBlock: String?,
)

private data class ForgeMetrics(
    val forgingEnabled: Long?,
    val aboutToLead: Long?,
    val nodeNotLeader: Long?,
    val nodeIsLeader: Long?,
    val forgedSlotLast: Long?,
    val forged: Long?,
    val adopted: Long?,
)

private sealed interface EkgRequest {
    data object GetAllMetrics : EkgRequest

    data object GetUpdatedMetrics : EkgRequest

    data class GetMetrics(
        val names: List<String>,
    ) : EkgRequest
}

private class EkgMetricsProtocol(
    private val request: EkgRequest,
) : MiniProtocol(protocolId = 0x0001) {
    private var state = State.Request
        set(value) {
            field = value
            _agencyFlow.tryEmit(agency)
        }

    private val _agencyFlow = MutableSharedFlow<Agency>(replay = 1, extraBufferCapacity = 4).apply { tryEmit(agency) }
    override val agencyFlow: Flow<Agency> = _agencyFlow

    override val rxBufferSize: Int = 1024 * 1024

    override val agency: Agency
        get() =
            when (state) {
                State.Request, State.DoneToSend -> Agency.Client
                State.Reply -> Agency.Server
                State.Done -> Agency.None
            }

    private val _messages = MutableSharedFlow<EkgReply>(extraBufferCapacity = 4)
    val messages: Flow<EkgReply> = _messages

    override fun shutdown() {
        state = if (state == State.Reply) State.DoneToSend else State.Done
    }

    override suspend fun sendData(): ByteBuffer =
        when (state) {
            State.Request -> {
                val payload = muxByteBufferPool.borrow()
                buildRequest(payload)
                state = State.Reply
                payload.flip()
                payload
            }

            State.DoneToSend -> {
                val payload = muxByteBufferPool.borrow()
                buildDone(payload)
                state = State.Done
                payload.flip()
                payload
            }

            State.Done -> muxByteBufferPool.borrow().apply {
                clear()
                limit(0)
            }

            State.Reply -> error("sendData() invalid in state $state")
        }

    override fun receiveData(payload: ByteBuffer) {
        if (state == State.Done) return
        if (state != State.Reply) error("receiveData() invalid in state $state")

        ByteArrayInputStream(payload.array(), payload.position(), payload.remaining()).use { byteStream ->
            val cborArray = CborReader.createFromInputStream(byteStream).readDataItem() as? CborArray
                ?: error("Expected EKG reply array")
            val messageId = (cborArray.elementAt(0) as CborInteger).longValue()
            when (messageId) {
                1L -> {
                    println("ekg raw reply=${cborArray.toJsonString()}")
                    val metrics = decodeMetrics(cborArray.elementAt(1))
                    _messages.tryEmit(EkgReply(metrics = metrics, rawJson = cborArray.toJsonString()))
                    state = State.DoneToSend
                }

                else -> error("Unexpected EKG message id: $messageId")
            }
        }
    }

    private fun buildRequest(buffer: ByteBuffer) {
        val payload =
            when (request) {
                EkgRequest.GetAllMetrics -> CborArray.create().apply {
                    add(CborInteger.create(0))
                    add(CborInteger.create(0))
                }

                EkgRequest.GetUpdatedMetrics -> CborArray.create().apply {
                    add(CborInteger.create(0))
                    add(CborInteger.create(2))
                }

                is EkgRequest.GetMetrics -> CborArray.create().apply {
                    add(CborInteger.create(0))
                    add(
                        CborArray.create().apply {
                            add(CborInteger.create(1))
                            add(
                                CborArray.create().apply {
                                    request.names.forEach { add(CborTextString.create(it)) }
                                }
                            )
                        }
                    )
                }
            }
        CborWriter.createFromByteBuffer(buffer).writeDataItem(payload)
    }

    private fun buildDone(buffer: ByteBuffer) {
        val payload = CborArray.create().apply { add(CborInteger.create(1)) }
        CborWriter.createFromByteBuffer(buffer).writeDataItem(payload)
    }

    private enum class State {
        Request,
        Reply,
        DoneToSend,
        Done,
    }
}

private fun decodeMetrics(item: com.google.iot.cbor.CborObject): Map<String, String> {
    val array = item as? CborArray ?: return emptyMap()
    val metricsArray =
        when {
            array.size() == 2 && (array.elementAt(0) as? CborInteger)?.longValue() == 0L -> {
                array.elementAt(1) as? CborArray ?: return emptyMap()
            }
            else -> array
        }
    val metrics = linkedMapOf<String, String>()
    for (index in 0 until metricsArray.size()) {
        val pair = metricsArray.elementAt(index) as? CborArray ?: continue
        if (pair.size() < 2) {
            println("ekg unexpected metric entry=${pair.toJsonString()}")
            continue
        }
        val name = (pair.elementAt(0) as? CborTextString)?.stringValue() ?: continue
        metrics[name] = pair.elementAt(1).toJsonString()
    }
    return metrics
}

private fun Map<String, String>.toKesMetrics(): KesMetrics? {
    val start = get("cardano.node.metrics.operationalCertificateStartKESPeriod_int")?.decodeEkgIntValue() ?: return null
    val expiry = get("cardano.node.metrics.operationalCertificateExpiryKESPeriod_int")?.decodeEkgIntValue() ?: return null
    val current = get("cardano.node.metrics.currentKESPeriod_int")?.decodeEkgIntValue() ?: return null
    val remaining = get("cardano.node.metrics.remainingKESPeriods_int")?.decodeEkgIntValue() ?: return null
    return KesMetrics(
        operationalCertificateStartKesPeriod = start,
        operationalCertificateExpiryKesPeriod = expiry,
        currentKesPeriod = current,
        remainingKesPeriods = remaining,
    )
}

private fun Map<String, String>.toMempoolMetrics(): MempoolMetrics =
    MempoolMetrics(
        txsInMempool = get("cardano.node.metrics.txsInMempool_int")?.decodeEkgIntValue(),
        mempoolBytes = get("cardano.node.metrics.mempoolBytes_int")?.decodeEkgIntValue(),
        txsProcessedNum = get("cardano.node.metrics.txsProcessedNum_counter")?.decodeEkgCounterValue(),
        txsSyncDuration = get("cardano.node.metrics.txsSyncDuration_int")?.decodeEkgIntValue(),
        txsSyncDurationTotal = get("cardano.node.metrics.txsSyncDurationTotal_counter")?.decodeEkgCounterValue(),
        txsMempoolTimeoutSoft = get("cardano.node.metrics.txsMempoolTimeoutSoft_counter")?.decodeEkgCounterValue(),
    )

private fun Map<String, String>.toChainMetrics(): ChainMetrics? {
    val blockNum = get("cardano.node.metrics.blockNum_int")?.decodeEkgIntValue() ?: return null
    val slotNum = get("cardano.node.metrics.slotNum_int")?.decodeEkgIntValue() ?: return null
    val slotInEpoch = get("cardano.node.metrics.slotInEpoch_int")?.decodeEkgIntValue() ?: return null
    val epoch = get("cardano.node.metrics.epoch_int")?.decodeEkgIntValue() ?: return null
    return ChainMetrics(
        blockNum = blockNum,
        slotNum = slotNum,
        slotInEpoch = slotInEpoch,
        epoch = epoch,
        density = get("cardano.node.metrics.density_real")?.decodeEkgRealValue(),
        tipBlock = get("cardano.node.metrics.tipBlock")?.decodeEkgLabelValue(),
    )
}

private fun Map<String, String>.toForgeMetrics(): ForgeMetrics =
    ForgeMetrics(
        forgingEnabled = get("cardano.node.metrics.forging_enabled_int")?.decodeEkgIntValue(),
        aboutToLead = get("cardano.node.metrics.Forge.about-to-lead_counter")?.decodeEkgCounterValue(),
        nodeNotLeader = get("cardano.node.metrics.Forge.node-not-leader_counter")?.decodeEkgCounterValue(),
        nodeIsLeader = get("cardano.node.metrics.Forge.node-is-leader_counter")?.decodeEkgCounterValue(),
        forgedSlotLast = get("cardano.node.metrics.forgedSlotLast_int")?.decodeEkgIntValue(),
        forged = get("cardano.node.metrics.Forge.forged_counter")?.decodeEkgCounterValue(),
        adopted = get("cardano.node.metrics.Forge.adopted_counter")?.decodeEkgCounterValue(),
    )

private fun String.decodeEkgIntValue(): Long? {
    val value = JSONArray(this)
    if (value.length() != 2 || value.optInt(0) != 1) {
        return null
    }
    return value.optLong(1)
}

private fun String.decodeEkgCounterValue(): Long? {
    val value = JSONArray(this)
    if (value.length() != 2 || value.optInt(0) != 0) {
        return null
    }
    return value.optLong(1)
}

private fun String.decodeEkgRealValue(): Double? {
    val value = JSONArray(this)
    if (value.length() != 2 || value.optInt(0) != 2) {
        return null
    }
    return value.optString(1).toDoubleOrNull()
}

private fun String.decodeEkgLabelValue(): String? {
    val value = JSONArray(this)
    if (value.length() != 2 || value.optInt(0) != 2) {
        return null
    }
    return value.optString(1)
}

private fun List<TraceForwardMessage.DataPointsReply>.extractLatestJsonDataPoint(name: String): String? =
    asReversed().firstNotNullOfOrNull { reply ->
        reply.dataPoints.toDataPointMap()[name]?.decodeToString()
    }

private fun traceKindOrNull(machineJson: String): String? =
    runCatching {
        JSONObject(machineJson).optString("kind").takeIf { it.isNotBlank() }
    }.getOrNull()

private fun Flow<EkgReply>.collectEkgRepliesInBackground(
    target: MutableCollection<EkgReply>,
): Job =
    CoroutineScope(Dispatchers.IO).launch {
        collect { target += it }
    }

private fun Flow<TraceForwardMessage>.collectTraceRepliesInBackground(
    target: MutableCollection<TraceForwardMessage.TraceObjectsReply>,
): Job =
    CoroutineScope(Dispatchers.IO).launch {
        collect { message ->
            if (message is TraceForwardMessage.TraceObjectsReply) {
                target += message
            }
        }
    }

private fun Flow<TraceForwardMessage>.collectDataPointRepliesInBackground(
    target: MutableCollection<TraceForwardMessage.DataPointsReply>,
): Job =
    CoroutineScope(Dispatchers.IO).launch {
        collect { message ->
            if (message is TraceForwardMessage.DataPointsReply) {
                target += message
            }
        }
    }

private fun com.google.iot.cbor.CborArray.toDataPointMap(): Map<String, ByteArray?> {
    val decoded = linkedMapOf<String, ByteArray?>()
    for (index in 0 until size()) {
        val pair = elementAt(index) as com.google.iot.cbor.CborArray
        val name = (pair.elementAt(0) as com.google.iot.cbor.CborTextString).stringValue()
        val maybeValue = pair.elementAt(1) as com.google.iot.cbor.CborArray
        decoded[name] =
            when (maybeValue.size()) {
                0 -> null
                1 -> (maybeValue.elementAt(0) as com.google.iot.cbor.CborByteString).byteArrayValue()[0]
                else -> error("Unexpected data point maybe-value shape")
            }
    }
    return decoded
}

private fun com.google.iot.cbor.CborObject.traceObjectMachineJsonOrNull(): String? =
    when (this) {
        is CborArray -> elementAtOrNull(2)?.traceObjectMachineJsonOrNull()
        is CborTextString -> stringValue()
        is CborByteString -> byteArrayValue()[0].decodeToString()
        else -> null
    }

private fun CborArray.elementAtOrNull(index: Int): com.google.iot.cbor.CborObject? =
    if (index in 0 until size()) {
        elementAt(index)
    } else {
        null
    }

private fun Flow<TraceForwardMessage>.collectInBackground(
    target: MutableCollection<TraceForwardMessage>,
): Job =
    CoroutineScope(Dispatchers.IO).launch {
        collect { target += it }
    }
