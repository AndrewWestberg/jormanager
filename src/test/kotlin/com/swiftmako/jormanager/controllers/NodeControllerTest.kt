package com.swiftmako.jormanager.controllers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.entities.WalletEntry
import com.swiftmako.jormanager.model.*
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.spring.config.Configuration
import com.swiftmako.jormanager.utils.Bech32
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
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
        hostRepository: HostRepository = mockk(relaxed = true),
        fileRepository: FileRepository = mockk(relaxed = true),
        walletRepository: WalletRepository = mockk(relaxed = true),
        walletUtils: WalletUtils =
            mockk(relaxed = true) {
                every { isValidSpendingPassword(any()) } returns true
            },
        webSocketTemplate: SimpMessagingTemplate = mockk(relaxed = true),
        cardanoRepository: CardanoRepository = mockk(relaxed = true),
    ) = NodeController(
        nodeRepository = nodeRepository,
        hostRepository = hostRepository,
        fileRepository = fileRepository,
        walletRepository = walletRepository,
        walletUtils = walletUtils,
        transactionRepository = mockk(relaxed = true),
        webSocketTemplate = webSocketTemplate,
        nodesChannel = mockk(relaxed = true),
        retrofit = config.getRetrofit(httpClient, moshi),
        okHttpClient = httpClient,
        relayRepository = mockk(relaxed = true),
        extendedMetadataAdapter = config.getExtendedMetadataAdapter(moshi),
        shelleyGenesisAdapter = config.getShelleyGenesisAdapter(moshi),
        byronGenesisAdapter = config.getByronGenesisAdapter(moshi),
        metadataAdapter = config.getMetadataAdapter(moshi),
        protocolParamsAdapter = config.getProtocolParametersAdapter(moshi),
        queryTipAdapter = config.getQueryTipAdapter(moshi),
        keyFileJsonAdapter = mockk(relaxed = true),
        bulkCredentialsJsonAdapter = mockk(relaxed = true),
        txSignedAdapter = mockk(relaxed = true),
        ledgerDao = mockk(relaxed = true),
        cardanoUtils = mockk(relaxed = true),
        cardanoRepository = cardanoRepository,
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
        assertThat(json["body"]["comment"].textValue()).isEqualTo(rationale)
        assertThat(json["hashAlgorithm"].textValue()).isEqualTo("blake2b-256")
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
    fun submitGovernanceVoteSharesOneAnchorAcrossMixedPoolVotes() {
        val fixture = RationaleHttpFixture()

        val commands = submitGovernanceVoteUntilBuild("Shared rationale", fixture)

        val votes = commands.filter { "governance vote create" in it }
        assertThat(votes).hasSize(2)
        assertThat(votes[0]).contains("--yes")
        assertThat(votes[1]).contains("--no")
        val anchors =
            votes.map {
                Regex("""--anchor-url '([^']+)' --anchor-data-hash ([0-9a-f]{64})""").find(it)?.value
            }
        val distinctAnchors = anchors.distinct()
        assertThat(distinctAnchors).hasSize(1)
        assertThat(distinctAnchors.single()).isNotNull()
        assertThat(fixture.uploadCount).isEqualTo(1)
    }

    @Test
    fun submitGovernanceVoteLeavesBlankRationaleUnanchored() {
        val fixture = RationaleHttpFixture()

        val commands = submitGovernanceVoteUntilBuild(" \n", fixture)

        val votes = commands.filter { "governance vote create" in it }
        assertThat(votes).hasSize(2)
        assertThat(votes.all { "--anchor-" !in it }).isTrue()
        assertThat(fixture.requestCount).isEqualTo(0)
    }

    @Test
    fun submitGovernanceVoteStopsBeforeSigningWhenRationaleIntegrityFails() {
        val fixture = RationaleHttpFixture(getBytes = { bytes -> bytes + 0 })

        val commands = submitGovernanceVoteUntilBuild("Must not downgrade", fixture)

        assertThat(commands.none { "governance vote create" in it }).isTrue()
        assertThat(commands.none { "transaction sign" in it || "transaction submit" in it }).isTrue()
    }

    private fun submitGovernanceVoteUntilBuild(
        rationale: String?,
        fixture: RationaleHttpFixture,
    ): List<String> {
        val nodeRepository = mockk<NodeRepository>()
        val hostRepository = mockk<HostRepository>()
        val fileRepository = mockk<FileRepository>()
        val walletRepository = mockk<WalletRepository>()
        val walletUtils =
            mockk<WalletUtils> {
                every { isValidSpendingPassword(any()) } returns true
            }
        val cardanoRepository = mockk<CardanoRepository>()
        val node1 = governanceNode(1, 11, 12, true)
        val node2 = governanceNode(2, 21, 22, false)
        val files =
            mapOf(
                10L to
                    File(
                        id = 10,
                        name = "shelley.json",
                        content = """{"activeSlotsCoeff":0.05,"networkId":"mainnet","slotLength":1,"epochLength":432000,"slotsPerKESPeriod":129600,"systemStart":"2017-09-23T21:44:51Z","maxKESEvolutions":62}""",
                    ),
                11L to File(11, "node1.vkey", "vkey-1"),
                12L to File(12, "node1.skey", "encrypted-skey-1"),
                21L to File(21, "node2.vkey", "vkey-2"),
                22L to File(22, "node2.skey", "encrypted-skey-2"),
            )
        val feeKey = File(30, "payment.skey", "encrypted-payment-skey")
        val feeWallet = WalletEntry(id = 5, name = "fees", type = "payment", paymentAddr = "addr_test1fees", paymentSkey = feeKey)
        every { nodeRepository.findDefault() } returns node1
        every { nodeRepository.findById(any()) } answers {
            Optional.ofNullable(mapOf(1L to node1, 2L to node2)[firstArg()])
        }
        every { hostRepository.findById(any()) } returns Optional.of(testHost)
        every { fileRepository.findById(any()) } answers { Optional.ofNullable(files[firstArg()]) }
        every { walletRepository.findById(any()) } returns Optional.of(feeWallet)
        every { cardanoRepository.getEra(any(), any(), any(), any()) } returns "conway"
        every { walletUtils.getUtxos(any(), any(), any(), any(), any(), any()) } returns
            listOf(Utxo("tx", 0, 10_000_000.toBigInteger(), emptyList()))
        every { walletUtils.getSKeyContent(any(), any()) } returns "decrypted-skey"

        val commands = mutableListOf<String>()
        mockkConstructor(HostConnection::class)
        try {
            every { anyConstructed<HostConnection>().commandWriteFile(any(), any()) } returns ""
            every { anyConstructed<HostConnection>().command(any<String>()) } answers {
                firstArg<String>().also(commands::add).let { command ->
                    when {
                        "query protocol-parameters" in command -> "{}"
                        "query tip" in command -> """{"era":"Conway","slot":100}"""
                        "transaction build-raw" in command -> throw IOException("Stop after generated vote files")
                        else -> ""
                    }
                }
            }
            val target =
                createTarget(
                    httpClient = fixture.client(),
                    nodeRepository = nodeRepository,
                    hostRepository = hostRepository,
                    fileRepository = fileRepository,
                    walletRepository = walletRepository,
                    walletUtils = walletUtils,
                    cardanoRepository = cardanoRepository,
                )
            val request =
                GovernanceVoteRequest(
                    govActionId = Bech32.encode("gov_action", ByteArray(33)),
                    votes = listOf(NodeVoteSelection(1, "YES"), NodeVoteSelection(2, "NO")),
                    feesAccountId = 5,
                    spendingPassword = "password",
                    rationale = rationale,
                )

            assertThrows<RuntimeException> { target.submitGovernanceVote(request) }
        } finally {
            unmockkConstructor(HostConnection::class)
        }
        return commands
    }

    private fun governanceNode(
        id: Long,
        vkeyId: Long,
        skeyId: Long,
        isDefault: Boolean,
    ) = Node(
        id = id,
        hostId = 1,
        color = "#000000",
        type = NodeController.NODE_TYPE_CORE,
        processorThreads = 2,
        name = "node-$id",
        listen = "127.0.0.1",
        port = 3000 + id.toInt(),
        promPort = 12790 + id.toInt(),
        genesisByronFileId = 10,
        genesisShelleyFileId = 10,
        genesisAlonzoFileId = 10,
        genesisConwayFileId = 10,
        configFileId = 10,
        isDefault = isDefault,
        coreVKeyId = vkeyId,
        coreSKeyId = skeyId,
    )

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
        assertThat(root.get("minSeverity").asText()).isEqualTo("Critical")
        assertThat(root.get("PeerSharing").asBoolean()).isFalse()
        assertThat(root.get("MaxConcurrencyDeadline").asInt()).isEqualTo(2)
        assertThat(root.get("ConwayGenesisFile").asText()).isEqualTo("conway-genesis.json")
        assertThat(root.get("AlonzoGenesisFile").asText()).isEqualTo("alonzo-genesis.json")
        assertThat(root.get("ByronGenesisFile").asText()).isEqualTo("byron-genesis.json")
        assertThat(root.get("ShelleyGenesisFile").asText()).isEqualTo("shelley-genesis.json")
        assertThat(root.get("GenesisFile").asText()).isEqualTo("shelley-genesis.json")

        val rootTraceOptions = root.get("TraceOptions").get("")
        assertThat(rootTraceOptions.get("severity").asText()).isEqualTo("Warning")
        assertThat(rootTraceOptions.get("detail")).isNull()
        assertThat(rootTraceOptions.get("backends").map { it.asText() })
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
                .get("severity")
                .asText()
        ).isEqualTo("Info")
        assertThat(
            root
                .get("TraceOptions")
                .get("ChainDB.AddBlockEvent.AddedToCurrentChain")
                .get("severity")
                .asText()
        ).isEqualTo("Silence")
        assertThat(
            root
                .get("TraceOptions")
                .get("Resources")
                .get("severity")
                .asText()
        ).isEqualTo("Silence")
        assertThat(root.fieldNames().asSequence().toList())
            .containsAtLeast(
                "ConwayGenesisFile",
                "AlonzoGenesisFile",
                "ByronGenesisFile",
                "ShelleyGenesisFile",
                "GenesisFile",
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
        assertThat(root.get("TraceOptionMetricsPrefix").asText()).isEqualTo("cardano.node.metrics.")
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
        assertThat(root.get("minSeverity").asText()).isEqualTo("Critical")
        assertThat(root.get("PeerSharing").asBoolean()).isTrue()
        assertThat(root.get("MaxConcurrencyDeadline").asInt()).isEqualTo(4)
        assertThat(
            root
                .get("TraceOptions")
                .get("")
                .get("backends")
                .map { it.asText() }
        ).containsExactly("Stdout MachineFormat", "Forwarder", "PrometheusSimple suffix 0.0.0.0 12900")
            .inOrder()
        assertThat(
            root
                .get("TraceOptions")
                .get("Forge.Loop.AdoptedBlock")
                .get("severity")
                .asText()
        ).isEqualTo("Info")
        assertThat(
            root
                .get("TraceOptions")
                .get("ChainDB.AddBlockEvent.AddedToCurrentChain")
                .get("severity")
                .asText()
        ).isEqualTo("Silence")
        val forwarder = root.get("TraceOptionForwarder")
        assertThat(forwarder).isNotNull()
        assertThat(forwarder.get("connQueueSize").asInt()).isEqualTo(64)
        assertThat(forwarder.get("disconnQueueSize").asInt()).isEqualTo(128)
        assertThat(forwarder.get("maxReconnectDelay").asInt()).isEqualTo(30)
        assertThat(root.fieldNames().asSequence().toList())
            .containsAtLeast(
                "ConwayGenesisFile",
                "AlonzoGenesisFile",
                "ByronGenesisFile",
                "ShelleyGenesisFile",
                "GenesisFile",
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
        assertThat(root.get("TraceOptionMetricsPrefix").asText()).isEqualTo("cardano.node.metrics.")
        assertThat(root.get("TraceOptionResourceFrequency").asInt()).isEqualTo(1000)
    }

    @Test
    @Disabled
    fun testCreateNode() {
        val target =
            NodeController(
                nodeRepository = mockk(relaxed = true),
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
                walletRepository = mockk(relaxed = true),
                walletUtils = mockk(relaxed = true) {
                    every { isValidSpendingPassword(any()) } returns true
                },
                transactionRepository = mockk(relaxed = true),
                webSocketTemplate = mockk(relaxed = true),
                nodesChannel = mockk(relaxed = true),
                retrofit = Retrofit.Builder().baseUrl("http://dummy.com").build(),
                okHttpClient = OkHttpClient.Builder().build(),
                relayRepository = mockk(relaxed = true),
                extendedMetadataAdapter = config.getExtendedMetadataAdapter(moshi),
                shelleyGenesisAdapter = config.getShelleyGenesisAdapter(moshi),
                byronGenesisAdapter = config.getByronGenesisAdapter(moshi),
                metadataAdapter = config.getMetadataAdapter(moshi),
                protocolParamsAdapter = config.getProtocolParametersAdapter(moshi),
                queryTipAdapter = config.getQueryTipAdapter(moshi),
                keyFileJsonAdapter = mockk(relaxed = true),
                bulkCredentialsJsonAdapter = mockk(relaxed = true),
                txSignedAdapter = mockk(relaxed = true),
                ledgerDao = mockk(relaxed = true),
                cardanoUtils = mockk(relaxed = true),
                cardanoRepository = mockk(relaxed = true),
            )

        val request =
            CreateNodeRequest(
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
