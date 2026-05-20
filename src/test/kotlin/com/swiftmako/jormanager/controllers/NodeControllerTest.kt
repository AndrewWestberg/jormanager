package com.swiftmako.jormanager.controllers

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.model.*
import com.swiftmako.jormanager.spring.config.Configuration
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
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

    private fun createTarget() =
        NodeController(
            nodeRepository = mockk(relaxed = true),
            hostRepository = mockk(relaxed = true),
            fileRepository = mockk(relaxed = true),
            walletRepository = mockk(relaxed = true),
            walletUtils =
                mockk(relaxed = true) {
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
        ).containsExactly("Stdout MachineFormat", "PrometheusSimple suffix 0.0.0.0 12900")
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
        assertThat(root.get("TraceOptionForwarder")).isNull()
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
