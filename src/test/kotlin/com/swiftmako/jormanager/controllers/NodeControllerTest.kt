package com.swiftmako.jormanager.controllers

import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.Relay as RelayEntity
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.model.*
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.RelayRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.services.NodeConfigurationFiles
import com.swiftmako.jormanager.spring.config.Configuration
import com.swiftmako.jormanager.utils.Bech32
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import java.io.IOException
import java.util.Optional
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import retrofit2.Retrofit

class NodeControllerTest {
    private val config = Configuration()
    private val moshi = config.getMoshi()
    private val objectMapper = ObjectMapper()
    private val legacyConfigTemplate =
        """
        {
          "ConwayGenesisFile": "/tmp/conway.json",
          "AlonzoGenesisFile": "/tmp/alonzo.json",
          "ByronGenesisFile": "/tmp/byron.json",
          "ShelleyGenesisFile": "/tmp/shelley.json",
          "GenesisFile": "/tmp/legacy-shelley.json",
          "PeerSharing": false,
          "MaxConcurrencyDeadline": 1,
          "TraceOptions": {
            "": {
              "backends": [
                "Stdout HumanFormatColoured",
                "PrometheusSimple suffix 127.0.0.1 12798"
              ],
              "detail": "DNormal",
              "severity": "Warning"
            },
            "Forge.AdoptedBlock": {
              "severity": "Info"
            }
          },
          "UseTraceDispatcher": false,
          "TurnOnLogging": false,
          "TurnOnLogMetrics": false,
          "minSeverity": "Notice",
          "TraceOptionForwarder": {
            "connQueueSize": 1,
            "disconnQueueSize": 2,
            "maxReconnectDelay": 3
          },
          "TraceOptionMetricsPrefix": "cardano.node.metrics.",
          "TraceOptionResourceFrequency": 1000,
          "TraceBlockFetchDecisions": true,
          "defaultBackends": [],
          "defaultScribes": [],
          "setupBackends": [],
          "setupScribes": [],
          "rotation": {},
          "TracingVerbosity": "Normal",
          "EKGBackend": "127.0.0.1:12788",
          "PrometheusSimple": "127.0.0.1:12789",
          "hasPrometheus": 12789
        }
        """.trimIndent()

    private val testHost =
        Host(
            id = 1L,
            type = "local",
            cardanoCliPath = "/home/westbam/.local/bin/cardano-cli",
            cardanoNodePath = "/home/westbam/.local/bin/cardano-node",
            hostname = "brainy",
            sshUser = "westbam",
            nodeHomePath = "/home/westbam/haskell",
            jcliPath = "/home/westbam/.cargo/bin/jcli",
        )

    private fun createRequest(
        type: String,
        name: String = "tickr",
        processorThreads: Int = 8,
    ) = CreateNodeRequest(
        requestId = java.util.UUID.randomUUID().toString(),
        color = "#0000FF",
        hostId = 1L,
        name = name,
        isDefault = false,
        type = type,
        processorThreads = processorThreads,
        listen = "127.0.0.1",
        port = 6001,
        prometheusListen = "127.0.0.1",
        enableTracingListener = true,
        tracingListen = "0.0.0.0",
        genesisByronFileId = 1L,
        genesisShelleyFileId = 2L,
        genesisAlonzoFileId = 3L,
        genesisConwayFileId = 4L,
        generateColdKeys = true,
        coldSKey = null,
        coldVKey = null,
        coldCounter = null,
        generateVRFKeys = true,
        vrfSKey = null,
        vrfVKey = null,
        generateKESKeys = true,
        kesSKey = null,
        kesVKey = null,
        registrationFeesAccount = 1L,
        ownerStakingAccount = 2L,
        rewardsStakingAccount = 3L,
        poolPledge = 250000000000L.toBigInteger(),
        poolCost = 340000000L.toBigInteger(),
        poolMargin = "0.05",
        relays = emptyList(),
        metadata = null,
        sudoPassword = "asdfasdf",
        spendingPassword = "asdfasdf",
        parentId = null,
    )

    @Test
    fun governanceVoteRequestDefaultsOmittedRationaleToNull() {
        val request =
            jacksonObjectMapper().readValue(
                """{"govActionId":"gov_action1test","votes":[],"feesAccountId":5,"spendingPassword":"secret"}""",
                GovernanceVoteRequest::class.java,
            )

        assertThat(request.rationale).isNull()
    }

    @Test
    fun allocatePrometheusPortReturnsFirstFreePort() {
        val target = createTarget()

        val promPort = target.allocatePrometheusPort(1L, mockk(relaxed = true)) { false }

        assertThat(promPort).isEqualTo(12789)
    }

    @Test
    fun allocatePrometheusPortScansPastUsedPorts() {
        val target = createTarget()

        val promPort =
            target.allocatePrometheusPort(1L, mockk(relaxed = true)) { port ->
                port == 12789 || port == 12790
            }

        assertThat(promPort).isEqualTo(12791)
    }

    @Test
    fun allocatePrometheusPortFeedsTracingPortSequence() {
        val target = createTarget()

        val promPort = target.allocatePrometheusPort(1L, mockk(relaxed = true)) { false }
        val tracingPort = target.allocateTracingPort(promPort) { false }

        assertThat(promPort).isEqualTo(12789)
        assertThat(tracingPort).isEqualTo(12790)
    }

    private fun createTarget(
        httpClient: OkHttpClient = OkHttpClient.Builder().build(),
        nodeRepository: NodeRepository = mockk(relaxed = true),
        relayRepository: RelayRepository = mockk(relaxed = true),
        hostRepository: HostRepository = mockk(relaxed = true),
        fileRepository: FileRepository = mockk(relaxed = true),
        walletUtils: WalletUtils =
            mockk(relaxed = true) {
                every { isValidSpendingPassword(any()) } returns true
            },
        webSocketTemplate: SimpMessagingTemplate = mockk(relaxed = true),
        cardanoRepository: CardanoRepository = mockk(relaxed = true),
        nodeConfigurationFiles: NodeConfigurationFiles = mockk(relaxed = true),
    ) = NodeController(
        nodeRepository = nodeRepository,
        relayRepository = relayRepository,
        hostRepository = hostRepository,
        fileRepository = fileRepository,
        walletUtils = walletUtils,
        webSocketTemplate = webSocketTemplate,
        nodesChannel = mockk(relaxed = true),
        retrofit = config.getRetrofit(httpClient, moshi),
        okHttpClient = httpClient,
        extendedMetadataAdapter = config.getExtendedMetadataAdapter(moshi),
        shelleyGenesisAdapter = config.getShelleyGenesisAdapter(moshi),
        byronGenesisAdapter = config.getByronGenesisAdapter(moshi),
        metadataAdapter = config.getMetadataAdapter(moshi),
        keyFileJsonAdapter = config.getKeyAdapter(moshi),
        cardanoRepository = cardanoRepository,
        nodeConfigurationFiles = nodeConfigurationFiles,
    )

    private class RationaleHttpFixture(
        var key: String = "rationale.json",
        var uploadCode: Int = 204,
        var getCode: Int = 200,
        var getBytes: (ByteArray) -> ByteArray = { it },
    ) : Interceptor {
        var requestCount = 0
        var retrievalCount = 0
        var uploadedBytes: ByteArray? = null
        var uploadCount = 0
        val documents = mutableListOf<ByteArray>()

        override fun intercept(chain: Interceptor.Chain): Response {
            requestCount++
            val request = chain.request()
            val body =
                when (request.url.host) {
                    "1v27dl8wn5.execute-api.us-west-2.amazonaws.com" ->
                        """
                        {
                          "fields": {
                            "AWSAccessKeyId": "test",
                            "key": "$key",
                            "policy": "test",
                            "signature": "test",
                            "x-amz-security-token": "test"
                          },
                          "url": "https://s3.us-west-2.amazonaws.com"
                        }
                        """.trimIndent().toResponseBody("application/json".toMediaType())

                    "s3.us-west-2.amazonaws.com" -> {
                        val multipart = request.body as MultipartBody
                        val file =
                            multipart.parts.first {
                                it.headers?.get("Content-Disposition")?.contains("""name="file"""") == true
                            }
                        uploadCount++
                        uploadedBytes =
                            Buffer().let { buffer ->
                                file.body.writeTo(buffer)
                                buffer.readByteArray()
                            }
                        documents += checkNotNull(uploadedBytes)
                        return response(request, uploadCode)
                    }

                    "cardanostakehouse.com" -> {
                        retrievalCount++
                        return response(
                            request,
                            getCode,
                            getBytes(checkNotNull(uploadedBytes)).toResponseBody("application/json".toMediaType()),
                        )
                    }

                    else -> error("Unexpected HTTP request: ${request.method} ${request.url}")
                }
            return response(request, 200, body)
        }

        private fun response(
            request: okhttp3.Request,
            code: Int,
            body: okhttp3.ResponseBody = ByteArray(0).toResponseBody(),
        ) = Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(code.toString())
            .body(body)
            .build()

        fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()
    }

    @Test
    fun poolMetadataPublishesContentAndChecksExactBytesWithoutLeakingKeys() {
        val fixture = RationaleHttpFixture(key = "pool.json")
        val node = mockk<Node> {
            every { hostId } returns 1L
            every { id } returns 2L
            every { name } returns "pool"
            every { type } returns NodeController.NODE_TYPE_CORE
            every { poolId } returns "pool1fixture"
            every { isDraft } returns false
            every { isDeleted } returns false
            every { itnPrivateKeyId } returns -1L
            every { itnPublicKeyId } returns -1L
        }
        val hosts = mockk<HostRepository> { every { findById(1L) } returns Optional.of(testHost) }
        val eras = mockk<CardanoRepository> {
            every { networkKey(node) } returns "test"
            every { evidence(node) } returns CardanoRepository.EraEvidence("test", "conway", "current", "host:1/node:2", generation = 1)
        }
        val files = mockk<FileRepository> { every { save(any<File>()) } answers { firstArg<File>().copy(id = 99) } }
        val wallet = mockk<WalletUtils> {
            every { isValidSpendingPassword("password") } returns true
            every { encryptSKeyContent("private-secret", "password") } returns "encrypted-secret"
        }
        val metadata = com.swiftmako.jormanager.model.Metadata("POOL", "Pool", "Description", "https://example.org",
            Extended(Itn("public-key", "private-secret"), null, "admin", "verify"))
        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().commandWriteFile(any(), any()) } returns ""
            every { anyConstructed<HostConnection>().commandWriteSecret(any(), any()) } returns Unit
            every { anyConstructed<HostConnection>().commandArgs(any()) } answers {
                val args = firstArg<List<String>>()
                when {
                    "metadata-hash" in args -> com.swiftmako.jormanager.utils.Blake2b.hash256(checkNotNull(fixture.uploadedBytes))
                        .joinToString("") { "%02x".format(it) }
                    "sign" in args -> "witness"
                    else -> ""
                }
            }
            val result = createTarget(fixture.client(), hostRepository = hosts, fileRepository = files,
                walletUtils = wallet, cardanoRepository = eras).publishPoolMetadata(node, metadata, "password")
            assertThat(fixture.uploadCount).isEqualTo(2)
            val extended = jacksonObjectMapper().readTree(fixture.documents.first())
            assertThat(extended["itn"]["witness"].asString()).isEqualTo("witness")
            assertThat(extended["itn"]["owner"].asString()).isEqualTo("public-key")
            assertThat(fixture.documents.joinToString { it.toString(Charsets.UTF_8) }).doesNotContain("private-secret")
            assertThat(result.itnPrivateKeyId).isEqualTo(99)
            verify { files.save(match { it.content == "encrypted-secret" }) }
            fixture.getBytes = { it + 0 }
            assertThrows<IllegalStateException> {
                createTarget(fixture.client(), hostRepository = hosts, fileRepository = files,
                    walletUtils = wallet, cardanoRepository = eras).publishPoolMetadata(node, metadata, "password")
            }
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test
    fun publishPoolMetadataResolvesParentOrDefaultObserverAndObserverHostForBulkChildAndDraftPools() {
        val observerHost =
            Host(
                id = 2L,
                type = "local",
                cardanoCliPath = "/opt/observer/cardano-cli",
                cardanoNodePath = "/opt/observer/cardano-node",
                hostname = "observer-host",
                sshUser = "operator",
                nodeHomePath = "/srv/observer",
                jcliPath = null,
            )
        val baseNode =
            Node(
                id = 10L,
                hostId = 2L,
                color = "#000000",
                type = NodeController.NODE_TYPE_CORE,
                processorThreads = 2,
                name = "parent-core",
                listen = "127.0.0.1",
                port = 3001,
                promPort = 12798,
                genesisByronFileId = 1L,
                genesisShelleyFileId = 2L,
                genesisAlonzoFileId = 3L,
                genesisConwayFileId = 4L,
                configFileId = 5L,
                isDefault = false,
            )
        val defaultObserver = baseNode.copy(id = 1L, name = "default-relay", type = NodeController.NODE_TYPE_RELAY, isDefault = true)
        val childWithParent =
            baseNode.copy(
                id = 20L,
                hostId = 99L,
                name = "bulk-child",
                type = NodeController.NODE_TYPE_POOL,
                parentId = 10L,
                poolId = "pool1child",
                isDraft = false,
            )
        val childWithoutParent = childWithParent.copy(id = 21L, parentId = null, poolId = "pool1orphan")
        val draftPool =
            baseNode.copy(
                id = 22L,
                hostId = 99L,
                name = "draft-pool",
                type = NodeController.NODE_TYPE_CORE,
                poolId = "pool1draft",
                isDraft = true,
            )
        val nodes =
            mockk<NodeRepository> {
                every { findById(10L) } returns Optional.of(baseNode)
                every { findDefault() } returns defaultObserver
            }
        val hosts =
            mockk<HostRepository> {
                every { findById(2L) } returns Optional.of(observerHost)
            }
        val eras =
            mockk<CardanoRepository> {
                every { networkKey(any()) } returns "dijkstra-net"
                every { evidence(baseNode) } returns
                    CardanoRepository.EraEvidence("dijkstra-net", "dijkstra", "current", "host:2/node:10", generation = 1)
                every { evidence(defaultObserver) } returns
                    CardanoRepository.EraEvidence("dijkstra-net", "dijkstra", "current", "host:2/node:1", generation = 1)
            }
        val wallet =
            mockk<WalletUtils> {
                every { isValidSpendingPassword("password") } returns true
            }
        val metadata = com.swiftmako.jormanager.model.Metadata("POOL", "Pool", "Description", "https://example.org", null)

        mockkConstructor(HostConnection::class)
        try {
            val cliInvocations = mutableListOf<List<String>>()
            val fixture = RationaleHttpFixture(key = "pool.json")
            every { anyConstructed<HostConnection>().commandWriteFile(any(), any()) } returns ""
            every { anyConstructed<HostConnection>().commandArgs(any()) } answers {
                val args = firstArg<List<String>>()
                if ("metadata-hash" in args) {
                    cliInvocations += args
                    com.swiftmako.jormanager.utils.Blake2b.hash256(checkNotNull(fixture.uploadedBytes))
                        .joinToString("") { "%02x".format(it) }
                } else {
                    ""
                }
            }

            val target =
                createTarget(
                    fixture.client(),
                    nodeRepository = nodes,
                    hostRepository = hosts,
                    walletUtils = wallet,
                    cardanoRepository = eras,
                )

            val childResult = target.publishPoolMetadata(childWithParent, metadata, "password")
            val fallbackResult = target.publishPoolMetadata(childWithoutParent, metadata, "password")
            val draftResult = target.publishPoolMetadata(draftPool, metadata, "password")

            assertThat(childResult.url).isEqualTo("https://cardanostakehouse.com/pool.json")
            assertThat(fallbackResult.url).isEqualTo("https://cardanostakehouse.com/pool.json")
            assertThat(draftResult.url).isEqualTo("https://cardanostakehouse.com/pool.json")
            assertThat(cliInvocations).hasSize(3)
            cliInvocations.forEach { args ->
                assertThat(args.take(4)).containsExactly("/opt/observer/cardano-cli", "dijkstra", "stake-pool", "metadata-hash").inOrder()
            }
            verify(exactly = 1) { eras.evidence(baseNode) }
            verify(exactly = 2) { eras.evidence(defaultObserver) }
            verify(exactly = 0) { eras.evidence(childWithParent) }
            verify(exactly = 0) { eras.evidence(childWithoutParent) }
            verify(exactly = 0) { eras.evidence(draftPool) }
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test
    fun blankGovernanceRationalesDoNotUseHosting() {
        val fixture = RationaleHttpFixture()
        val target = createTarget(fixture.client())

        assertThat(target.uploadGovernanceRationale(null)).isNull()
        assertThat(target.uploadGovernanceRationale("")).isNull()
        assertThat(target.uploadGovernanceRationale(" \n\t")).isNull()
        assertThat(fixture.requestCount).isEqualTo(0)
    }

    @Test
    fun governanceRationalePublishesExactCommentAndHashesDocumentBytes() {
        val fixture = RationaleHttpFixture()
        val target = createTarget(fixture.client())
        val rationale = "First line\n\"café\" ${'$'}(touch /tmp/should-not-exist)"

        val anchor = target.uploadGovernanceRationale(rationale)

        val bytes = checkNotNull(fixture.uploadedBytes)
        val json = jacksonObjectMapper().readTree(bytes)
        assertThat(json["body"]["comment"].stringValue()).isEqualTo(rationale)
        assertThat(json["hashAlgorithm"].stringValue()).isEqualTo("blake2b-256")
        assertThat(json["authors"].isEmpty).isTrue()
        val digest = Blake2bDigest(256)
        digest.update(bytes, 0, bytes.size)
        val expectedHash = ByteArray(32)
        digest.doFinal(expectedHash, 0)
        assertThat(anchor?.second).isEqualTo(expectedHash.joinToString("") { "%02x".format(it) })
        assertThat(fixture.requestCount).isEqualTo(3)
    }

    @Test
    fun governanceRationaleRejectsUploadAndRetrievalFailures() {
        val fixtures =
            listOf(
                RationaleHttpFixture(uploadCode = 500),
                RationaleHttpFixture(getCode = 500),
                RationaleHttpFixture(getCode = 302),
                RationaleHttpFixture(getBytes = { bytes -> bytes.copyOf().apply { this[lastIndex] = (last() + 1).toByte() } }),
                RationaleHttpFixture(getBytes = { bytes -> bytes.copyOf(bytes.size - 1) }),
                RationaleHttpFixture(getBytes = { bytes -> bytes + 0 }),
            )

        fixtures.forEach { fixture ->
            assertThrows<IOException> {
                createTarget(fixture.client()).uploadGovernanceRationale("Publish this")
            }
        }
    }

    @Test
    fun governanceRationaleEnforcesAnchorUrlBoundaryBeforeRetrieval() {
        val prefix = "https://cardanostakehouse.com/"
        val accepted = RationaleHttpFixture(key = "a".repeat(128 - prefix.length))
        val tooLong = RationaleHttpFixture(key = "a".repeat(129 - prefix.length))
        val unsafe = RationaleHttpFixture(key = "unsafe?query")

        assertThat(createTarget(accepted.client()).uploadGovernanceRationale("Accepted")).isNotNull()
        assertThat(accepted.retrievalCount).isEqualTo(1)
        listOf(tooLong, unsafe).forEach { fixture ->
            val exception =
                assertThrows<IOException> {
                    createTarget(fixture.client()).uploadGovernanceRationale("Rejected")
                }
            assertThat(exception).hasMessageThat().isEqualTo("Invalid rationale anchor URL returned by metadata storage!")
            assertThat(fixture.retrievalCount).isEqualTo(0)
        }
    }


    @Test
    fun allocateTracingPortReturnsFirstFreePort() {
        val target = createTarget()

        val tracingPort = target.allocateTracingPort(12789) { false }

        assertThat(tracingPort).isEqualTo(12790)
    }

    @Test
    fun allocateTracingPortScansPastCollisions() {
        val target = createTarget()

        val tracingPort =
            target.allocateTracingPort(12789) { port ->
                port == 12790 || port == 12791
            }

        assertThat(tracingPort).isEqualTo(12792)
    }

    @Test
    fun renderTracingListenerArgumentBuildsNetworkAcceptFlag() {
        val target = createTarget()

        val listenerArgument = target.renderTracingListenerArgument("0.0.0.0", 12790)

        assertThat(listenerArgument).isEqualTo("--tracer-socket-network-accept ${'$'}{TRACING_HOST}:${'$'}{TRACING_PORT}")
    }

    @Test
    fun renderTracingListenerArgumentRequiresTracingPort() {
        val target = createTarget()

        val listenerArgument = target.renderTracingListenerArgument("0.0.0.0", null)

        assertThat(listenerArgument).isNull()
    }

    @Test
    fun createNodeRejectsDisabledTracingListener() {
        val target = createTarget()

        val exception =
            assertThrows<RuntimeException> {
                target.createNode(createRequest(NodeController.NODE_TYPE_RELAY).copy(enableTracingListener = false))
            }

        assertThat(exception.cause).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(exception.cause).hasMessageThat().isEqualTo("Tracing listener must be enabled!")
    }

    @Test
    fun renderManagedConfigCreatesMissingTraceRoot() {
        val rendered = createTarget().renderManagedConfig("{}", "relay", "4", true, "127.0.0.1", 12789)
        val root = objectMapper.readTree(rendered)
        assertThat(root.path("TraceOptions").path("").path("severity").asString()).isEqualTo("Notice")
        assertThat(root.path("ByronGenesisFile").asString()).isEqualTo("byron-genesis.json")
        assertThat(root.has("GenesisFile")).isFalse()
    }

    @Test
    fun firstRelayInstallsSelectedFilesWithoutObserver() {
        val request = createRequest(NodeController.NODE_TYPE_RELAY).copy(
            isDefault = true, configFileId = 5, topologyFileId = 6,
            genesisDijkstraFileId = 7, peerSnapshotFileId = 8,
        )
        val assets = mapOf(
            "byron-genesis.json" to "byron", "shelley-genesis.json" to "shelley",
            "alonzo-genesis.json" to "alonzo", "conway-genesis.json" to "conway",
            "dijkstra-genesis.json" to "dijkstra", "config.json" to legacyConfigTemplate,
            "topology.json" to """{"peerSnapshotFile":"source.json"}""",
            "peer-snapshot.json" to "peer",
        ).mapValues { it.value.toByteArray() }
        val topology = """{"peerSnapshotFile":"peer-snapshot.json"}"""
        val selected = mockk<NodeConfigurationFiles>()
        every { selected.resolve(request) } returns assets
        every { selected.renderTopology(assets) } returns topology
        val nodes = mockk<NodeRepository>(relaxed = true)
        every { nodes.findDefault() } returns null
        every { nodes.save(any<Node>()) } answers { firstArg<Node>().copy(id = 10) }
        val hosts = mockk<HostRepository>()
        every { hosts.findById(1L) } returns Optional.of(testHost)
        val written = mutableMapOf<String, String>()
        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().sudoCommand(any<String>(), any()) } returns ""
            every { anyConstructed<HostConnection>().commandFileExists(any()) } returns false
            every { anyConstructed<HostConnection>().command(any<String>()) } returns ""
            every { anyConstructed<HostConnection>().commandWriteFile(any(), any()) } answers {
                written[firstArg()] = secondArg()
                ""
            }
            every { anyConstructed<HostConnection>().commandReadFile(any()) } answers { written.getValue(firstArg()) }
            every { anyConstructed<HostConnection>().sudoCommandWriteFile(any(), any(), any()) } returns ""
            val target = createTarget(nodeRepository = nodes, hostRepository = hosts, nodeConfigurationFiles = selected)

            target.createNode(request)

            val folder = "${testHost.nodeHomePath}/${request.name}"
            assertThat(written["$folder/dijkstra-genesis.json"]).isEqualTo("dijkstra")
            assertThat(written["$folder/peer-snapshot.json"]).isEqualTo("peer")
            assertThat(written["$folder/topology.json"]).isEqualTo(topology)
            assertThat(written["$folder/config.json"]).contains("byron-genesis.json")
            verify { nodes.save(match { it.isDefault && it.configFileId == 5L && it.topologyFileId == 6L &&
                it.genesisDijkstraFileId == 7L && it.peerSnapshotFileId == 8L }) }
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test
    fun invalidSelectedFilesFailBeforeHostWrites() {
        val selected = mockk<NodeConfigurationFiles>()
        every { selected.resolve(any()) } throws IllegalArgumentException("Missing selected topology")
        val hosts = mockk<HostRepository>(relaxed = true)
        val target = createTarget(hostRepository = hosts, nodeConfigurationFiles = selected)

        val error = assertThrows<RuntimeException> { target.createNode(createRequest(NodeController.NODE_TYPE_RELAY)) }

        assertThat(error.cause).hasMessageThat().isEqualTo("Missing selected topology")
        verify(exactly = 0) { hosts.findById(any()) }
    }

    @Test
    fun existingDirectoryOrServiceFailsBeforeWrites() {
        val request = createRequest(NodeController.NODE_TYPE_RELAY)
        val selected = mockk<NodeConfigurationFiles>()
        every { selected.resolve(request) } returns emptyMap()
        every { selected.renderTopology(any()) } returns "{}"
        val hosts = mockk<HostRepository>()
        every { hosts.findById(1L) } returns Optional.of(testHost)
        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().sudoCommand(any<String>(), any()) } returns ""
            for (existing in listOf("${testHost.nodeHomePath}/${request.name}", "/etc/systemd/system/${request.name}-node.service")) {
                every { anyConstructed<HostConnection>().commandFileExists(any()) } answers { firstArg<String>() == existing }
                assertThrows<RuntimeException> {
                    createTarget(hostRepository = hosts, nodeConfigurationFiles = selected).createNode(request)
                }
            }
            verify(exactly = 0) { anyConstructed<HostConnection>().commandWriteFile(any(), any()) }
            verify(exactly = 0) { anyConstructed<HostConnection>().sudoCommandWriteFile(any(), any(), any()) }
            verify(exactly = 0) { anyConstructed<HostConnection>().command(any<String>()) }
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test
    fun renderManualStartupScriptAddsTracingListenerForCoreNodes() {
        val target = createTarget()

        val script =
            target.renderManualStartupScript(
                request = createRequest(NodeController.NODE_TYPE_CORE),
                startupNodeType = NodeController.NODE_TYPE_CORE,
                host = testHost,
                tracingHost = "0.0.0.0",
                tracingPort = 12790,
            )

        assertThat(script).contains("--tracer-socket-network-accept ${'$'}{TRACING_HOST}:${'$'}{TRACING_PORT}")
        assertThat(script).contains("--shelley-operational-certificate ${'$'}{SHELLEY_OPCERT}")
        assertThat(script).doesNotContain("--bulk-credentials-file")
    }

    @Test
    fun renderManualStartupScriptUsesBulkCredentialsForPoolRewrite() {
        val target = createTarget()

        val withTracing =
            target.renderManualStartupScript(
                request = createRequest(NodeController.NODE_TYPE_CORE, name = "core1"),
                startupNodeType = NodeController.NODE_TYPE_POOL,
                host = testHost,
                tracingHost = "0.0.0.0",
                tracingPort = 12790,
            )
        val withoutTracing =
            target.renderManualStartupScript(
                request = createRequest(NodeController.NODE_TYPE_CORE, name = "core1"),
                startupNodeType = NodeController.NODE_TYPE_POOL,
                host = testHost,
                tracingHost = "0.0.0.0",
                tracingPort = null,
            )

        assertThat(withTracing).contains("--bulk-credentials-file ${'$'}{BULK_CREDENTIALS}")
        assertThat(withTracing).contains("--tracer-socket-network-accept ${'$'}{TRACING_HOST}:${'$'}{TRACING_PORT}")
        assertThat(withTracing).doesNotContain("--shelley-kes-key")
        assertThat(withTracing).doesNotContain("--shelley-vrf-key")
        assertThat(withTracing).doesNotContain("--shelley-operational-certificate")

        assertThat(withoutTracing).contains("--bulk-credentials-file ${'$'}{BULK_CREDENTIALS}")
        assertThat(withoutTracing).doesNotContain("--tracer-socket-network-accept")
        assertThat(withoutTracing).doesNotContain("--shelley-operational-certificate")
    }

    @Test
    fun renderSystemdContentAddsTracingListenerForRelayNodes() {
        val target = createTarget()

        val systemd =
            target.renderSystemdContent(
                startupNodeType = NodeController.NODE_TYPE_RELAY,
                host = testHost,
                name = "relay1",
                processorThreads = 4,
                tracingHost = "0.0.0.0",
                tracingPort = 12790,
            )

        assertThat(systemd).contains("--tracer-socket-network-accept ${'$'}{TRACING_HOST}:${'$'}{TRACING_PORT}")
        assertThat(systemd).contains("--config ${'$'}{CONFIG}")
    }

    @Test
    fun renderSystemdContentAddsTracingListenerForPoolRewrite() {
        val target = createTarget()

        val systemd =
            target.renderSystemdContent(
                startupNodeType = NodeController.NODE_TYPE_POOL,
                host = testHost,
                name = "core1",
                processorThreads = 6,
                tracingHost = "0.0.0.0",
                tracingPort = 12790,
            )

        assertThat(systemd).contains("--tracer-socket-network-accept ${'$'}{TRACING_HOST}:${'$'}{TRACING_PORT}")
        assertThat(systemd).contains("--bulk-credentials-file ${'$'}{BULK_CREDENTIALS}")
        assertThat(systemd).doesNotContain("--shelley-operational-certificate ${'$'}{SHELLEY_OPCERT}")
    }

    @Test
    fun renderSystemdContentSkipsTracingListenerWhenPoolRewriteCorePortMissing() {
        val target = createTarget()

        val systemd =
            target.renderSystemdContent(
                startupNodeType = NodeController.NODE_TYPE_POOL,
                host = testHost,
                name = "core1",
                processorThreads = 6,
                tracingHost = "0.0.0.0",
                tracingPort = null,
            )

        assertThat(systemd).doesNotContain("--tracer-socket-network-accept")
        assertThat(systemd).contains("--bulk-credentials-file ${'$'}{BULK_CREDENTIALS}")
    }

    @Test
    fun renderEnvContentIncludesTracingValuesForCoreNodes() {
        val target = createTarget()

        val env =
            target.renderEnvContent(
                nodeType = NodeController.NODE_TYPE_CORE,
                nodeName = "core1",
                nodeFolder = "/srv/cardano/core1",
                listen = "0.0.0.0",
                port = 3001,
                tracingHost = "0.0.0.0",
                tracingPort = 12790,
            )

        assertThat(env).contains("TRACING_HOST=0.0.0.0")
        assertThat(env).contains("TRACING_PORT=12790")
        assertThat(env).contains("SHELLEY_OPCERT=/srv/cardano/core1/core1.node.opcert")
    }

    @Test
    fun renderEnvContentPreservesParentCoreTracingValuesForPoolRewrite() {
        val target = createTarget()

        val env =
            target.renderEnvContent(
                nodeType = NodeController.NODE_TYPE_POOL,
                nodeName = "core1",
                nodeFolder = "/srv/cardano/core1",
                listen = "127.0.0.1",
                port = 6001,
                tracingHost = "10.0.0.5",
                tracingPort = 12795,
            )

        assertThat(env).contains("TRACING_HOST=10.0.0.5")
        assertThat(env).contains("TRACING_PORT=12795")
        assertThat(env).contains("BULK_CREDENTIALS=/srv/cardano/core1/credentials.json")
    }

    @Test
    fun renderManagedConfigBuildsCoreDispatcherTracingShape() {
        val target = createTarget()

        val rendered =
            target.renderManagedConfig(
                templateContent = legacyConfigTemplate,
                nodeType = NodeController.NODE_TYPE_CORE,
                maxConcurrencyDeadline = "2",
                peerSharing = false,
                prometheusListen = "127.0.0.1",
                promPort = 12800,
            )

        val root = objectMapper.readTree(rendered)

        assertThat(root.get("UseTraceDispatcher").asBoolean()).isTrue()
        assertThat(root.get("TurnOnLogging").asBoolean()).isTrue()
        assertThat(root.get("TurnOnLogMetrics").asBoolean()).isTrue()
        assertThat(root.get("minSeverity").asString()).isEqualTo("Critical")
        assertThat(root.get("PeerSharing").asBoolean()).isFalse()
        assertThat(root.get("MaxConcurrencyDeadline").asInt()).isEqualTo(2)
        assertThat(root.get("ConwayGenesisFile").asString()).isEqualTo("conway-genesis.json")
        assertThat(root.get("AlonzoGenesisFile").asString()).isEqualTo("alonzo-genesis.json")
        assertThat(root.get("ByronGenesisFile").asString()).isEqualTo("byron-genesis.json")
        assertThat(root.get("ShelleyGenesisFile").asString()).isEqualTo("shelley-genesis.json")
        assertThat(root.has("GenesisFile")).isFalse()

        val rootTraceOptions = root.get("TraceOptions").get("")
        assertThat(rootTraceOptions.get("severity").asString()).isEqualTo("Warning")
        assertThat(rootTraceOptions.get("detail")).isNull()
        assertThat(rootTraceOptions.get("backends").values().map { it.asString() })
            .containsExactly("Stdout MachineFormat", "Forwarder", "PrometheusSimple suffix 127.0.0.1 12800")
            .inOrder()

        val forwarder = root.get("TraceOptionForwarder")
        assertThat(forwarder).isNotNull()
        assertThat(forwarder.get("connQueueSize").asInt()).isEqualTo(64)
        assertThat(forwarder.get("disconnQueueSize").asInt()).isEqualTo(128)
        assertThat(forwarder.get("maxReconnectDelay").asInt()).isEqualTo(30)

        assertThat(
            root
                .get("TraceOptions")
                .get("Forge.Loop.AdoptedBlock")
                .get("severity").asString()
        ).isEqualTo("Info")
        assertThat(
            root
                .get("TraceOptions")
                .get("ChainDB.AddBlockEvent.AddedToCurrentChain")
                .get("severity").asString()
        ).isEqualTo("Silence")
        assertThat(
            root
                .get("TraceOptions")
                .get("Resources")
                .get("severity").asString()
        ).isEqualTo("Silence")
        assertThat(root.propertyNames().toList())
            .containsAtLeast(
                "ConwayGenesisFile",
                "AlonzoGenesisFile",
                "ByronGenesisFile",
                "ShelleyGenesisFile",
                "PeerSharing",
                "MaxConcurrencyDeadline",
                "TraceOptions",
                "UseTraceDispatcher",
                "TurnOnLogging",
                "TurnOnLogMetrics",
                "minSeverity",
                "TraceOptionForwarder",
            )
        assertThat(root.get("TraceBlockFetchDecisions")).isNull()
        assertThat(root.get("defaultScribes")).isNull()
        assertThat(root.get("rotation")).isNull()
        assertThat(root.get("EKGBackend")).isNull()
        assertThat(root.get("PrometheusSimple")).isNull()
        assertThat(root.get("hasEkg")).isNull()
        assertThat(root.get("hasEKG")).isNull()
        assertThat(root.get("hasPrometheus")).isNull()
        assertThat(root.get("TraceOptionMetricsPrefix").asString()).isEqualTo("cardano.node.metrics.")
        assertThat(root.get("TraceOptionResourceFrequency").asInt()).isEqualTo(1000)
    }

    @Test
    fun renderManagedConfigBuildsRelayDispatcherTracingShape() {
        val target = createTarget()

        val rendered =
            target.renderManagedConfig(
                templateContent = legacyConfigTemplate,
                nodeType = NodeController.NODE_TYPE_RELAY,
                maxConcurrencyDeadline = "4",
                peerSharing = true,
                prometheusListen = "0.0.0.0",
                promPort = 12900,
            )

        val root = objectMapper.readTree(rendered)

        assertThat(root.get("UseTraceDispatcher").asBoolean()).isTrue()
        assertThat(root.get("TurnOnLogging").asBoolean()).isTrue()
        assertThat(root.get("TurnOnLogMetrics").asBoolean()).isTrue()
        assertThat(root.get("minSeverity").asString()).isEqualTo("Critical")
        assertThat(root.get("PeerSharing").asBoolean()).isTrue()
        assertThat(root.get("MaxConcurrencyDeadline").asInt()).isEqualTo(4)
        assertThat(root.has("GenesisFile")).isFalse()
        assertThat(
            root
                .get("TraceOptions")
                .get("")
                .get("backends")
                .values()
                .map { it.asString() }
        ).containsExactly("Stdout MachineFormat", "Forwarder", "PrometheusSimple suffix 0.0.0.0 12900")
            .inOrder()
        assertThat(
            root
                .get("TraceOptions")
                .get("Forge.Loop.AdoptedBlock")
                .get("severity").asString()
        ).isEqualTo("Info")
        assertThat(
            root
                .get("TraceOptions")
                .get("ChainDB.AddBlockEvent.AddedToCurrentChain")
                .get("severity").asString()
        ).isEqualTo("Silence")
        val forwarder = root.get("TraceOptionForwarder")
        assertThat(forwarder).isNotNull()
        assertThat(forwarder.get("connQueueSize").asInt()).isEqualTo(64)
        assertThat(forwarder.get("disconnQueueSize").asInt()).isEqualTo(128)
        assertThat(forwarder.get("maxReconnectDelay").asInt()).isEqualTo(30)
        assertThat(root.propertyNames().toList())
            .containsAtLeast(
                "ConwayGenesisFile",
                "AlonzoGenesisFile",
                "ByronGenesisFile",
                "ShelleyGenesisFile",
                "PeerSharing",
                "MaxConcurrencyDeadline",
                "TraceOptions",
                "UseTraceDispatcher",
                "TurnOnLogging",
                "TurnOnLogMetrics",
                "minSeverity",
                "TraceOptionForwarder",
            )
        assertThat(root.get("TraceBlockFetchDecisions")).isNull()
        assertThat(root.get("defaultBackends")).isNull()
        assertThat(root.get("defaultScribes")).isNull()
        assertThat(root.get("EKGBackend")).isNull()
        assertThat(root.get("PrometheusSimple")).isNull()
        assertThat(root.get("TraceOptionMetricsPrefix").asString()).isEqualTo("cardano.node.metrics.")
        assertThat(root.get("TraceOptionResourceFrequency").asInt()).isEqualTo(1000)
    }

    @Test
    fun updateColorRequestAcceptsBothIdAndNodeIdAndNodeSerializesRelays() {
        val mapper = jacksonObjectMapper()
        val fromId = mapper.readValue("""{"id":5,"color":"#112233"}""", UpdateColorRequest::class.java)
        val fromNodeId = mapper.readValue("""{"nodeId":5,"color":"#112233"}""", UpdateColorRequest::class.java)
        assertThat(fromId).isEqualTo(UpdateColorRequest(id = 5L, color = "#112233"))
        assertThat(fromNodeId).isEqualTo(UpdateColorRequest(id = 5L, color = "#112233"))

        val node =
            Node(
                id = 5L,
                hostId = 1L,
                color = "#112233",
                type = "core",
                processorThreads = 2,
                name = "core-1",
                listen = "0.0.0.0",
                port = 3001,
                promPort = 12798,
                genesisByronFileId = 1L,
                genesisShelleyFileId = 2L,
                genesisAlonzoFileId = 3L,
                genesisConwayFileId = 4L,
                configFileId = 5L,
                isDefault = false,
                relays = listOf(RelayEntity(id = 9L, nodeId = 5L, addr = "relay.pool.test", port = 3001)),
            )
        val tree = mapper.readTree(mapper.writeValueAsString(node))
        assertThat(tree.path("relays").size()).isEqualTo(1)
        assertThat(tree.path("relays")[0].path("addr").asString()).isEqualTo("relay.pool.test")
        assertThat(tree.path("relays")[0].path("port").asInt()).isEqualTo(3001)
    }

    @Test
    fun getNodesAndUpdateNodeColorAttachRelaysAndExcludeDeletedNodes() {
        val nodeRepository = mockk<NodeRepository>()
        val relayRepository = mockk<RelayRepository>()
        val webSocketTemplate = mockk<SimpMessagingTemplate>(relaxed = true)
        var storedNode =
            Node(
                id = 5L,
                hostId = 1L,
                color = "#112233",
                type = "core",
                processorThreads = 2,
                name = "core-1",
                listen = "0.0.0.0",
                port = 3001,
                promPort = 12798,
                genesisByronFileId = 1L,
                genesisShelleyFileId = 2L,
                genesisAlonzoFileId = 3L,
                genesisConwayFileId = 4L,
                configFileId = 5L,
                isDefault = false,
            )
        val deletedNode = storedNode.copy(id = 6L, name = "deleted", isDeleted = true)
        val relay = RelayEntity(id = 9L, nodeId = 5L, addr = "relay.pool.test", port = 3001)
        val orphanRelay = RelayEntity(id = 10L, nodeId = 6L, addr = "deleted.pool.test", port = 3002)

        every { nodeRepository.findAll(any<Sort>()) } answers { listOf(storedNode, deletedNode) }
        every { nodeRepository.findById(5L) } answers { Optional.of(storedNode) }
        every { nodeRepository.save(any<Node>()) } answers {
            storedNode = firstArg()
            storedNode
        }
        every { relayRepository.findAll() } returns listOf(relay, orphanRelay)

        val target =
            createTarget(
                nodeRepository = nodeRepository,
                relayRepository = relayRepository,
                webSocketTemplate = webSocketTemplate,
            )

        val getResponse = target.getNodes() as SocketResponse.Success<List<Node>>
        assertThat(getResponse.data).hasSize(1)
        assertThat(getResponse.data.single().id).isEqualTo(5L)
        assertThat(getResponse.data.single().relays).containsExactly(relay)

        target.updateNodeColor(UpdateColorRequest(id = 5L, color = "#abcdef"))
        assertThat(storedNode.color).isEqualTo("#abcdef")
        verify(exactly = 1) {
            webSocketTemplate.convertAndSend(
                "/topic/messages",
                match<SocketResponse.Success<List<Node>>> {
                    it.type == "nodes" &&
                        it.data.size == 1 &&
                        it.data.single().color == "#abcdef" &&
                        it.data.single().relays == listOf(relay)
                },
            )
        }
    }

    @Test
    fun rotateKesRejectsDraftDeletedRelayAndCrossNetworkNodesBeforeHostSideEffects() {
        val nodeRepository = mockk<NodeRepository>()
        val fileRepository = mockk<FileRepository>()
        val cardanoRepository = mockk<CardanoRepository>()
        val webSocketTemplate = mockk<SimpMessagingTemplate>(relaxed = true)
        val defaultNode =
            Node(
                id = 1L,
                hostId = 1L,
                color = "#000000",
                type = "core",
                processorThreads = 2,
                name = "default-core",
                listen = "127.0.0.1",
                port = 3001,
                promPort = 12798,
                genesisByronFileId = 1L,
                genesisShelleyFileId = 2L,
                genesisAlonzoFileId = 3L,
                genesisConwayFileId = 4L,
                configFileId = 5L,
                isDefault = true,
            )
        val cases =
            mapOf(
                10L to defaultNode.copy(id = 10L, name = "draft-core", isDefault = false, isDraft = true),
                11L to defaultNode.copy(id = 11L, name = "deleted-core", isDefault = false, isDeleted = true),
                12L to defaultNode.copy(id = 12L, name = "relay-node", isDefault = false, type = "relay"),
                13L to defaultNode.copy(id = 13L, name = "other-net-core", isDefault = false, genesisShelleyFileId = 999L),
            )

        every { nodeRepository.findDefault() } returns defaultNode
        every { nodeRepository.findById(any()) } answers { Optional.ofNullable(cases[firstArg<Long>()]) }
        every { cardanoRepository.networkKey(any()) } answers { "network:${firstArg<Node>().genesisShelleyFileId}" }

        val target =
            createTarget(
                nodeRepository = nodeRepository,
                fileRepository = fileRepository,
                cardanoRepository = cardanoRepository,
                webSocketTemplate = webSocketTemplate,
            )

        for (nodeId in cases.keys) {
            assertThrows<RuntimeException> {
                target.rotateKes(RotateKesRequest(id = nodeId, spendingPassword = "secret"))
            }
        }
        verify(exactly = 0) { fileRepository.findById(any()) }
        verify(exactly = 0) { fileRepository.save(any()) }
    }

    @Test
    fun rotateKesUsesTargetNodeGenesisUuidSecretPathsExcludesDraftChildPoolsAndBroadcastsNodes() {
        val nodeRepository = mockk<NodeRepository>()
        val relayRepository = mockk<RelayRepository>()
        val hostRepository = mockk<HostRepository>()
        val fileRepository = mockk<FileRepository>()
        val cardanoRepository = mockk<CardanoRepository>()
        val walletUtils = mockk<WalletUtils>()
        val webSocketTemplate = mockk<SimpMessagingTemplate>(relaxed = true)

        val host =
            Host(
                id = 1L,
                type = "local",
                cardanoCliPath = "/bin/cardano-cli",
                cardanoNodePath = "/bin/cardano-node",
                hostname = "localhost",
                sshUser = "user",
                nodeHomePath = "/nodes",
                jcliPath = null,
            )
        // defaultNode intentionally points to non-existent genesis IDs (901/902) to prove rotateKes reads node's genesis!
        val defaultNode =
            Node(
                id = 1L,
                hostId = 1L,
                color = "#000000",
                type = "relay",
                processorThreads = 2,
                name = "default-relay",
                listen = "127.0.0.1",
                port = 3000,
                promPort = 12789,
                genesisByronFileId = 901L,
                genesisShelleyFileId = 902L,
                genesisAlonzoFileId = 3L,
                genesisConwayFileId = 4L,
                configFileId = 5L,
                isDefault = true,
            )
        var coreNode =
            Node(
                id = 10L,
                hostId = 1L,
                color = "#112233",
                type = "core",
                processorThreads = 4,
                name = "core-1",
                listen = "0.0.0.0",
                port = 3001,
                promPort = 12798,
                genesisByronFileId = 1L,
                genesisShelleyFileId = 2L,
                genesisAlonzoFileId = 3L,
                genesisConwayFileId = 4L,
                configFileId = 5L,
                isDefault = false,
                coreSKeyId = 11L,
                coreCounterId = 12L,
                vrfSKeyId = 13L,
                kesSKeyId = 14L,
                kesVKeyId = 15L,
                opcertId = 16L,
            )
        val activeChild =
            coreNode.copy(
                id = 20L,
                name = "pool-active",
                type = "pool",
                parentId = 10L,
                opcertId = 21L,
                vrfSKeyId = 22L,
                kesSKeyId = 23L,
            )
        val draftChild =
            coreNode.copy(
                id = 21L,
                name = "pool-draft",
                type = "pool",
                parentId = 10L,
                isDraft = true,
                opcertId = -1L,
                vrfSKeyId = 24L,
                kesSKeyId = 25L,
            )
        val deletedChild =
            coreNode.copy(
                id = 22L,
                name = "pool-deleted",
                type = "pool",
                parentId = 10L,
                isDeleted = true,
                opcertId = 26L,
                vrfSKeyId = 27L,
                kesSKeyId = 28L,
            )
        val relay = RelayEntity(id = 7L, nodeId = 10L, addr = "relay.core.test", port = 3001)

        val files =
            mutableMapOf(
                1L to File(1L, "byron.json", """{"startTime":1506203091,"protocolConsts":{"k":2160},"blockVersionData":{"slotDuration":20000}}"""),
                2L to File(2L, "shelley.json", """{"activeSlotsCoeff":0.05,"networkId":"Testnet","networkMagic":42,"slotLength":1,"epochLength":432000,"slotsPerKESPeriod":129600,"systemStart":"2017-09-23T21:44:51Z","maxKESEvolutions":62}"""),
                11L to File(11L, "core.skey", """{"type":"NodeOperationalCertificateIssueCounter","description":"","cborHex":"5820${"aa".repeat(32)}"}"""),
                12L to File(12L, "core.counter", """{"type":"NodeOperationalCertificateIssueCounter","description":"","cborHex":"01"}"""),
                13L to File(13L, "core.vrf.skey", """{"type":"VrfSigningKey_PraosVRF","description":"","cborHex":"5840${"bb".repeat(64)}"}"""),
                14L to File(14L, "core.kes.skey", """{"type":"KesSigningKey_ed25519_kes_2^6","description":"","cborHex":"5820${"cc".repeat(32)}"}"""),
                15L to File(15L, "core.kes.vkey", """{"type":"KesVerificationKey_ed25519_kes_2^6","description":"","cborHex":"5820${"dd".repeat(32)}"}"""),
                16L to File(16L, "core.opcert", """{"type":"NodeOperationalCertificate","description":"","cborHex":"5820${"ee".repeat(32)}"}"""),
                21L to File(21L, "child.opcert", """{"type":"NodeOperationalCertificate","description":"","cborHex":"5820${"21".repeat(32)}"}"""),
                22L to File(22L, "child.vrf.skey", """{"type":"VrfSigningKey_PraosVRF","description":"","cborHex":"5840${"22".repeat(64)}"}"""),
                23L to File(23L, "child.kes.skey", """{"type":"KesSigningKey_ed25519_kes_2^6","description":"","cborHex":"5820${"23".repeat(32)}"}"""),
            )
        var nextFileId = 100L

        every { walletUtils.isValidSpendingPassword("secret") } returns true
        every { walletUtils.getSKeyContent(any(), "secret") } answers { firstArg<File>().content }
        every { walletUtils.encryptSKeyContent(any(), "secret") } answers { firstArg() }
        every { nodeRepository.findDefault() } returns defaultNode
        every { nodeRepository.findById(10L) } answers { Optional.of(coreNode) }
        every { nodeRepository.findByParentId(10L) } returns listOf(activeChild, draftChild, deletedChild)
        every { nodeRepository.findAll(any<Sort>()) } answers { listOf(defaultNode, coreNode, activeChild, draftChild, deletedChild) }
        every { nodeRepository.save(any<Node>()) } answers {
            coreNode = firstArg()
            coreNode
        }
        every { relayRepository.findAll() } returns listOf(relay)
        every { hostRepository.findById(1L) } returns Optional.of(host)
        every { fileRepository.findById(any()) } answers {
            val id = firstArg<Long>()
            assertThat(id).isNotEqualTo(-1L)
            assertThat(id).isNotIn(listOf(901L, 902L, 26L, 27L, 28L))
            Optional.ofNullable(files[id])
        }
        every { fileRepository.save(any<File>()) } answers {
            val incoming = firstArg<File>()
            val saved = if (incoming.id == null) incoming.copy(id = nextFileId++) else incoming
            files[requireNotNull(saved.id)] = saved
            saved
        }
        every { cardanoRepository.networkKey(any()) } returns "same-network"
        every { cardanoRepository.getEra(any(), any(), any(), any()) } returns "conway"

        val secretWrites = mutableMapOf<String, String>()
        val plainWrites = mutableMapOf<String, String>()
        val remoteFiles = mutableMapOf<String, String>()
        val commandArgsCalls = mutableListOf<List<String>>()

        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().bashCommand(any(), any()) } returns "SSH connectivity verified"
            every { anyConstructed<HostConnection>().commandFileExists(any()) } returns false
            every { anyConstructed<HostConnection>().commandWriteSecret(any(), any()) } answers {
                val path = firstArg<String>()
                val content = secondArg<String>()
                secretWrites[path] = content
                remoteFiles[path] = content
            }
            every { anyConstructed<HostConnection>().commandWriteFile(any(), any()) } answers {
                val path = firstArg<String>()
                val content = secondArg<String>()
                plainWrites[path] = content
                remoteFiles[path] = content
                ""
            }
            every { anyConstructed<HostConnection>().commandReadFile(any()) } answers {
                remoteFiles.getValue(firstArg())
            }
            every { anyConstructed<HostConnection>().command(any<String>()) } answers {
                val cmd = firstArg<String>()
                when {
                    "key-gen-KES" in cmd -> {
                        val vkey = Regex("--verification-key-file\\s+([^\\s]+)").find(cmd)!!.groupValues[1]
                        val skey = Regex("--signing-key-file\\s+([^\\s]+)").find(cmd)!!.groupValues[1]
                        remoteFiles[vkey] = """{"type":"KesVerificationKey_ed25519_kes_2^6","description":"","cborHex":"5820${"ef".repeat(32)}"}"""
                        remoteFiles[skey] = """{"type":"KesSigningKey_ed25519_kes_2^6","description":"","cborHex":"5820${"fe".repeat(32)}"}"""
                        ""
                    }
                    "issue-op-cert" in cmd -> {
                        val counter = Regex("--operational-certificate-issue-counter\\s+([^\\s]+)").find(cmd)!!.groupValues[1]
                        val out = Regex("--out-file\\s+([^\\s]+)").find(cmd)!!.groupValues[1]
                        remoteFiles[counter] = """{"type":"NodeOperationalCertificateIssueCounter","description":"","cborHex":"02"}"""
                        remoteFiles[out] = """{"type":"NodeOperationalCertificate","description":"","cborHex":"5820${"99".repeat(32)}"}"""
                        ""
                    }
                    cmd.startsWith("date --date=2017-") -> "1506203091"
                    cmd == "date -u +%s" -> "1700000000"
                    cmd.startsWith("date --date=@") -> "Sun Feb 01 00:00:00 UTC 2026"
                    else -> ""
                }
            }
            every { anyConstructed<HostConnection>().command(any<List<String>>()) } answers {
                val args = firstArg<List<String>>()
                if (args.firstOrNull() == "mv") {
                    remoteFiles[args[3]] = remoteFiles.remove(args[2])!!
                } else if (args.firstOrNull() == "rm") {
                    args.drop(2).forEach(remoteFiles::remove)
                }
                ""
            }
            every { anyConstructed<HostConnection>().commandArgs(any()) } answers {
                val args = firstArg<List<String>>()
                commandArgsCalls += args
                if (args.firstOrNull() == "rm") {
                    args.drop(2).forEach(remoteFiles::remove)
                }
                ""
            }

            val target =
                createTarget(
                    nodeRepository = nodeRepository,
                    relayRepository = relayRepository,
                    hostRepository = hostRepository,
                    fileRepository = fileRepository,
                    walletUtils = walletUtils,
                    webSocketTemplate = webSocketTemplate,
                    cardanoRepository = cardanoRepository,
                )

            target.rotateKes(RotateKesRequest(id = 10L, spendingPassword = "secret"))

            // Cold signing key was written via commandWriteSecret under /tmp/jormanager-kes-<uuid>.node.skey, never /tmp/core.*
            assertThat(plainWrites.keys.none { it.endsWith(".node.skey") || it.startsWith("/tmp/core.") }).isTrue()
            val coldSecretPath = secretWrites.keys.single { it.endsWith(".node.skey") }
            assertThat(coldSecretPath).startsWith("/tmp/jormanager-kes-")

            // Generated kes.skey received chmod 600 and all /tmp/jormanager-kes-* files were cleaned up
            val chmodKes = commandArgsCalls.single { it.take(2) == listOf("chmod", "600") }[2]
            assertThat(chmodKes).startsWith("/tmp/jormanager-kes-")
            assertThat(chmodKes).endsWith(".kes.skey")
            assertThat(remoteFiles.keys.filter { it.startsWith("/tmp/jormanager-kes-") }).isEmpty()

            // Bulk credentials include core + active child pool (2 entries), excluding draft and deleted child pools
            val bulkJson = objectMapper.readTree(remoteFiles.getValue("/nodes/core-1/credentials.json"))
            assertThat(bulkJson.size()).isEqualTo(2)

            // Nodes broadcast includes updated KES expiry and relays
            assertThat(coreNode.kesExpireTimeSec).isEqualTo(1700000000L + 62L * 129600L)
            verify(exactly = 1) {
                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    match<SocketResponse.Success<List<Node>>> {
                        it.type == "nodes" &&
                            it.data.none { n -> n.isDeleted } &&
                            it.data.single { n -> n.id == 10L }.relays == listOf(relay)
                    },
                )
            }
        } finally {
            unmockkConstructor(HostConnection::class)
        }
    }

    @Test
    @Disabled
    fun testCreateNode() {
        val target =
            NodeController(
                nodeRepository = mockk(relaxed = true),
                relayRepository = mockk(relaxed = true),
                hostRepository =
                    mockk(relaxed = true) {
                        every { findByIdOrNull(1L) } returns
                            Host(
                                id = 1L,
                                type = "local",
                                cardanoCliPath = "/home/westbam/.local/bin/cardano-cli",
                                cardanoNodePath = "/home/westbam/.local/bin/cardano-node",
                                hostname = "brainy",
                                sshUser = "westbam",
                                nodeHomePath = "/home/westbam/haskell",
                                jcliPath = "/home/westbam/.cargo/bin/jcli"
                            )
                    },
                fileRepository = mockk(relaxed = true),
                walletUtils = mockk(relaxed = true) {
                    every { isValidSpendingPassword(any()) } returns true
                },
                webSocketTemplate = mockk(relaxed = true),
                nodesChannel = mockk(relaxed = true),
                retrofit = Retrofit.Builder().baseUrl("http://dummy.com").build(),
                okHttpClient = OkHttpClient.Builder().build(),
                extendedMetadataAdapter = config.getExtendedMetadataAdapter(moshi),
                shelleyGenesisAdapter = config.getShelleyGenesisAdapter(moshi),
                byronGenesisAdapter = config.getByronGenesisAdapter(moshi),
                metadataAdapter = config.getMetadataAdapter(moshi),
                keyFileJsonAdapter = mockk(relaxed = true),
                cardanoRepository = mockk(relaxed = true),
                nodeConfigurationFiles = mockk(relaxed = true),
            )

        val request =
            CreateNodeRequest(
                requestId = java.util.UUID.randomUUID().toString(),
                color = "#0000FF",
                hostId = 1L,
                name = "tickr",
                isDefault = false,
                type = "core",
                processorThreads = 8,
                listen = "127.0.0.1",
                port = 6001,
                prometheusListen = "127.0.0.1",
                enableTracingListener = true,
                tracingListen = "0.0.0.0",
                genesisByronFileId = 1L,
                genesisShelleyFileId = 2L,
                genesisAlonzoFileId = 3L,
                genesisConwayFileId = 4L,
                generateColdKeys = true,
                coldSKey = null,
                coldVKey = null,
                coldCounter = null,
                generateVRFKeys = true,
                vrfSKey = null,
                vrfVKey = null,
                generateKESKeys = true,
                kesSKey = null,
                kesVKey = null,
                registrationFeesAccount = 1L,
                ownerStakingAccount = 2L,
                rewardsStakingAccount = 3L,
                poolPledge = 250000000000L.toBigInteger(),
                poolCost = 340000000L.toBigInteger(),
                poolMargin = "0.05",
                relays =
                    listOf(
                        Relay("relay0.flippin-stakes.com", 3001),
                        Relay("relay1.flippin-stakes.com", 3001),
                        Relay("52.98.47.198", 3001)
                    ),
                metadata =
                    com.swiftmako.jormanager.model.Metadata(
                        ticker = "TICKR",
                        name = "Flippin Stakes Pool",
                        description = "The best stakepool in Flippin, Arkansas!",
                        homepage = "https://flippin-stakes.com",
                        extended =
                            Extended(
                                itn =
                                    Itn(
                                        publicKey = "ed25519_pk1ly9wqdrgfjjskzy503z9dt8qsn5285uay3trfts2pdg85qwfkqnscrsknk",
                                        privateKey = "ed25519e_sk1jq49wy7uq4tepqumk2c9q3ym6fwlvk9g7l68e2kqzyafyx7hd9nvzjnrf8y5796mjrasyda7hgnzmk6jhyq88kkzg4l6us2x438n7tczxz02x"
                                    ),
                                info =
                                    Info(
                                        icon64 = "https://flippin-stakes.com/icon64x64.png",
                                        logo = "https://flippin-stakes.com/logo.png",
                                        location = "United States, North America",
                                        social =
                                            Social(
                                                twitter = "flippinstakes",
                                                telegram = "flippin_stakes_telegram_group",
                                                facebook = "flippin_stakes_facebook",
                                                youtube = "flippin_stakes_youtube",
                                                discord = "flippin_stakes_discord",
                                                github = "flippin_stakes_github",
                                                twitch = "flippin_stakes_twitch"
                                            ),
                                        company =
                                            Company(
                                                name = "Flippin Stakes, LLC.",
                                                addr = "123 Backflip Ln.",
                                                city = "Flippin, AK",
                                                country = "United States",
                                                companyId = "38391290",
                                                vatId = "2934858"
                                            ),
                                        about =
                                            About(
                                                me = "Best damn pool operator in Flippin, Arkansas!",
                                                server = "EPYC with lots 'o RAM!",
                                                company = "Flippin Stakes, LLC."
                                            ),
                                        rss = "https://flippin-stakes.com/atom.xml"
                                    ),
                                telegramAdminHandle = "Bubba1977",
                                adapoolsVerify = null
                            )
                    ),
                sudoPassword = "asdfasdf",
                spendingPassword = "asdfasdf",
                parentId = null,
            )
        val response = target.createNode(request)

        assertThat(response).isInstanceOf(SocketResponse.Success::class.java)
    }
}
