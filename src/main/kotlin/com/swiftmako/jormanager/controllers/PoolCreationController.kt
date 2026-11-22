package com.swiftmako.jormanager.controllers

import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.File
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.KeyInstallation
import com.swiftmako.jormanager.entities.LeiosVotingKey
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.Relay
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.CreateNodeRequest
import com.swiftmako.jormanager.model.CreateNodeResult
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.KeyInstallationRepository
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.repositories.LeiosKeyRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.ProtocolParametersRepository
import com.swiftmako.jormanager.repositories.RelayRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.services.NodeConfigurationFiles
import com.swiftmako.jormanager.services.PoolDeploymentService
import com.swiftmako.jormanager.services.WalletTransactionBuilder
import com.swiftmako.jormanager.utils.CborSpans
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableSharedFlow
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Controller
class PoolCreationController(
    private val service: PoolCreationService,
    private val nodeController: NodeController,
    private val messages: SimpMessagingTemplate,
) {
    @MessageMapping("/createnode")
    fun create(request: CreateNodeRequest) {
        if (request.type == "relay") {
            nodeController.createNode(request)
            return
        }
        try {
            val node = service.create(request)
            messages.convertAndSend("/topic/messages", SocketResponse.Success("nodes", nodeController.activeNodesWithRelays()))
            messages.convertAndSend(
                "/topic/messages",
                SocketResponse.Success(
                    "createnode",
                    CreateNodeResult(request.requestId, requireNotNull(node.id), "${node.name} created successfully!"),
                ),
            )
        } catch (e: PoolCreationFailure) {
            messages.convertAndSend(
                "/topic/messages",
                SocketResponse.Error(
                    "createnode",
                    SocketResponse.PublicFailure(
                        "PoolCreationFailed",
                        e.message ?: "Pool setup failed",
                        "createnode",
                        e.disposition,
                        request.requestId,
                    ),
                ),
            )
        } catch (_: Exception) {
            messages.convertAndSend(
                "/topic/messages",
                SocketResponse.Error(
                    "createnode",
                    SocketResponse.PublicFailure(
                        "PoolCreationFailed",
                        "Pool setup failed; inspect protected server diagnostics.",
                        "createnode",
                        "retry",
                        request.requestId,
                    ),
                ),
            )
        }
    }
}

class PoolCreationFailure(
    message: String,
    val disposition: String = "retry",
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

@Service
class PoolCreationService(
    private val nodes: NodeRepository,
    private val hosts: HostRepository,
    private val files: FileRepository,
    private val wallets: WalletRepository,
    private val relays: RelayRepository,
    private val keys: LeiosKeyRepository,
    private val installations: KeyInstallationRepository,
    private val walletUtils: WalletUtils,
    private val configurationFiles: NodeConfigurationFiles,
    private val eras: CardanoRepository,
    private val protocolParameters: ProtocolParametersRepository,
    private val txBuilder: WalletTransactionBuilder,
    private val ledger: LedgerDao,
    private val nodeController: NodeController,
    private val installer: PoolDeploymentService,
    private val json: ObjectMapper,
    @param:Qualifier("nodesChannel") private val nodesChannel: MutableSharedFlow<Node>,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)
    private val inFlightRequests = ConcurrentHashMap.newKeySet<String>()
    private val safePath = Regex("[A-Za-z0-9_./-]+")
    private val decimal = Regex("0|[1-9][0-9]*")

    private data class PreparedBlsKey(
        val signingKeyEnvelope: String,
        val encryptedSigningKey: String,
        val publicKey: ByteArray,
        val proof: ByteArray,
        val provenance: String,
    )

    private data class PreparedKeys(
        val poolId: String,
        val coldSKey: String,
        val coldVKey: String,
        val advancedCounter: String,
        val vrfSKey: String,
        val vrfVKey: String,
        val kesSKey: String,
        val kesVKey: String,
        val opcert: String,
        val bls: PreparedBlsKey?,
    )

    private fun checkSafePath(path: String): String =
        path.also {
            require(safePath.matches(it) && !it.startsWith("-") && !it.split('/').contains("..")) { "Invalid host path" }
        }

    private fun networkArgsForGenesis(shelleyGenesisJson: String): List<String> {
        val genesis = json.readTree(shelleyGenesisJson)
        return when (genesis.path("networkId").asString().lowercase()) {
            "mainnet" -> listOf("--mainnet")
            "testnet" ->
                listOf(
                    "--testnet-magic",
                    genesis.path("networkMagic").asString().also { require(it.matches(Regex("[0-9]+"))) { "Invalid networkMagic" } },
                )
            else -> throw IllegalArgumentException("Network ID unavailable")
        }
    }

    private fun validateMetadataFields(
        metadata: com.swiftmako.jormanager.model.Metadata,
        targetHost: Host,
        observerHost: Host,
    ) {
        val name = requireNotNull(metadata.name) { "Pool metadata name is required" }
        val description = requireNotNull(metadata.description) { "Pool metadata description is required" }
        val ticker = requireNotNull(metadata.ticker) { "Pool metadata ticker is required" }
        val homepage = requireNotNull(metadata.homepage) { "Pool metadata homepage is required" }
        require(name.isNotBlank() && name.toByteArray(Charsets.UTF_8).size <= 50) { "Invalid pool name" }
        require(description.toByteArray(Charsets.UTF_8).size <= 255) { "Invalid pool description" }
        require(ticker.length in 3..5 && ticker.all { it.code in 33..126 }) { "Invalid pool ticker" }
        require(
            homepage.toByteArray(Charsets.UTF_8).size <= 64 &&
                URI(homepage).let { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() },
        ) { "Invalid homepage" }
        val importedSecret = metadata.extended
            ?.itn
            ?.privateKey
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val importedPublic = metadata.extended
            ?.itn
            ?.publicKey
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (importedSecret != null) {
            require(!importedPublic.isNullOrBlank()) { "ITN public key is required" }
            val itnHost = if (!targetHost.jcliPath.isNullOrBlank()) targetHost else observerHost
            val jcli = requireNotNull(itnHost.jcliPath) { "jcli is required for the ITN witness" }
            checkSafePath(jcli)
        }
    }

    private fun queryTipSlotAndEpoch(
        observerHost: Host,
        observer: Node,
        observerConnection: HostConnection,
        era: String,
        networkArgs: List<String>,
        socketPath: String,
    ): Pair<Long, Long> {
        val raw =
            observerConnection
                .commandArgs(
                    listOf(observerHost.cardanoCliPath, era, "query", "tip") +
                        networkArgs +
                        listOf("--socket-path", socketPath, "--output-json"),
                ).trim()
        val tip = json.readTree(raw)
        val slot = tip.path("slot")
        val epoch = tip.path("epoch")
        require(
            slot.isIntegralNumber &&
                slot.canConvertToLong() &&
                slot.asLong() >= 0 &&
                epoch.isIntegralNumber &&
                epoch.canConvertToLong() &&
                epoch.asLong() >= 0 &&
                tip.path("era").asString().equals(era, true) &&
                tip.path("hash").asString().matches(Regex("[0-9a-fA-F]{64}")),
        ) { "Observer tip unavailable" }
        return slot.asLong() to epoch.asLong()
    }

    fun create(request: CreateNodeRequest): Node {
        UUID.fromString(request.requestId)
        require(inFlightRequests.add(request.requestId)) { "Request ID already in progress" }
        try {
            return executeCreate(request)
        } finally {
            inFlightRequests.remove(request.requestId)
        }
    }

    private fun executeCreate(request: CreateNodeRequest): Node {
        // 1. Pre-flight checks (no DB writes, no host writes)
        require(walletUtils.isValidSpendingPassword(request.spendingPassword)) { "Authorization required" }
        require(
            request.type in setOf("core", "pool") &&
                !request.isDefault &&
                request.name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*")),
        ) { "Invalid node type or name" }
        require(request.processorThreads > 0 && request.port in 1..65535) { "Invalid processor threads or port" }
        require(
            listOfNotNull(request.listen, request.prometheusListen, request.tracingListen).all {
                it.matches(Regex("[A-Za-z0-9.:-]+"))
            },
        ) { "Invalid listen address" }
        val pledge = requireNotNull(request.poolPledge) { "Pool pledge is required" }
        val cost = requireNotNull(request.poolCost) { "Pool cost is required" }
        require(pledge.signum() >= 0 && cost.signum() >= 0) { "Invalid pool pledge or cost" }
        val margin = request.poolMargin.toBigDecimal()
        require(
            margin >= BigDecimal.ZERO &&
                margin <= BigDecimal.ONE &&
                request.poolMargin.matches(Regex("0|1|0\\.[0-9]+|1\\.0+")),
        ) { "Invalid pool margin" }
        require(request.relays.orEmpty().all { it.addr.isNotBlank() && it.port in 1..65535 }) { "Invalid pool relays" }
        val metadata = requireNotNull(request.metadata) { "Pool metadata is required" }

        val owner = requireNotNull(wallets.findByIdOrNull(requireNotNull(request.ownerStakingAccount))) { "Owner account not found" }
        val reward = requireNotNull(wallets.findByIdOrNull(requireNotNull(request.rewardsStakingAccount))) { "Reward account not found" }
        val payer = requireNotNull(wallets.findByIdOrNull(requireNotNull(request.registrationFeesAccount))) { "Fee payer account not found" }
        require(
            !owner.deleted &&
                !reward.deleted &&
                !payer.deleted &&
                owner.stakingVkey != null &&
                owner.stakingSkey != null &&
                !owner.stakingAddr.isNullOrBlank() &&
                reward.stakingVkey != null &&
                !reward.stakingAddr.isNullOrBlank() &&
                payer.paymentVkey != null &&
                payer.paymentSkey != null &&
                payer.paymentAddr.isNotBlank(),
        ) { "Selected accounts lack required keys or addresses" }

        val host = requireNotNull(hosts.findByIdOrNull(request.hostId)) { "Target host not found" }
        checkSafePath(host.nodeHomePath)
        checkSafePath(host.cardanoNodePath)
        checkSafePath(host.cardanoCliPath)

        val observer = requireNotNull(nodes.findDefault()) { "Observer not found" }
        require(!observer.isDeleted && observer.type != "pool") { "Observer unavailable" }
        val observerHost = requireNotNull(hosts.findByIdOrNull(observer.hostId)) { "Observer host not found" }
        checkSafePath(observerHost.nodeHomePath)
        checkSafePath(observerHost.cardanoCliPath)
        validateMetadataFields(metadata, host, observerHost)

        val network = eras.networkKey(observer)
        val observerShelleyContent = requireNotNull(files.findByIdOrNull(observer.genesisShelleyFileId)).content
        val networkArgs = networkArgsForGenesis(observerShelleyContent)
        val magicString = networkArgs.joinToString(" ")
        val observerSocketPath = checkSafePath("${observerHost.nodeHomePath}/${observer.name}/db/socket")
        val socketFlag = "--socket-path $observerSocketPath"
        val era = eras.getEra(observerHost, observer, magicString, socketFlag)
        require(era in setOf("conway", "dijkstra")) { "Unsupported era $era" }
        val evidence = eras.evidence(observer)
        require(
            evidence.freshness == "current" &&
                evidence.era == era &&
                evidence.networkKey == network &&
                evidence.source == "host:${observerHost.id ?: observer.hostId}/node:${observer.id}",
        ) { "Current observer era evidence unavailable" }

        val observerConnection = HostConnection(observerHost, observer)
        val (tipSlot, _) = queryTipSlotAndEpoch(observerHost, observer, observerConnection, era, networkArgs, observerSocketPath)
        val preflightProtocolRaw = protocolParameters.query(observerHost, observer, observerConnection, era, magicString, socketFlag)
        val preflightProtocol = json.readTree(preflightProtocolRaw)
        val minPoolCost = BigInteger(preflightProtocol.path("minPoolCost").asString().also { require(it.matches(decimal)) })
        require(cost >= minPoolCost) { "Pool cost is below the current minimum" }
        val preflightUtxos = walletUtils.getUtxos(observerHost, observerConnection, magicString, socketFlag, era, payer.paymentAddr)
        require(preflightUtxos.isNotEmpty()) { "Available fee payer inputs unavailable" }

        val parent =
            if (request.type == "pool") {
                val parentNode = requireNotNull(request.parentId?.let { nodes.findByIdOrNull(it) }) { "Parent core node not found" }
                require(
                    !parentNode.isDeleted &&
                        parentNode.type == "core" &&
                        parentNode.hostId == host.id &&
                        eras.networkKey(parentNode) == network,
                ) { "Parent core node is unavailable" }
                parentNode
            } else {
                require(request.parentId == null) { "Standalone core node cannot specify a parent" }
                null
            }

        val targetConnection = HostConnection(host, parent)
        val assets =
            if (parent == null) {
                configurationFiles.resolve(request)
            } else {
                val parentFolder = "${host.nodeHomePath}/${parent.name}"
                val ids =
                    mapOf(
                        "byron-genesis.json" to parent.genesisByronFileId,
                        "shelley-genesis.json" to parent.genesisShelleyFileId,
                        "alonzo-genesis.json" to parent.genesisAlonzoFileId,
                        "conway-genesis.json" to parent.genesisConwayFileId,
                    ) + listOfNotNull(parent.genesisDijkstraFileId?.let { "dijkstra-genesis.json" to it }).toMap()
                val inherited = ids.mapValues { requireNotNull(files.findByIdOrNull(it.value)).content.toByteArray(Charsets.UTF_8) }
                val remote =
                    listOf("config.json", "topology.json", "peer-snapshot.json", "checkpoints.json")
                        .filter { it in setOf("config.json", "topology.json") || targetConnection.commandFileExists("$parentFolder/$it") }
                        .associateWith { targetConnection.commandReadFile("$parentFolder/$it").toByteArray(Charsets.UTF_8) }
                inherited + remote
            }
        configurationFiles.validate(assets)

        val observedGenesis =
            mapOf(
                "byron-genesis.json" to observer.genesisByronFileId,
                "shelley-genesis.json" to observer.genesisShelleyFileId,
                "alonzo-genesis.json" to observer.genesisAlonzoFileId,
                "conway-genesis.json" to observer.genesisConwayFileId,
            ) + listOfNotNull(observer.genesisDijkstraFileId?.let { "dijkstra-genesis.json" to it }).toMap()
        require(assets.keys.filter { it.endsWith("-genesis.json") }.toSet() == observedGenesis.keys) {
            "Selected genesis files do not match observer era"
        }
        observedGenesis.forEach { (name, id) ->
            require(assets.getValue(name).contentEquals(requireNotNull(files.findByIdOrNull(id)).content.toByteArray(Charsets.UTF_8))) {
                "Genesis differs from observer"
            }
        }

        fun portUsed(port: Int): Boolean = Regex("[:.]$port(?:\\s|$)").containsMatchIn(targetConnection.commandArgs(listOf("ss", "-tulw")))

        if (parent == null) {
            require(!portUsed(request.port)) { "Reviewed port is already in use" }
        }
        val promPort = parent?.promPort ?: nodeController.allocatePrometheusPort(request.hostId, targetConnection)
        if (parent == null) {
            require(promPort in 1..65535 && promPort != request.port) { "Prometheus and node ports conflict" }
        }
        val tracingPort =
            parent?.tracingPort
                ?: if (request.enableTracingListener) {
                    nodeController.allocateTracingPort(promPort) { it == request.port || portUsed(it) }
                } else {
                    null
                }
        val config =
            if (parent != null) {
                targetConnection.commandReadFile("${host.nodeHomePath}/${parent.name}/config.json")
            } else {
                nodeController.renderManagedConfig(
                    assets.getValue("config.json").toString(Charsets.UTF_8),
                    request.type,
                    "2",
                    false,
                    request.prometheusListen,
                    promPort,
                )
            }
        val targetSpec =
            PoolDeploymentService.TargetSpec(
                host = host,
                parent = parent,
                name = request.name,
                type = request.type,
                processorThreads = request.processorThreads,
                listen = request.listen,
                port = request.port,
                promPort = promPort,
                tracingHost = request.tracingListen,
                tracingPort = tracingPort,
                assets = assets,
                config = config,
            )
        installer.validateTarget(
            spec = targetSpec,
            spendingPassword = request.spendingPassword,
            sudoPassword = request.sudoPassword,
            verifySudo = true,
        )

        // 2. Key, BLS, and operational certificate preparation in a temporary host directory
        val preparedKeys =
            prepareKeysAndOpcert(
                request = request,
                host = host,
                targetConnection = targetConnection,
                targetSpec = targetSpec,
                observerHost = observerHost,
                observer = observer,
                observerConnection = observerConnection,
                era = era,
                networkArgs = networkArgs,
                observerSocketPath = observerSocketPath,
                tipSlot = tipSlot,
                shelleyGenesisBytes = assets.getValue("shelley-genesis.json"),
                ownerStakingVKeyContent = requireNotNull(owner.stakingVkey).content,
                rewardStakingVKeyContent = requireNotNull(reward.stakingVkey).content,
            )

        // 3. Metadata publication & on-chain initial pool registration
        val publishedMetadata =
            nodeController.publishPoolMetadataMaterial(
                poolId = preparedKeys.poolId,
                targetHostId = requireNotNull(host.id),
                observer = observer,
                metadata = metadata,
                spendingPassword = request.spendingPassword,
            )

        val txId =
            submitInitialRegistration(
                request = request,
                observerHost = observerHost,
                observer = observer,
                observerConnection = observerConnection,
                era = era,
                networkArgs = networkArgs,
                magicString = magicString,
                observerSocketPath = observerSocketPath,
                socketFlag = socketFlag,
                ownerAccountId = requireNotNull(owner.id),
                rewardAccountId = requireNotNull(reward.id),
                payerAccountId = requireNotNull(payer.id),
                preparedKeys = preparedKeys,
                publishedMetadata = publishedMetadata,
            )

        // 4. Host installation & service startup (with automatic host rollback on failure)
        val credentialMaterial =
            PoolDeploymentService.CredentialMaterial(
                kesSKey = preparedKeys.kesSKey,
                vrfSKey = preparedKeys.vrfSKey,
                opcert = preparedKeys.opcert,
                blsSKey = preparedKeys.bls?.signingKeyEnvelope,
                blsPublicKey = preparedKeys.bls?.publicKey,
            )
        val deployed =
            try {
                installer.deploy(
                    spec = targetSpec,
                    credentials = credentialMaterial,
                    spendingPassword = request.spendingPassword,
                    sudoPassword = request.sudoPassword,
                    operationId = request.requestId,
                    deferBackupCleanup = true,
                )
            } catch (e: Throwable) {
                throw PoolCreationFailure(
                    "Registration transaction $txId was submitted, but host setup failed and was rolled back.",
                    "retry",
                    e,
                )
            }

        // 5. Single-transaction database persistence
        val savedNode =
            try {
                requireNotNull(
                    transaction.execute {
                        fun saveFile(
                            name: String,
                            content: String
                        ): Long = requireNotNull(files.save(File(name = name, content = content)).id)

                        fun saveSecret(
                            name: String,
                            content: String
                        ): Long = saveFile(name, walletUtils.encryptSKeyContent(content, request.spendingPassword))

                        fun saveAsset(suffix: String): Long = saveFile("${request.name}-$suffix", assets.getValue(suffix).toString(Charsets.UTF_8))

                        val byronId = saveAsset("byron-genesis.json")
                        val shelleyId = saveAsset("shelley-genesis.json")
                        val alonzoId = saveAsset("alonzo-genesis.json")
                        val conwayId = saveAsset("conway-genesis.json")
                        val dijkstraId = if ("dijkstra-genesis.json" in assets) saveAsset("dijkstra-genesis.json") else null
                        val configId = saveFile("${request.name}-config.json", config)
                        val topologyId = saveAsset("topology.json")
                        val peerSnapshotId = if ("peer-snapshot.json" in assets) saveAsset("peer-snapshot.json") else null

                        val coreSKeyId = saveSecret("${request.name}.node.skey", preparedKeys.coldSKey)
                        val coreVKeyId = saveFile("${request.name}.node.vkey", preparedKeys.coldVKey)
                        val coreCounterId = saveFile("${request.name}.node.counter", preparedKeys.advancedCounter)
                        val vrfSKeyId = saveSecret("${request.name}.vrf.skey", preparedKeys.vrfSKey)
                        val vrfVKeyId = saveFile("${request.name}.vrf.vkey", preparedKeys.vrfVKey)
                        val kesSKeyId = saveSecret("${request.name}.kes.skey", preparedKeys.kesSKey)
                        val kesVKeyId = saveFile("${request.name}.kes.vkey", preparedKeys.kesVKey)
                        val opcertId = saveFile("${request.name}.node.opcert", preparedKeys.opcert)

                        val itnPrivateKeyId =
                            publishedMetadata.encryptedItnPrivateKey?.let { saveFile("${request.name}.itn.skey", it) } ?: -1L
                        val itnPublicKeyId =
                            publishedMetadata.itnPublicKey?.let { saveFile("${request.name}.itn.vkey", it) } ?: -1L

                        val persistedNode =
                            nodes.save(
                                Node(
                                    hostId = request.hostId,
                                    parentId = parent?.id,
                                    color = request.color,
                                    type = request.type,
                                    processorThreads = request.processorThreads,
                                    name = request.name,
                                    listen = request.listen,
                                    port = request.port,
                                    promPort = promPort,
                                    tracingHost = request.tracingListen,
                                    tracingPort = tracingPort,
                                    genesisByronFileId = byronId,
                                    genesisShelleyFileId = shelleyId,
                                    genesisAlonzoFileId = alonzoId,
                                    genesisConwayFileId = conwayId,
                                    genesisDijkstraFileId = dijkstraId,
                                    configFileId = configId,
                                    topologyFileId = topologyId,
                                    peerSnapshotFileId = peerSnapshotId,
                                    networkKeyDiscriminator = observer.networkKeyDiscriminator,
                                    isDefault = false,
                                    poolId = preparedKeys.poolId,
                                    poolPledge = pledge,
                                    poolCost = cost,
                                    poolMargin = request.poolMargin,
                                    ownerStakingAccountId = requireNotNull(owner.id),
                                    rewardsStakingAccountId = requireNotNull(reward.id),
                                    coreSKeyId = coreSKeyId,
                                    coreVKeyId = coreVKeyId,
                                    coreCounterId = coreCounterId,
                                    vrfSKeyId = vrfSKeyId,
                                    vrfVKeyId = vrfVKeyId,
                                    kesSKeyId = kesSKeyId,
                                    kesVKeyId = kesVKeyId,
                                    opcertId = opcertId,
                                    kesExpireTimeSec = deployed.kesExpireTimeSec ?: -1,
                                    kesExpireDate = deployed.kesExpireDate ?: "---",
                                    metadataUrl = publishedMetadata.url,
                                    extendedMetadataUrl = publishedMetadata.extendedUrl,
                                    itnPrivateKeyId = itnPrivateKeyId,
                                    itnPublicKeyId = itnPublicKeyId,
                                ),
                            )
                        require(eras.networkKey(persistedNode) == network) {
                            "Persisted node configuration does not match observer network"
                        }
                        val nodeId = requireNotNull(persistedNode.id)
                        val persistedRelays =
                            relays.saveAll(
                                request.relays.orEmpty().map { Relay(nodeId = nodeId, addr = it.addr, port = it.port) },
                            )

                        if (preparedKeys.bls != null) {
                            val blsFileId =
                                saveFile(
                                    "leios-$nodeId-${UUID.randomUUID()}.skey",
                                    preparedKeys.bls.encryptedSigningKey,
                                )
                            val savedKey =
                                keys.save(
                                    LeiosVotingKey(
                                        networkKey = network,
                                        poolId = preparedKeys.poolId,
                                        publicKey = preparedKeys.bls.publicKey,
                                        proof = preparedKeys.bls.proof,
                                        signingFileId = blsFileId,
                                        preparedAt = Instant.now(),
                                        validationProvenance = preparedKeys.bls.provenance,
                                    ),
                                )
                            installations.save(
                                KeyInstallation(
                                    keyId = requireNotNull(savedKey.id),
                                    nodeId = nodeId,
                                    hostId = request.hostId,
                                    configurationFingerprint = deployed.configurationFingerprint,
                                    installedFingerprint = deployed.installedFingerprint,
                                    permissionResult = "owner:600",
                                    state = "installed",
                                    observedAt = Instant.now(),
                                ),
                            )
                        }

                        persistedNode.copy(relays = persistedRelays.toList())
                    },
                )
            } catch (e: Throwable) {
                deployed.rollback()
                throw PoolCreationFailure(
                    "Registration transaction $txId was submitted, but database persistence failed and host changes were rolled back.",
                    "retry",
                    e,
                )
            } finally {
                deployed.cleanup()
            }

        nodesChannel.tryEmit(savedNode)
        return savedNode
    }

    private fun prepareKeysAndOpcert(
        request: CreateNodeRequest,
        host: Host,
        targetConnection: HostConnection,
        targetSpec: PoolDeploymentService.TargetSpec,
        observerHost: Host,
        observer: Node,
        observerConnection: HostConnection,
        era: String,
        networkArgs: List<String>,
        observerSocketPath: String,
        tipSlot: Long,
        shelleyGenesisBytes: ByteArray,
        ownerStakingVKeyContent: String,
        rewardStakingVKeyContent: String,
    ): PreparedKeys {
        val directory = targetConnection.commandArgs(listOf("mktemp", "-d", "/tmp/jormanager-pool-XXXXXXXXXX")).trim()
        require(directory.matches(Regex("/tmp/jormanager-pool-[A-Za-z0-9]+"))) { "Invalid temporary directory" }
        try {
            fun runCli(vararg args: String): String = targetConnection.commandArgs(listOf(host.cardanoCliPath, era) + args)

            fun readEnvelope(path: String): String = targetConnection.commandArgs(listOf("cat", path))

            fun keyBytes(content: String): ByteArray {
                val hex = json.readTree(content).path("cborHex").asString()
                require(hex.isNotEmpty() && hex.length % 2 == 0 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                    "Invalid key envelope cborHex"
                }
                return CborSpans.parse(hex.hexToByteArray()).payload()
            }

            fun keyPair(
                name: String,
                generated: Boolean,
                suppliedSecret: String?,
                suppliedPublic: String?,
                command: String,
            ): Pair<String, String> {
                val secret = "$directory/$name.skey"
                val public = "$directory/$name.vkey"
                if (generated) {
                    require(suppliedSecret.isNullOrBlank() && suppliedPublic.isNullOrBlank()) {
                        "Generated $name keys must not supply key material"
                    }
                    if (name == "cold") {
                        runCli(
                            "node",
                            command,
                            "--verification-key-file",
                            public,
                            "--signing-key-file",
                            secret,
                            "--operational-certificate-issue-counter",
                            "$directory/cold.counter",
                        )
                    } else {
                        runCli("node", command, "--verification-key-file", public, "--signing-key-file", secret)
                    }
                    targetConnection.commandArgs(listOf("chmod", "600", secret))
                } else {
                    val skey = requireNotNull(suppliedSecret) { "Missing $name signing key" }.trim()
                    val vkey = requireNotNull(suppliedPublic) { "Missing $name verification key" }.trim()
                    require(skey.isNotEmpty() && skey.length <= 16384 && vkey.isNotEmpty() && vkey.length <= 16384) {
                        "Invalid $name key length"
                    }
                    targetConnection.commandWriteSecret(secret, skey)
                    targetConnection.commandArgs(listOf("chmod", "600", secret))
                    targetConnection.commandWriteFile(public, vkey)
                }
                runCli("key", "verification-key", "--signing-key-file", secret, "--verification-key-file", "$directory/$name.derived.vkey")
                val skey = readEnvelope(secret)
                val vkey = readEnvelope(public)
                require(keyBytes(vkey).contentEquals(keyBytes(readEnvelope("$directory/$name.derived.vkey")))) {
                    "Key pair mismatch"
                }
                return skey to vkey
            }

            val cold = keyPair("cold", request.generateColdKeys, request.coldSKey, request.coldVKey, "key-gen")
            val vrf = keyPair("vrf", request.generateVRFKeys, request.vrfSKey, request.vrfVKey, "key-gen-VRF")
            val kes = keyPair("kes", request.generateKESKeys, request.kesSKey, request.kesVKey, "key-gen-KES")

            if (!request.generateColdKeys) {
                val suppliedCounter = requireNotNull(request.coldCounter) { "Missing cold operational certificate counter" }.trim()
                require(suppliedCounter.isNotEmpty() && suppliedCounter.length <= 16384) { "Invalid cold counter length" }
                targetConnection.commandWriteFile("$directory/cold.counter", suppliedCounter)
            }
            val initialCounter = readEnvelope("$directory/cold.counter")
            val counterHex = json.readTree(initialCounter).path("cborHex").asString()
            require(counterHex.isNotEmpty() && counterHex.length % 2 == 0 && counterHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                "Invalid cold counter cborHex"
            }
            val counterItems = CborSpans.parse(counterHex.hexToByteArray()).array()
            require(
                counterItems.size == 2 &&
                    counterItems[0].integer().signum() >= 0 &&
                    counterItems[1].payload().contentEquals(keyBytes(cold.second)),
            ) { "Cold counter does not match cold verification key" }

            if (targetSpec.parent != null) {
                installer.validateTarget(
                    spec = targetSpec,
                    spendingPassword = request.spendingPassword,
                    sudoPassword = request.sudoPassword,
                    verifySudo = false,
                    candidateVrfSKey = vrf.first,
                )
            }

            val poolId = runCli("stake-pool", "id", "--cold-verification-key-file", "$directory/cold.vkey", "--output-hex").trim().lowercase()
            require(poolId.matches(Regex("[0-9a-f]{56}"))) { "Invalid derived pool ID" }
            require(nodes.findByPoolId(poolId) == null) { "Pool is already managed by an active node" }
            val existingPoolState =
                json.readTree(
                    observerConnection
                        .commandArgs(
                            listOf(observerHost.cardanoCliPath, era, "query", "pool-state", "--stake-pool-id", poolId) +
                                networkArgs +
                                listOf("--socket-path", observerSocketPath, "--output-json"),
                        ).trim(),
                )
            require(existingPoolState.isObject && existingPoolState.isEmpty) {
                "Pool is already registered on chain"
            }

            val bls =
                if (era == "dijkstra") {
                    val blsSkeyPath = "$directory/bls.skey"
                    val blsGeneratedVkeyPath = "$directory/bls.generated.vkey"
                    val blsDerivedVkeyPath = "$directory/bls.derived.vkey"
                    runCli("node", "key-gen-BLS", "--verification-key-file", blsGeneratedVkeyPath, "--signing-key-file", blsSkeyPath)
                    targetConnection.commandArgs(listOf("chmod", "600", blsSkeyPath))
                    runCli("key", "verification-key", "--signing-key-file", blsSkeyPath, "--verification-key-file", blsDerivedVkeyPath)
                    val derivedPublic = keyBytes(readEnvelope(blsDerivedVkeyPath))
                    require(derivedPublic.size == 96 && derivedPublic.contentEquals(keyBytes(readEnvelope(blsGeneratedVkeyPath)))) {
                        "Derived BLS verification key mismatch"
                    }
                    val blsSecretEnvelope = readEnvelope(blsSkeyPath)
                    require(installer.isValidBlsSigningEnvelope(json.readTree(blsSecretEnvelope))) {
                        "Generated BLS signing key has invalid envelope"
                    }
                    targetConnection.commandWriteFile("$directory/owner.vkey", ownerStakingVKeyContent)
                    targetConnection.commandWriteFile("$directory/reward.vkey", rewardStakingVKeyContent)
                    targetConnection.commandArgs(
                        listOf(
                            host.cardanoCliPath,
                            "dijkstra",
                            "stake-pool",
                            "registration-certificate",
                            "--cold-verification-key-file",
                            "$directory/cold.vkey",
                            "--vrf-verification-key-file",
                            "$directory/vrf.vkey",
                            "--bls-signing-key-file",
                            blsSkeyPath,
                            "--pool-pledge",
                            "0",
                            "--pool-cost",
                            "0",
                            "--pool-margin",
                            "0",
                            "--pool-reward-account-verification-key-file",
                            "$directory/reward.vkey",
                            "--pool-owner-stake-verification-key-file",
                            "$directory/owner.vkey",
                        ) + networkArgs + listOf("--out-file", "$directory/bls-check.cert"),
                    )
                    val certHex = json.readTree(readEnvelope("$directory/bls-check.cert")).path("cborHex").asString()
                    require(certHex.isNotEmpty() && certHex.length % 2 == 0 && certHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                        "Invalid BLS test certificate cborHex"
                    }
                    val certArray = CborSpans.parse(certHex.hexToByteArray()).array()
                    require(certArray.size == 11 && certArray[0].integer().toInt() == 3) { "Unexpected Dijkstra pool certificate structure" }
                    val blsParts = certArray[3].array()
                    require(blsParts.size == 2) { "Missing BLS certificate fields" }
                    val certPublic = blsParts[0].payload()
                    val certProof = blsParts[1].payload()
                    require(certPublic.size == 96 && certProof.size == 48 && certPublic.contentEquals(derivedPublic)) {
                        "BLS proof verification failed"
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    digest.update(certPublic)
                    val provenance = "cli-derived-key-and-certificate:" + digest.digest(certProof).toHexString()
                    PreparedBlsKey(
                        signingKeyEnvelope = blsSecretEnvelope,
                        encryptedSigningKey = walletUtils.encryptSKeyContent(blsSecretEnvelope, request.spendingPassword),
                        publicKey = certPublic,
                        proof = certProof,
                        provenance = provenance,
                    )
                } else {
                    null
                }

            val shelleyGenesis = json.readTree(shelleyGenesisBytes.toString(Charsets.UTF_8))
            val slotsPerKesPeriod = shelleyGenesis.path("slotsPerKESPeriod").asLong(0L)
            require(slotsPerKesPeriod > 0L && tipSlot >= 0L) { "Invalid KES period parameters" }
            runCli(
                "node",
                "issue-op-cert",
                "--hot-kes-verification-key-file",
                "$directory/kes.vkey",
                "--cold-signing-key-file",
                "$directory/cold.skey",
                "--operational-certificate-issue-counter",
                "$directory/cold.counter",
                "--kes-period",
                (tipSlot / slotsPerKesPeriod).toString(),
                "--out-file",
                "$directory/node.opcert",
            )
            val advancedCounter = readEnvelope("$directory/cold.counter")
            val opcert = readEnvelope("$directory/node.opcert")
            require(
                json
                    .readTree(opcert)
                    .path("cborHex")
                    .asString()
                    .isNotBlank()
            ) { "Issued operational certificate is empty" }

            return PreparedKeys(
                poolId = poolId,
                coldSKey = cold.first,
                coldVKey = cold.second,
                advancedCounter = advancedCounter,
                vrfSKey = vrf.first,
                vrfVKey = vrf.second,
                kesSKey = kes.first,
                kesVKey = kes.second,
                opcert = opcert,
                bls = bls,
            )
        } finally {
            runCatching { targetConnection.commandArgs(listOf("rm", "-rf", directory)) }
        }
    }

    private fun submitInitialRegistration(
        request: CreateNodeRequest,
        observerHost: Host,
        observer: Node,
        observerConnection: HostConnection,
        era: String,
        networkArgs: List<String>,
        magicString: String,
        observerSocketPath: String,
        socketFlag: String,
        ownerAccountId: Long,
        rewardAccountId: Long,
        payerAccountId: Long,
        preparedKeys: PreparedKeys,
        publishedMetadata: NodeController.PreparedPoolMetadata,
    ): String =
        ledger.withSubmissionLock {
            val owner = requireNotNull(wallets.findByIdOrNull(ownerAccountId)) { "Owner account not found" }
            val reward = requireNotNull(wallets.findByIdOrNull(rewardAccountId)) { "Reward account not found" }
            val payer = requireNotNull(wallets.findByIdOrNull(payerAccountId)) { "Fee payer account not found" }
            require(!owner.deleted && !reward.deleted && !payer.deleted) { "Selected accounts unavailable" }

            fun queryObserver(vararg args: String): JsonNode =
                json.readTree(
                    observerConnection
                        .commandArgs(
                            listOf(observerHost.cardanoCliPath, era, "query") +
                                args +
                                networkArgs +
                                listOf("--socket-path", observerSocketPath, "--output-json"),
                        ).trim(),
                )

            val (slot, _) = queryTipSlotAndEpoch(observerHost, observer, observerConnection, era, networkArgs, observerSocketPath)
            val validUntil = Math.addExact(slot, 21600L)
            val protocolRaw = protocolParameters.query(observerHost, observer, observerConnection, era, magicString, socketFlag)
            val protocol = json.readTree(protocolRaw)
            val minPoolCost = BigInteger(protocol.path("minPoolCost").asString().also { require(it.matches(decimal)) })
            val pledge = requireNotNull(request.poolPledge)
            val cost = requireNotNull(request.poolCost)
            require(cost >= minPoolCost) { "Pool cost is below the current minimum" }
            val poolDeposit = BigInteger(protocol.path("stakePoolDeposit").asString().also { require(it.matches(decimal)) })
            val stakeDepositPerAccount = BigInteger(protocol.path("stakeAddressDeposit").asString().also { require(it.matches(decimal)) })

            val poolState = queryObserver("pool-state", "--stake-pool-id", preparedKeys.poolId)
            require(poolState.isObject && poolState.isEmpty) { "Pool is already registered on chain" }

            val inputs = walletUtils.getUtxos(observerHost, observerConnection, magicString, socketFlag, era, payer.paymentAddr)
            require(inputs.isNotEmpty()) { "Available fee payer inputs unavailable" }

            val prefix = "/tmp/jormanager-pool-registration-${UUID.randomUUID()}"
            val cleanupPaths = mutableListOf<String>()

            fun writeTemp(
                suffix: String,
                value: String,
                secret: Boolean = false
            ): String {
                val path = "$prefix.$suffix"
                cleanupPaths += path
                if (secret) {
                    observerConnection.commandWriteSecret(path, value)
                } else {
                    observerConnection.commandWriteFile(path, value)
                }
                return path
            }

            try {
                val coldVKeyPath = writeTemp("cold.vkey", preparedKeys.coldVKey)
                val vrfVKeyPath = writeTemp("vrf.vkey", preparedKeys.vrfVKey)
                val ownerVKeyPath = writeTemp("owner.vkey", requireNotNull(owner.stakingVkey).content)
                val rewardVKeyPath = writeTemp("reward.vkey", requireNotNull(reward.stakingVkey).content)

                val stakeIds = listOf(ownerAccountId, rewardAccountId).distinct().sorted()
                val registrationCerts = mutableListOf<String>()
                val extraStakingSkeyFiles = linkedMapOf<Long, File>()
                var stakeDeposit = BigInteger.ZERO
                stakeIds.forEach { accountId ->
                    val account = requireNotNull(wallets.findByIdOrNull(accountId))
                    val stakeAddr = requireNotNull(account.stakingAddr)
                    val info = queryObserver("stake-address-info", "--address", stakeAddr)
                    require(info.isArray && info.size() <= 1) { "Stake address state unavailable" }
                    if (info.isEmpty) {
                        val vkeyPath = writeTemp("stake-$accountId.vkey", requireNotNull(account.stakingVkey).content)
                        val certPath = "$prefix.stake-$accountId.cert"
                        cleanupPaths += certPath
                        observerConnection.commandArgs(
                            listOf(
                                observerHost.cardanoCliPath,
                                era,
                                "stake-address",
                                "registration-certificate",
                                "--stake-verification-key-file",
                                vkeyPath,
                                "--key-reg-deposit-amt",
                                stakeDepositPerAccount.toString(),
                                "--out-file",
                                certPath,
                            ),
                        )
                        registrationCerts += certPath
                        val skeyFile = requireNotNull(account.stakingSkey) { "Stake signing key unavailable" }
                        extraStakingSkeyFiles[requireNotNull(skeyFile.id)] = skeyFile
                        stakeDeposit += stakeDepositPerAccount
                    }
                }

                val poolCertCommand =
                    mutableListOf(
                        observerHost.cardanoCliPath,
                        era,
                        "stake-pool",
                        "registration-certificate",
                        "--cold-verification-key-file",
                        coldVKeyPath,
                        "--vrf-verification-key-file",
                        vrfVKeyPath,
                        "--pool-pledge",
                        pledge.toString(),
                        "--pool-cost",
                        cost.toString(),
                        "--pool-margin",
                        request.poolMargin,
                        "--pool-reward-account-verification-key-file",
                        rewardVKeyPath,
                        "--pool-owner-stake-verification-key-file",
                        ownerVKeyPath,
                    )
                if (era == "dijkstra") {
                    val bls = requireNotNull(preparedKeys.bls) { "BLS key required for Dijkstra pool registration" }
                    poolCertCommand += listOf("--bls-signing-key-file", writeTemp("bls.skey", bls.signingKeyEnvelope, secret = true))
                }
                request.relays.orEmpty().forEach { relay ->
                    val addr = relay.addr.trim()
                    val relayFlag =
                        when {
                            addr.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) -> "--pool-relay-ipv4"
                            addr.contains(':') -> "--pool-relay-ipv6"
                            else -> "--single-host-pool-relay"
                        }
                    poolCertCommand += listOf(relayFlag, addr, "--pool-relay-port", relay.port.toString())
                }
                poolCertCommand += listOf("--metadata-url", publishedMetadata.url, "--metadata-hash", publishedMetadata.hash)
                val poolCertPath = "$prefix.pool.cert"
                cleanupPaths += poolCertPath
                observerConnection.commandArgs(poolCertCommand + networkArgs + listOf("--out-file", poolCertPath))

                if (era == "dijkstra") {
                    val bls = requireNotNull(preparedKeys.bls)
                    val certHex = json.readTree(observerConnection.commandArgs(listOf("cat", poolCertPath))).path("cborHex").asString()
                    require(certHex.matches(Regex("[0-9a-fA-F]+")) && certHex.length % 2 == 0) { "Invalid pool certificate cborHex" }
                    val parts = CborSpans.parse(certHex.hexToByteArray()).array()
                    require(parts.size == 11 && parts[0].integer() == BigInteger.valueOf(3)) { "Invalid Dijkstra pool certificate" }
                    val blsNode = parts[3].array()
                    require(
                        blsNode.size == 2 &&
                            blsNode[0].payload().contentEquals(bls.publicKey) &&
                            blsNode[1].payload().contentEquals(bls.proof),
                    ) { "Generated BLS proof does not match prepared key" }
                }

                val delegationCertPath = "$prefix.delegate-$ownerAccountId.cert"
                cleanupPaths += delegationCertPath
                observerConnection.commandArgs(
                    listOf(
                        observerHost.cardanoCliPath,
                        era,
                        "stake-address",
                        "stake-delegation-certificate",
                        "--stake-verification-key-file",
                        ownerVKeyPath,
                        "--cold-verification-key-file",
                        coldVKeyPath,
                        "--out-file",
                        delegationCertPath,
                    ),
                )
                val ownerSkeyFile = requireNotNull(owner.stakingSkey)
                extraStakingSkeyFiles[requireNotNull(ownerSkeyFile.id)] = ownerSkeyFile

                val protocolPath = writeTemp("protocol.json", protocolRaw)
                val bodyPath = "$prefix.body"
                cleanupPaths += bodyPath
                val allCerts = registrationCerts + poolCertPath + delegationCertPath
                // Signers: fee payer payment skey + cold skey + distinct staking skeys
                val witnessCount = 2 + extraStakingSkeyFiles.size
                val built =
                    txBuilder.build(
                        host = observerHost,
                        connection = observerConnection,
                        era = era,
                        inputs = inputs,
                        outputs = emptyList(),
                        changeAddress = payer.paymentAddr,
                        deposits = poolDeposit + stakeDeposit,
                        extraArgs = allCerts.flatMap { listOf("--certificate-file", it) },
                        protocolParameters = protocolRaw,
                        protocolPath = protocolPath,
                        witnessCount = witnessCount,
                        bodyPath = bodyPath,
                        validUntil = validUntil,
                        referenceScriptSize =
                            walletUtils.queryReferenceScriptSize(observerHost, observerConnection, magicString, socketFlag, era, inputs),
                    )

                val signingKeyPaths = mutableListOf<String>()
                signingKeyPaths += writeTemp("0.skey", walletUtils.getSKeyContent(requireNotNull(payer.paymentSkey), request.spendingPassword), secret = true)
                signingKeyPaths += writeTemp("1.skey", preparedKeys.coldSKey, secret = true)
                extraStakingSkeyFiles.entries.sortedBy { it.key }.forEachIndexed { idx, (_, skeyFile) ->
                    signingKeyPaths += writeTemp("${idx + 2}.skey", walletUtils.getSKeyContent(skeyFile, request.spendingPassword), secret = true)
                }

                val signedPath = "$prefix.signed"
                cleanupPaths += signedPath
                val signArgs = mutableListOf(observerHost.cardanoCliPath, era, "transaction", "sign", "--tx-body-file", bodyPath)
                signingKeyPaths.forEach { signArgs += listOf("--signing-key-file", it) }
                signArgs += networkArgs
                signArgs += listOf("--out-file", signedPath)
                observerConnection.commandArgs(signArgs)

                val signedEnvelope = observerConnection.commandReadFile(signedPath)
                require(
                    json
                        .readTree(signedEnvelope)
                        .path("cborHex")
                        .asString()
                        .isNotBlank()
                ) { "Signed transaction envelope is empty" }
                observerConnection.commandArgs(
                    listOf(observerHost.cardanoCliPath, era, "transaction", "submit", "--tx-file", signedPath) +
                        networkArgs +
                        listOf("--socket-path", observerSocketPath),
                )
                ledger.recordSubmitted(observerHost, observer, era, built.txId, signedEnvelope)
                built.txId
            } finally {
                if (cleanupPaths.isNotEmpty()) {
                    runCatching { observerConnection.commandArgs(listOf("rm", "-f") + cleanupPaths) }
                }
            }
        }
}
