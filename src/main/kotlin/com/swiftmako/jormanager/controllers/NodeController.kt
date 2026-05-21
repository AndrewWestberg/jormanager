package com.swiftmako.jormanager.controllers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.Relay
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.entities.Transaction
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.sumByBigInteger
import com.swiftmako.jormanager.model.CreateNodeRequest
import com.swiftmako.jormanager.model.EditPoolConfigRequest
import com.swiftmako.jormanager.model.EditRelaysRequest
import com.swiftmako.jormanager.model.GenesisByron
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.model.ProtocolParameters
import com.swiftmako.jormanager.model.QueryTip
import com.swiftmako.jormanager.model.RetirePoolRequest
import com.swiftmako.jormanager.model.GovernanceVoteRequest
import com.swiftmako.jormanager.model.RotateKesRequest
import com.swiftmako.jormanager.model.UpdateColorRequest
import com.swiftmako.jormanager.model.UpdateMetadataRequest
import com.swiftmako.jormanager.model.key.Key
import com.swiftmako.jormanager.model.metadata.pool.About
import com.swiftmako.jormanager.model.metadata.pool.Company
import com.swiftmako.jormanager.model.metadata.pool.ExtendedMetadata
import com.swiftmako.jormanager.model.metadata.pool.Info
import com.swiftmako.jormanager.model.metadata.pool.Itn
import com.swiftmako.jormanager.model.metadata.pool.Metadata
import com.swiftmako.jormanager.model.metadata.pool.Social
import com.swiftmako.jormanager.model.toNativeAssetMap
import com.swiftmako.jormanager.model.tx.TxSigned
import com.swiftmako.jormanager.repositories.CardanoRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.RelayRepository
import com.swiftmako.jormanager.repositories.TransactionRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.services.MetadataService
import com.swiftmako.jormanager.utils.Bech32
import com.swiftmako.jormanager.utils.CardanoNetworkEpochs
import com.swiftmako.jormanager.utils.CardanoUtils
import com.swiftmako.jormanager.utils.TransactionCache
import java.io.File
import java.io.IOException
import java.math.BigInteger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_SINGLETON
import org.springframework.context.annotation.Scope
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import retrofit2.Retrofit

@Controller
@Scope(SCOPE_SINGLETON)
class NodeController
    @Autowired
    constructor(
        private val nodeRepository: NodeRepository,
        private val hostRepository: HostRepository,
        private val fileRepository: FileRepository,
        private val walletRepository: WalletRepository,
        private val transactionRepository: TransactionRepository,
        private val relayRepository: RelayRepository,
        private val walletUtils: WalletUtils,
        private val webSocketTemplate: SimpMessagingTemplate,
        @param:Qualifier("nodesChannel") private val nodesChannel: MutableSharedFlow<Node>,
        private val retrofit: Retrofit,
        private val okHttpClient: OkHttpClient,
        private val shelleyGenesisAdapter: JsonAdapter<GenesisShelley>,
        private val byronGenesisAdapter: JsonAdapter<GenesisByron>,
        private val protocolParamsAdapter: JsonAdapter<ProtocolParameters>,
        private val queryTipAdapter: JsonAdapter<QueryTip>,
        private val extendedMetadataAdapter: JsonAdapter<ExtendedMetadata>,
        private val metadataAdapter: JsonAdapter<Metadata>,
        private val keyFileJsonAdapter: JsonAdapter<Key>,
        private val bulkCredentialsJsonAdapter: JsonAdapter<MutableList<MutableList<Key>>>,
        private val txSignedAdapter: JsonAdapter<TxSigned>,
        private val ledgerDao: LedgerDao,
        private val cardanoUtils: CardanoUtils,
        private val cardanoRepository: CardanoRepository,
        // @param:Value("\${jormanager.era}") private val eraString: String,
    ) {
        private val log by lazy { LoggerFactory.getLogger("NodeController") }
        private val objectMapper by lazy {
            ObjectMapper().apply {
                enable(SerializationFeature.INDENT_OUTPUT)
            }
        }

        @MessageMapping("/nodes")
        @SendTo("/topic/messages")
        fun getNodes(): SocketResponse<List<Node>> {
            val nodes = nodeRepository.findAll(Sort.by(Sort.Direction.ASC, "name")).filter { !it.isDeleted }
            return SocketResponse.Success(type = "nodes", data = nodes)
        }

        @MessageMapping("/restartnode")
        @SendTo("/topic/messages")
        @Synchronized
        fun restartNode(nodeId: Long): SocketResponse<String> =
            try {
                val node = nodeRepository.findByIdOrNull(nodeId) ?: throw IllegalArgumentException("Node not found!")
                val host = hostRepository.findByIdOrNull(node.hostId) ?: throw IllegalArgumentException("Host not found!")
                val hostConnection = HostConnection(host)
                val processId =
                    if (hostConnection.hasSystemd) {
                        hostConnection
                            .command("systemctl show --property MainPID --value ${node.name}-node.service")
                            .trim()
                    } else {
                        throw IllegalAccessError("This node does not support automatic restart.")
                    }
                hostConnection.command("kill -INT $processId")
                try {
                    Thread.sleep(1000)
                    hostConnection.command("kill -TERM $processId")
                    Thread.sleep(1000)
                    hostConnection.command("kill -KILL $processId")
                } catch (t: Throwable) {
                    log.debug("kill command error!", t)
                }
                SocketResponse.Success(type = "restartnode", data = "triggered")
            } catch (e: Throwable) {
                log.error("Error Restarting Node!", e)
                SocketResponse.Error(type = "restartnode", exception = e)
            }

        @MessageMapping("/updatenodecolor")
        @Transactional
        fun updateNodeColor(request: UpdateColorRequest) {
            try {
                val node = nodeRepository.findByIdOrNull(request.id) ?: throw IllegalArgumentException("Node not found!")
                nodeRepository.save(node.copy(color = request.color))

                // send all to the client for ui updates
                val nodes = nodeRepository.findAll(Sort.by(Sort.Direction.ASC, "name")).filter { !it.isDeleted }
                webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success(type = "nodes", data = nodes))

                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Success("updatenodecolor", "Updated successfully!")
                )
            } catch (e: Throwable) {
                log.error("Error Updating Node color!", e)
                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Error(type = "updatenodecolor", exception = e)
                )
                // rethrow so db transaction is rolled back
                throw RuntimeException(e)
            }
        }

        @MessageMapping("/createnode")
        @Transactional
        fun createNode(request: CreateNodeRequest) {
            try {
                if (!walletUtils.isValidSpendingPassword(request.spendingPassword)) {
                    throw IllegalArgumentException("Invalid spending password!")
                }
                if (!request.enableTracingListener) {
                    throw IllegalArgumentException("Tracing listener must be enabled!")
                }
                log.debug(request.toString())
                val host =
                    hostRepository.findByIdOrNull(request.hostId)
                        ?: throw IOException("Invalid HostId: ${request.hostId}")
                val tracingListen = request.tracingListen ?: if (host.isRemote) "0.0.0.0" else "127.0.0.1"
                val hostConnection = HostConnection(host)
                // validate sudo password right away
                hostConnection.sudoCommand("pwd", request.sudoPassword)

                when (request.type) {
                    NODE_TYPE_RELAY -> {
                        val nodeFolder = "${host.nodeHomePath}${File.separator}${request.name}"
                        createNodeFolders(hostConnection, nodeFolder)
                        val genesisByronFile =
                            createGenesisFile("byron", request.genesisByronFileId, hostConnection, nodeFolder)
                        createGenesisFile("shelley", request.genesisShelleyFileId, hostConnection, nodeFolder)
                        createGenesisFile("alonzo", request.genesisAlonzoFileId, hostConnection, nodeFolder)
                        createGenesisFile("conway", request.genesisConwayFileId, hostConnection, nodeFolder)
                        createTopologyFile(genesisByronFile.name, hostConnection, nodeFolder)
                        val (configFileId, promPort) =
                            createConfigFile(
                                genesisByronFileName = genesisByronFile.name,
                                nodeType = request.type,
                                promPort = allocatePrometheusPort(request.hostId, hostConnection),
                                prometheusListen = request.prometheusListen,
                                hostConnection = hostConnection,
                                nodeFolder = nodeFolder,
                                maxConcurrencyDeadline = "4",
                                peerSharing = true,
                            )
                        val tracingPort =
                            allocateTracingPort(promPort) { port ->
                                isPortUsed(hostConnection, port)
                            }
                        createEnvFile(
                            hostConnection,
                            request.type,
                            request.name,
                            nodeFolder,
                            request.listen,
                            request.port,
                            tracingListen,
                            tracingPort,
                        )
                        createSystemdFile(
                            request = request,
                            startupNodeType = request.type,
                            host = host,
                            hostConnection = hostConnection,
                            name = request.name,
                            processorThreads = request.processorThreads,
                            tracingHost = tracingListen,
                            tracingPort = tracingPort,
                        )
                        createManualStartupScripts(
                            request = request,
                            startupNodeType = request.type,
                            host = host,
                            hostConnection = hostConnection,
                            tracingHost = tracingListen,
                            tracingPort = tracingPort,
                        )

                        if (request.isDefault) {
                            val oldDefault = nodeRepository.findDefault()
                            oldDefault?.let {
                                // Make old default no longer the default
                                nodeRepository.save(oldDefault.copy(isDefault = false))
                            }
                        }

                        val node =
                            Node(
                                hostId = host.id!!,
                                color = request.color,
                                type = request.type,
                                processorThreads = request.processorThreads,
                                name = request.name,
                                listen = request.listen,
                                port = request.port,
                                promPort = promPort,
                                tracingHost = tracingListen,
                                genesisByronFileId = request.genesisByronFileId,
                                genesisShelleyFileId = request.genesisShelleyFileId,
                                genesisAlonzoFileId = request.genesisAlonzoFileId,
                                genesisConwayFileId = request.genesisConwayFileId,
                                configFileId = configFileId,
                                isDefault = request.isDefault,
                                tracingPort = tracingPort,
                            )
                        val savedNode = nodeRepository.save(node)

                        // send it to the channel for monitoring
                        nodesChannel.tryEmit(savedNode)

                        // send all to the client for ui updates
                        val nodes = nodeRepository.findAll(Sort.by(Sort.Direction.ASC, "name")).filter { !it.isDeleted }
                        webSocketTemplate.convertAndSend(
                            "/topic/messages",
                            SocketResponse.Success(type = "nodes", data = nodes)
                        )
                    }

                    NODE_TYPE_CORE, NODE_TYPE_POOL -> {
                        val defaultNode = nodeRepository.findDefault() ?: throw IOException("No default node!")
                        val genesisFile =
                            fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)
                                ?: throw IOException("Genesis file for default node not found!")
                        val genesisShelley = shelleyGenesisAdapter.fromJson(genesisFile.content)!!
                        val magicString =
                            if (genesisShelley.networkId.equals("testnet", ignoreCase = true)) {
                                "--testnet-magic ${genesisShelley.networkMagic}"
                            } else {
                                "--mainnet"
                            }
                        val byronToShelleyEpochs = CardanoNetworkEpochs.byronToShelleyEpochs(genesisShelley)
                        val genesisByronFile =
                            fileRepository.findByIdOrNull((defaultNode.genesisByronFileId))
                                ?: throw IOException("Genesis Byron file for default node not found!")
                        val genesisByron = byronGenesisAdapter.fromJson(genesisByronFile.content)!!

                        val defaultHost =
                            hostRepository.findByIdOrNull(defaultNode.hostId)
                                ?: throw IOException("Host not found for default node!")
                        val defaultHostConnection = HostConnection(defaultHost, defaultNode)
                        val socketPath = "--socket-path ${defaultHost.nodeHomePath}/${defaultNode.name}/db/socket"
                        try {
                            val era = cardanoRepository.getEra(defaultHost, defaultNode, magicString, socketPath)
                            val protocolParamsJson =
                                defaultHostConnection
                                    .command(
                                        "${defaultHost.cardanoCliPath} $era query protocol-parameters $magicString $socketPath --output-json"
                                    ).trim()
                            defaultHostConnection.commandWriteFile("/tmp/protocol-parameters.json", protocolParamsJson)
                            val protocolParameters =
                                protocolParamsAdapter.fromJson(protocolParamsJson)
                                    ?: throw IOException("Invalid protocol params!")

                            // 1. Create a transaction to dump EVERYTHING into
                            var depositAndFees = BigInteger.ZERO
                            var witnessCount = 0
                            val transaction = StringBuilder()
                            val certificates = StringBuilder()
                            val signingKeys = StringBuilder()
                            transaction.append("${defaultHost.cardanoCliPath} $era transaction build-raw ")
                            val feePayerAccount =
                                walletRepository.findByIdOrNull(request.registrationFeesAccount!!)
                                    ?: throw IOException("Registration fees account not found!")
                            val utxos =
                                walletUtils.getUtxos(
                                    defaultHost,
                                    defaultHostConnection,
                                    magicString,
                                    socketPath,
                                    era,
                                    feePayerAccount.paymentAddr
                                )
                            utxos.forEach { utxo ->
                                transaction.append("--tx-in ${utxo.hash}#${utxo.ix} ")
                            }
                            val feePayerAccountBalance = utxos.sumByBigInteger { it.lovelace }
                            log.debug("feePayerAccount balance: $feePayerAccountBalance")
                            witnessCount++ // fee payer is a witness
                            defaultHostConnection.commandWriteFile(
                                "/tmp/feepayer.payment.skey",
                                walletUtils.getSKeyContent(
                                    requireNotNull(feePayerAccount.paymentSkey),
                                    request.spendingPassword
                                )
                            )
                            signingKeys.append("--signing-key-file /tmp/feepayer.payment.skey ")

                            // initial dummy value to return change to the fee payer account.
                            // We'll replace this with the actual change to return later
                            transaction.append("--tx-out ${feePayerAccount.paymentAddr}+$feePayerAccountBalance ")

                            val queryTipString =
                                defaultHostConnection
                                    .command("${defaultHost.cardanoCliPath} $era query tip $magicString $socketPath --output-json")
                                    .trim()
                            val ttl = queryTipAdapter.fromJson(queryTipString)?.let { it.slot + 21600 } ?: -1
                            transaction.append("--invalid-hereafter $ttl ")
                            transaction.append("--fee 200000 ")

                            // 2. Register owner address on the chain if not yet registered
                            val ownerStakingAccount =
                                walletRepository.findByIdOrNull(request.ownerStakingAccount!!)
                                    ?: throw IOException("Owner staking account not found!")
                            defaultHostConnection.commandWriteFile(
                                "/tmp/owner.staking.skey",
                                walletUtils.getSKeyContent(
                                    requireNotNull(ownerStakingAccount.stakingSkey),
                                    request.spendingPassword
                                )
                            )
                            defaultHostConnection.commandWriteFile(
                                "/tmp/owner.staking.vkey",
                                requireNotNull(ownerStakingAccount.stakingVkey?.content)
                            )
                            val ownerStakingWalletItem =
                                walletUtils.getWalletItem(
                                    defaultHost,
                                    defaultHostConnection,
                                    magicString,
                                    socketPath,
                                    era,
                                    ownerStakingAccount
                                )
                            if (!ownerStakingWalletItem.stakingAddrRegistered) {
                                // owner staking address is *not* registered. We should register it on chain as part of the transaction
                                ownerStakingAccount.stakingRegCert?.let { stakingRegCert ->
                                    val content =
                                        if ("Shelley" in stakingRegCert.content) {
                                            // we need to convert the shelley cert to a conway cert
                                            walletUtils
                                                .upgradeShelleyRegcertToConway(
                                                    stakingRegCert,
                                                    protocolParameters
                                                ).content
                                        } else {
                                            stakingRegCert.content
                                        }

                                    defaultHostConnection.commandWriteFile(
                                        "/tmp/owner.staking.cert",
                                        content
                                    )
                                } ?: throw IOException("Staking reg cert not found on owner account!")
                                depositAndFees += protocolParameters.stakeAddressDeposit
                                certificates.append("--certificate /tmp/owner.staking.cert ")
                            }
                            witnessCount++ // the owner.staking.skey is a witness
                            signingKeys.append("--signing-key-file /tmp/owner.staking.skey ")

                            // 3. Register rewards address on the chain if not yet registered
                            val rewardsStakingAccount =
                                walletRepository.findByIdOrNull(request.rewardsStakingAccount!!)
                                    ?: throw IOException("Rewards staking account not found!")
                            defaultHostConnection.commandWriteFile(
                                "/tmp/rewards.staking.skey",
                                walletUtils.getSKeyContent(
                                    requireNotNull(rewardsStakingAccount.stakingSkey),
                                    request.spendingPassword
                                )
                            )
                            defaultHostConnection.commandWriteFile(
                                "/tmp/rewards.staking.vkey",
                                requireNotNull(rewardsStakingAccount.stakingVkey?.content)
                            )
                            if (request.rewardsStakingAccount != request.ownerStakingAccount) {
                                val rewardsStakingWalletItem =
                                    walletUtils.getWalletItem(
                                        defaultHost,
                                        defaultHostConnection,
                                        magicString,
                                        socketPath,
                                        era,
                                        rewardsStakingAccount
                                    )
                                if (!rewardsStakingWalletItem.stakingAddrRegistered) {
                                    // rewards staking address is *not* registered. We should register it on chain as part of the transaction
                                    rewardsStakingAccount.stakingRegCert?.let { stakingRegCert ->
                                        val content =
                                            if ("Shelley" in stakingRegCert.content) {
                                                // we need to convert the shelley cert to a conway cert
                                                walletUtils
                                                    .upgradeShelleyRegcertToConway(
                                                        stakingRegCert,
                                                        protocolParameters
                                                    ).content
                                            } else {
                                                stakingRegCert.content
                                            }
                                        defaultHostConnection.commandWriteFile(
                                            "/tmp/rewards.staking.cert",
                                            content
                                        )
                                    } ?: throw IOException("Staking reg cert not found on rewards account!")
                                    depositAndFees += protocolParameters.stakeAddressDeposit
                                    certificates.append("--certificate /tmp/rewards.staking.cert ")

                                    witnessCount++ // the rewards.staking.skey is a witness
                                    signingKeys.append("--signing-key-file /tmp/rewards.staking.skey ")
                                }
                            }

                            // 4. Keys and opcert
                            val coreSKeyId: Long
                            val coreVKeyId: Long
                            val coreCounterId: Long
                            if (request.generateColdKeys) {
                                defaultHostConnection.command(
                                    "${defaultHost.cardanoCliPath} $era node key-gen --verification-key-file /tmp/core.node.vkey --signing-key-file /tmp/core.node.skey --operational-certificate-issue-counter /tmp/core.node.counter"
                                )
                                val coreSKeyContent = defaultHostConnection.commandReadFile("/tmp/core.node.skey")
                                val coreVKeyContent = defaultHostConnection.commandReadFile("/tmp/core.node.vkey")
                                val coreSKey =
                                    com.swiftmako.jormanager.entities.File(
                                        name = "${request.name}.node.skey",
                                        content =
                                            walletUtils.encryptSKeyContent(
                                                coreSKeyContent,
                                                request.spendingPassword
                                            )
                                    )
                                val coreVKey =
                                    com.swiftmako.jormanager.entities.File(
                                        name = "${request.name}.node.vkey",
                                        content = coreVKeyContent
                                    )
                                coreSKeyId = fileRepository.save(coreSKey).id!!
                                coreVKeyId = fileRepository.save(coreVKey).id!!
                            } else {
                                defaultHostConnection.commandWriteFile(
                                    "/tmp/core.node.skey",
                                    requireNotNull(request.coldSKey).trim()
                                )
                                defaultHostConnection.commandWriteFile(
                                    "/tmp/core.node.vkey",
                                    requireNotNull(request.coldVKey).trim()
                                )
                                defaultHostConnection.commandWriteFile(
                                    "/tmp/core.node.counter",
                                    requireNotNull(request.coldCounter).trim()
                                )
                                val coreSKey =
                                    com.swiftmako.jormanager.entities.File(
                                        name = "${request.name}.node.skey",
                                        content =
                                            walletUtils.encryptSKeyContent(
                                                requireNotNull(request.coldSKey).trim(),
                                                request.spendingPassword
                                            )
                                    )
                                val coreVKey =
                                    com.swiftmako.jormanager.entities.File(
                                        name = "${request.name}.node.vkey",
                                        content = requireNotNull(request.coldVKey).trim()
                                    )
                                coreSKeyId = fileRepository.save(coreSKey).id!!
                                coreVKeyId = fileRepository.save(coreVKey).id!!
                            }
                            witnessCount++ // the core.node.skey is always a witness
                            signingKeys.append("--signing-key-file /tmp/core.node.skey ")
                            val vrfSKeyId: Long
                            val vrfVKeyId: Long
                            val vrfSKeyContent =
                                if (request.generateVRFKeys) {
                                    defaultHostConnection.command(
                                        "${defaultHost.cardanoCliPath} $era node key-gen-VRF --verification-key-file /tmp/core.vrf.vkey --signing-key-file /tmp/core.vrf.skey"
                                    )
                                    val vrfSKeyContent = defaultHostConnection.commandReadFile("/tmp/core.vrf.skey")
                                    val vrfVKeyContent = defaultHostConnection.commandReadFile("/tmp/core.vrf.vkey")
                                    val vrfSKey =
                                        com.swiftmako.jormanager.entities.File(
                                            name = "${request.name}.vrf.skey",
                                            content =
                                                walletUtils.encryptSKeyContent(
                                                    vrfSKeyContent,
                                                    request.spendingPassword
                                                )
                                        )
                                    val vrfVKey =
                                        com.swiftmako.jormanager.entities.File(
                                            name = "${request.name}.vrf.vkey",
                                            content = vrfVKeyContent
                                        )
                                    vrfSKeyId = fileRepository.save(vrfSKey).id!!
                                    vrfVKeyId = fileRepository.save(vrfVKey).id!!
                                    vrfSKeyContent
                                } else {
                                    defaultHostConnection.commandWriteFile(
                                        "/tmp/core.vrf.skey",
                                        requireNotNull(request.vrfSKey).trim()
                                    )
                                    defaultHostConnection.commandWriteFile(
                                        "/tmp/core.vrf.vkey",
                                        requireNotNull(request.vrfVKey).trim()
                                    )
                                    val vrfSKey =
                                        com.swiftmako.jormanager.entities.File(
                                            name = "${request.name}.vrf.skey",
                                            content =
                                                walletUtils.encryptSKeyContent(
                                                    requireNotNull(request.vrfSKey).trim(),
                                                    request.spendingPassword
                                                )
                                        )
                                    val vrfVKey =
                                        com.swiftmako.jormanager.entities.File(
                                            name = "${request.name}.vrf.vkey",
                                            content = requireNotNull(request.vrfVKey).trim()
                                        )
                                    vrfSKeyId = fileRepository.save(vrfSKey).id!!
                                    vrfVKeyId = fileRepository.save(vrfVKey).id!!
                                    request.vrfSKey
                                }
                            val kesSKeyId: Long
                            val kesVKeyId: Long
                            val kesSKeyContent =
                                if (request.generateKESKeys) {
                                    defaultHostConnection.command(
                                        "${defaultHost.cardanoCliPath} $era node key-gen-KES --verification-key-file /tmp/core.kes.vkey --signing-key-file /tmp/core.kes.skey"
                                    )
                                    val kesSKeyContent = defaultHostConnection.commandReadFile("/tmp/core.kes.skey")
                                    val kesVKeyContent = defaultHostConnection.commandReadFile("/tmp/core.kes.vkey")
                                    val kesSKey =
                                        com.swiftmako.jormanager.entities.File(
                                            name = "${request.name}.kes.skey",
                                            content =
                                                walletUtils.encryptSKeyContent(
                                                    kesSKeyContent,
                                                    request.spendingPassword
                                                )
                                        )
                                    val kesVKey =
                                        com.swiftmako.jormanager.entities.File(
                                            name = "${request.name}.kes.vkey",
                                            content = kesVKeyContent
                                        )
                                    kesSKeyId = fileRepository.save(kesSKey).id!!
                                    kesVKeyId = fileRepository.save(kesVKey).id!!
                                    kesSKeyContent
                                } else {
                                    defaultHostConnection.commandWriteFile(
                                        "/tmp/core.kes.skey",
                                        requireNotNull(request.kesSKey).trim()
                                    )
                                    defaultHostConnection.commandWriteFile(
                                        "/tmp/core.kes.vkey",
                                        requireNotNull(request.kesVKey).trim()
                                    )
                                    val kesSKey =
                                        com.swiftmako.jormanager.entities.File(
                                            name = "${request.name}.kes.skey",
                                            content =
                                                walletUtils.encryptSKeyContent(
                                                    requireNotNull(request.kesSKey).trim(),
                                                    request.spendingPassword
                                                )
                                        )
                                    val kesVKey =
                                        com.swiftmako.jormanager.entities.File(
                                            name = "${request.name}.kes.vkey",
                                            content = requireNotNull(request.kesVKey).trim()
                                        )
                                    kesSKeyId = fileRepository.save(kesSKey).id!!
                                    kesVKeyId = fileRepository.save(kesVKey).id!!
                                    request.kesSKey
                                }

                            val slotLength = genesisShelley.slotLength
                            val epochLength = genesisShelley.epochLength
                            val slotsPerKESPeriod = genesisShelley.slotsPerKESPeriod
                            val startTimeByron = genesisByron.startTime
                            val startTimeGenesis = genesisShelley.systemStart

                            val startTimeSec =
                                defaultHostConnection.command("date --date=$startTimeGenesis +%s").trim().toLong()
                            val transTimeEnd = startTimeSec + (byronToShelleyEpochs * epochLength)
                            val byronSlots = (startTimeSec - startTimeByron) / 20L
                            val transSlots = (byronToShelleyEpochs * epochLength) / 20L

                            val currentTimeSec = defaultHostConnection.command("date -u +%s").trim().toLong()

                            val currentSlot =
                                if (currentTimeSec < transTimeEnd) {
                                    // in transition phase between shelley genesis start and transition end
                                    (byronSlots + (currentTimeSec - startTimeSec) / 20L)
                                } else {
                                    // after transition phase
                                    (byronSlots + transSlots + ((currentTimeSec - transTimeEnd) / slotLength))
                                }
                            var currentKESPeriod = ((currentSlot - byronSlots) / (slotsPerKESPeriod * slotLength))
                            if (currentKESPeriod < 0L) {
                                currentKESPeriod = 0L
                            }

                            val maxKESEvolutions = genesisShelley.maxKESEvolutions
                            val expiresKESPeriod = currentKESPeriod + maxKESEvolutions
                            val kesExpireTimeSec = (currentTimeSec + (slotLength * maxKESEvolutions * slotsPerKESPeriod))
                            val kesExpireDate = defaultHostConnection.command("date --date=@$kesExpireTimeSec").trim()
                            log.warn(
                                "CreateKES: currentKESPeriod: $currentKESPeriod, expiresKESPeriod: $expiresKESPeriod, expireDate: $kesExpireDate"
                            )

                            defaultHostConnection.command(
                                "${defaultHost.cardanoCliPath} $era node issue-op-cert --hot-kes-verification-key-file /tmp/core.kes.vkey --cold-signing-key-file /tmp/core.node.skey --operational-certificate-issue-counter /tmp/core.node.counter --kes-period $currentKESPeriod --out-file /tmp/core.node.opcert"
                            )
                            val opcertContent = defaultHostConnection.commandReadFile("/tmp/core.node.opcert")
                            val opcertFile =
                                com.swiftmako.jormanager.entities.File(
                                    name = "${request.name}.node.opcert",
                                    content = opcertContent
                                )
                            val opcertId = fileRepository.save(opcertFile).id!!
                            val coreCounterContent = defaultHostConnection.commandReadFile("/tmp/core.node.counter")
                            val coreCounter =
                                com.swiftmako.jormanager.entities.File(
                                    name = "${request.name}.node.counter",
                                    content = coreCounterContent
                                )
                            coreCounterId = fileRepository.save(coreCounter).id!!

                            val poolId =
                                defaultHostConnection
                                    .command(
                                        "${defaultHost.cardanoCliPath} $era stake-pool id --cold-verification-key-file /tmp/core.node.vkey --output-hex"
                                    ).trim()
                            log.debug("poolId: $poolId")
                            val isPoolOnChain =
                                isPoolOnChain(defaultHost, defaultHostConnection, poolId, era, magicString, socketPath)

                            // 5. Create and upload metadata files
                            var itnPrivateKeyId = -1L
                            var itnPublicKeyId = -1L
                            val itnWitnessSign =
                                if (request.metadata
                                        ?.extended
                                        ?.itn
                                        ?.privateKey != null &&
                                    defaultHost.jcliPath != null
                                ) {
                                    val itnPrivateKey =
                                        com.swiftmako.jormanager.entities.File(
                                            name = "${request.name}.itn.skey",
                                            content =
                                                walletUtils.encryptSKeyContent(
                                                    request.metadata.extended.itn.privateKey
                                                        .trim(),
                                                    request.spendingPassword
                                                )
                                        )
                                    itnPrivateKeyId = fileRepository.save(itnPrivateKey).id!!
                                    defaultHostConnection.commandWriteFile("/tmp/core.pool.id", poolId)
                                    defaultHostConnection.commandWriteFile(
                                        "/tmp/core.itn.skey",
                                        request.metadata.extended.itn.privateKey
                                            .trim()
                                    )
                                    defaultHostConnection
                                        .command(
                                            "${defaultHost.jcliPath} key sign --secret-key /tmp/core.itn.skey /tmp/core.pool.id"
                                        ).trim()
                                } else {
                                    null
                                }
                            val itnWitnessOwner =
                                if (request.metadata
                                        ?.extended
                                        ?.itn
                                        ?.publicKey != null
                                ) {
                                    val itnPublicKey =
                                        com.swiftmako.jormanager.entities.File(
                                            name = "${request.name}.itn.vkey",
                                            content =
                                                request.metadata.extended.itn.publicKey
                                                    .trim()
                                        )
                                    itnPublicKeyId = fileRepository.save(itnPublicKey).id!!
                                    request.metadata.extended.itn.publicKey
                                        .trim()
                                } else {
                                    null
                                }

                            val extendedMetadata =
                                ExtendedMetadata(
                                    itn =
                                        itnWitnessSign?.let {
                                            Itn(
                                                owner = itnWitnessOwner,
                                                witness = itnWitnessSign
                                            )
                                        },
                                    info =
                                        Info(
                                            urlPngIcon64x64 =
                                                request.metadata
                                                    ?.extended
                                                    ?.info
                                                    ?.icon64,
                                            urlPngLogo =
                                                request.metadata
                                                    ?.extended
                                                    ?.info
                                                    ?.logo,
                                            location =
                                                request.metadata
                                                    ?.extended
                                                    ?.info
                                                    ?.location,
                                            social =
                                                Social(
                                                    twitterHandle =
                                                        request.metadata
                                                            ?.extended
                                                            ?.info
                                                            ?.social
                                                            ?.twitter,
                                                    telegramHandle =
                                                        request.metadata
                                                            ?.extended
                                                            ?.info
                                                            ?.social
                                                            ?.telegram,
                                                    facebookHandle =
                                                        request.metadata
                                                            ?.extended
                                                            ?.info
                                                            ?.social
                                                            ?.facebook,
                                                    youtubeHandle =
                                                        request.metadata
                                                            ?.extended
                                                            ?.info
                                                            ?.social
                                                            ?.youtube,
                                                    twitchHandle =
                                                        request.metadata
                                                            ?.extended
                                                            ?.info
                                                            ?.social
                                                            ?.twitch,
                                                    discordHandle =
                                                        request.metadata
                                                            ?.extended
                                                            ?.info
                                                            ?.social
                                                            ?.discord,
                                                    githubHandle =
                                                        request.metadata
                                                            ?.extended
                                                            ?.info
                                                            ?.social
                                                            ?.github
                                                ),
                                            company =
                                                Company(
                                                    name =
                                                        request.metadata
                                                            ?.extended
                                                            ?.info
                                                            ?.company
                                                            ?.name,
                                                    addr =
                                                        request.metadata
                                                            ?.extended
                                                            ?.info
                                                            ?.company
                                                            ?.addr,
                                                    city =
                                                        request.metadata
                                                            ?.extended
                                                            ?.info
                                                            ?.company
                                                            ?.city,
                                                    country =
                                                        request.metadata
                                                            ?.extended
                                                            ?.info
                                                            ?.company
                                                            ?.country,
                                                    companyId =
                                                        request.metadata
                                                            ?.extended
                                                            ?.info
                                                            ?.company
                                                            ?.companyId,
                                                    vatId =
                                                        request.metadata
                                                            ?.extended
                                                            ?.info
                                                            ?.company
                                                            ?.vatId
                                                ),
                                            about =
                                                About(
                                                    me =
                                                        request.metadata
                                                            ?.extended
                                                            ?.info
                                                            ?.about
                                                            ?.me,
                                                    server =
                                                        request.metadata
                                                            ?.extended
                                                            ?.info
                                                            ?.about
                                                            ?.server,
                                                    company =
                                                        request.metadata
                                                            ?.extended
                                                            ?.info
                                                            ?.about
                                                            ?.company
                                                ),
                                            rss =
                                                request.metadata
                                                    ?.extended
                                                    ?.info
                                                    ?.rss
                                        ),
                                    telegramAdminHandle =
                                        request.metadata?.extended?.telegramAdminHandle?.let {
                                            listOf(
                                                it
                                            )
                                        }
                                            ?: emptyList(),
                                    myPoolIds = listOf(poolId),
                                    whenSaturedThenRecommend = null
                                )

                            val extendedMetadataJson = extendedMetadataAdapter.indent(" ").toJson(extendedMetadata)
                            val extendedMetadataUrl = uploadMetadata(extendedMetadataJson)
                            log.debug("extendedMetadataUrl: $extendedMetadataUrl")

                            val metadata =
                                com.swiftmako.jormanager.model.metadata.pool.Metadata(
                                    name = requireNotNull(request.metadata?.name),
                                    description = requireNotNull(request.metadata.description),
                                    ticker = requireNotNull(request.metadata.ticker),
                                    homepage = requireNotNull(request.metadata.homepage),
                                    extended = extendedMetadataUrl
                                )
                            val metadataJson = metadataAdapter.indent(" ").toJson(metadata)
                            val metadataUrl = uploadMetadata(metadataJson)
                            log.debug("metadataUrl: $metadataUrl")

                            // download and get the hash!
                            defaultHostConnection.command("curl $metadataUrl --output /tmp/metadata.json")
                            val metadataHash =
                                defaultHostConnection
                                    .command(
                                        "${defaultHost.cardanoCliPath} $era stake-pool metadata-hash --pool-metadata-file /tmp/metadata.json"
                                    ).trim()

                            // 6. create the pool registration certificate
                            val poolRegcertCommand =
                                StringBuilder().apply {
                                    append("${defaultHost.cardanoCliPath} $era stake-pool registration-certificate ")
                                    append("--cold-verification-key-file /tmp/core.node.vkey ")
                                    append("--vrf-verification-key-file /tmp/core.vrf.vkey ")
                                    append("--pool-pledge ${request.poolPledge} ")
                                    append("--pool-cost ${request.poolCost} ")
                                    append("--pool-margin ${request.poolMargin.toBigDecimal()} ")
                                    append("--pool-reward-account-verification-key-file /tmp/rewards.staking.vkey ")
                                    append("--pool-owner-stake-verification-key-file /tmp/owner.staking.vkey ")
                                    request.relays?.forEach { relay ->
                                        if (relay.addr.matches(IP4_ADDRESS)) {
                                            append("--pool-relay-ipv4 ${relay.addr} --pool-relay-port ${relay.port} ")
                                        } else {
                                            append("--single-host-pool-relay ${relay.addr} --pool-relay-port ${relay.port} ")
                                        }
                                    }
                                    append("--metadata-url $metadataUrl --metadata-hash $metadataHash ")
                                    append("$magicString ")
                                    append("--out-file /tmp/core.pool.cert")
                                }
                            log.debug("poolRegcertCommand: $poolRegcertCommand")
                            defaultHostConnection.command(poolRegcertCommand.toString())
                            certificates.append("--certificate /tmp/core.pool.cert ")
                            depositAndFees +=
                                if (!isPoolOnChain) {
                                    protocolParameters.stakePoolDeposit
                                } else {
                                    BigInteger.ZERO
                                }

                            // 7. Create delegation certificates for owner(s)
                            defaultHostConnection.command(
                                "${defaultHost.cardanoCliPath} $era stake-address stake-delegation-certificate --stake-verification-key-file /tmp/owner.staking.vkey --cold-verification-key-file /tmp/core.node.vkey --out-file /tmp/owner.deleg.cert"
                            )
                            certificates.append("--certificate /tmp/owner.deleg.cert ")

                            // 8. Calculate fees
                            transaction.append(certificates)
                            transaction.append("--out-file /tmp/transaction.txbody")
                            defaultHostConnection.command(transaction.toString())

                            log.debug("depositAndFees: $depositAndFees")
                            val feesString =
                                defaultHostConnection
                                    .command(
                                        "${defaultHost.cardanoCliPath} $era transaction calculate-min-fee --tx-body-file /tmp/transaction.txbody --protocol-params-file /tmp/protocol-parameters.json --tx-in-count ${utxos.size} --tx-out-count 1 $magicString --witness-count $witnessCount --byron-witness-count 0 --output-text"
                                    ).trim()
                            val fees = feesString.split(" ")[0].toBigInteger()
                            log.debug("fees: $fees")
                            depositAndFees += fees
                            log.debug("final depositAndFees: $depositAndFees")

                            // 9. Create the transaction
                            val change = utxos.sumByBigInteger { it.lovelace } - depositAndFees
                            if (change < BigInteger.ONE) {
                                throw IOException("Not enough funds to pay depositAndFees of $depositAndFees lovelace!")
                            }

                            val tokenChange = StringBuilder()
                            utxos.toNativeAssetMap().forEach { (currency, amount) ->
                                if (amount > BigInteger.ZERO) {
                                    tokenChange.append("+$amount $currency")
                                }
                            }

                            val realTransaction =
                                transaction
                                    .toString()
                                    .replace("--fee 200000 ", "--fee $fees ")
                                    .replace(
                                        "--tx-out ${feePayerAccount.paymentAddr}+$feePayerAccountBalance ",
                                        "--tx-out '${feePayerAccount.paymentAddr}+$change$tokenChange' "
                                    )
                            log.debug("Pool Transaction Command: $realTransaction")
                            defaultHostConnection.command(realTransaction)

                            // 10. Sign the transaction
                            defaultHostConnection.command(
                                "${defaultHost.cardanoCliPath} $era transaction sign --tx-body-file /tmp/transaction.txbody $signingKeys $magicString --out-file /tmp/transaction.txsigned"
                            )

                            runBlocking {
                                TransactionCache.withLock {
                                    // 11. Submit the transaction
                                    defaultHostConnection.command(
                                        "${defaultHost.cardanoCliPath} $era transaction submit --tx-file /tmp/transaction.txsigned $magicString $socketPath"
                                    )
                                    val txid =
                                        defaultHostConnection
                                            .command(
                                                "${defaultHost.cardanoCliPath} $era transaction txid --tx-body-file /tmp/transaction.txbody --output-text"
                                            ).trim()
                                    val txSigned = defaultHostConnection.commandReadFile("/tmp/transaction.txsigned")
                                    TransactionCache.put(txid, txSigned)

                                    val cborBytes = txSignedAdapter.fromJson(txSigned)!!.cborHex.hexToByteArray()
                                    ledgerDao.updateLiveLedgerState(txid, cborBytes)
                                    transactionRepository.save(Transaction(txid = txid))
                                }
                            }

                            // 13. Create and Save node information
                            var parentId: Long? = null
                            var tracingPort: Int? = null
                            val (configFileId, promPort) =
                                if (request.type == "core") {
                                    val nodeFolder = "${host.nodeHomePath}${File.separator}${request.name}"
                                    createNodeFolders(hostConnection, nodeFolder)
                                    createGenesisFile("byron", request.genesisByronFileId, hostConnection, nodeFolder)
                                    createGenesisFile("shelley", request.genesisShelleyFileId, hostConnection, nodeFolder)
                                    createGenesisFile("alonzo", request.genesisAlonzoFileId, hostConnection, nodeFolder)
                                    createGenesisFile("conway", request.genesisConwayFileId, hostConnection, nodeFolder)
                                    createTopologyFile(genesisByronFile.name, hostConnection, nodeFolder)
                                    val allocatedPromPort = allocatePrometheusPort(request.hostId, hostConnection)
                                    val (configFileId, promPort) =
                                        createConfigFile(
                                            genesisByronFileName = genesisByronFile.name,
                                            nodeType = request.type,
                                            promPort = allocatedPromPort,
                                            prometheusListen = request.prometheusListen,
                                            hostConnection = hostConnection,
                                            nodeFolder = nodeFolder,
                                            maxConcurrencyDeadline = "2",
                                            peerSharing = false,
                                        )
                                    createKesVrfOpcert(
                                        request.name,
                                        kesSKeyContent,
                                        vrfSKeyContent,
                                        opcertContent,
                                        hostConnection,
                                        nodeFolder
                                    )
                                    tracingPort =
                                        allocateTracingPort(promPort) { port ->
                                            isPortUsed(hostConnection, port)
                                        }
                                    createEnvFile(
                                        hostConnection,
                                        request.type,
                                        request.name,
                                        nodeFolder,
                                        request.listen,
                                        request.port,
                                        tracingListen,
                                        tracingPort,
                                    )
                                    createSystemdFile(
                                        request = request,
                                        startupNodeType = request.type,
                                        host = host,
                                        hostConnection = hostConnection,
                                        name = request.name,
                                        processorThreads = request.processorThreads,
                                        tracingHost = tracingListen,
                                        tracingPort = tracingPort,
                                    )
                                    createManualStartupScripts(
                                        request = request,
                                        startupNodeType = request.type,
                                        host = host,
                                        hostConnection = hostConnection,
                                        tracingHost = tracingListen,
                                        tracingPort = tracingPort,
                                    )
                                    Pair(configFileId, promPort)
                                } else {
                                    // pool
                                    nodeRepository.findByIdOrNull(request.parentId!!)?.let { coreNode ->
                                        parentId = coreNode.id
                                        val nodeFolder = "${host.nodeHomePath}${File.separator}${coreNode.name}"
                                        createKesVrfOpcert(
                                            request.name,
                                            kesSKeyContent,
                                            vrfSKeyContent,
                                            opcertContent,
                                            hostConnection,
                                            nodeFolder
                                        )
                                        createEnvFile(
                                            hostConnection,
                                            request.type,
                                            coreNode.name,
                                            nodeFolder,
                                            coreNode.listen,
                                            coreNode.port,
                                            coreNode.tracingHost ?: tracingListen,
                                            coreNode.tracingPort,
                                        )
                                        val credentials = mutableListOf<MutableList<Key>>()
                                        // core node's pool
                                        credentials.add(
                                            mutableListOf(
                                                keyFileJsonAdapter.fromJson(fileRepository.findByIdOrNull(coreNode.opcertId)!!.content)!!,
                                                keyFileJsonAdapter.fromJson(
                                                    walletUtils.getSKeyContent(
                                                        fileRepository.findByIdOrNull(
                                                            coreNode.vrfSKeyId
                                                        )!!,
                                                        request.spendingPassword
                                                    )
                                                )!!,
                                                keyFileJsonAdapter.fromJson(
                                                    walletUtils.getSKeyContent(
                                                        fileRepository.findByIdOrNull(
                                                            coreNode.kesSKeyId
                                                        )!!,
                                                        request.spendingPassword
                                                    )
                                                )!!,
                                            )
                                        )
                                        // existing child pools
                                        nodeRepository.findByParentId(coreNode.id!!).forEach { pool ->
                                            credentials.add(
                                                mutableListOf(
                                                    keyFileJsonAdapter.fromJson(fileRepository.findByIdOrNull(pool.opcertId)!!.content)!!,
                                                    keyFileJsonAdapter.fromJson(
                                                        walletUtils.getSKeyContent(
                                                            fileRepository.findByIdOrNull(
                                                                pool.vrfSKeyId
                                                            )!!,
                                                            request.spendingPassword
                                                        )
                                                    )!!,
                                                    keyFileJsonAdapter.fromJson(
                                                        walletUtils.getSKeyContent(
                                                            fileRepository.findByIdOrNull(
                                                                pool.kesSKeyId
                                                            )!!,
                                                            request.spendingPassword
                                                        )
                                                    )!!,
                                                )
                                            )
                                        }
                                        // new child pool
                                        credentials.add(
                                            mutableListOf(
                                                keyFileJsonAdapter.fromJson(opcertContent)!!,
                                                keyFileJsonAdapter.fromJson(vrfSKeyContent)!!,
                                                keyFileJsonAdapter.fromJson(kesSKeyContent)!!,
                                            )
                                        )
                                        createBulkCredentials(hostConnection, nodeFolder, credentials)
                                        createSystemdFile(
                                            request = request,
                                            startupNodeType = request.type,
                                            host = host,
                                            hostConnection = hostConnection,
                                            name = coreNode.name,
                                            processorThreads = 4 + credentials.size,
                                            tracingHost = coreNode.tracingHost ?: tracingListen,
                                            tracingPort = coreNode.tracingPort,
                                        )

                                        Pair(coreNode.configFileId, coreNode.promPort)
                                    } ?: throw IOException("Parent core node not found!")
                                }
                            val node =
                                Node(
                                    hostId = host.id!!,
                                    parentId = parentId,
                                    color = request.color,
                                    type = request.type,
                                    processorThreads = request.processorThreads,
                                    name = request.name,
                                    listen = request.listen,
                                    port = request.port,
                                    promPort = promPort,
                                    tracingHost = tracingListen,
                                    genesisByronFileId = request.genesisByronFileId,
                                    genesisShelleyFileId = request.genesisShelleyFileId,
                                    genesisAlonzoFileId = request.genesisAlonzoFileId,
                                    genesisConwayFileId = request.genesisConwayFileId,
                                    configFileId = configFileId,
                                    isDefault = false,
                                    poolId = poolId,
                                    poolPledge = request.poolPledge,
                                    poolCost = request.poolCost,
                                    poolMargin = request.poolMargin,
                                    ownerStakingAccountId = ownerStakingAccount.id!!,
                                    rewardsStakingAccountId = rewardsStakingAccount.id!!,
                                    coreSKeyId = coreSKeyId,
                                    coreVKeyId = coreVKeyId,
                                    coreCounterId = coreCounterId,
                                    vrfSKeyId = vrfSKeyId,
                                    vrfVKeyId = vrfVKeyId,
                                    kesSKeyId = kesSKeyId,
                                    kesVKeyId = kesVKeyId,
                                    kesExpireTimeSec = kesExpireTimeSec,
                                    kesExpireDate = kesExpireDate,
                                    opcertId = opcertId,
                                    itnPrivateKeyId = itnPrivateKeyId,
                                    itnPublicKeyId = itnPublicKeyId,
                                    metadataUrl = metadataUrl,
                                    extendedMetadataUrl = extendedMetadataUrl,
                                    tracingPort = tracingPort,
                                )
                            val savedNode = nodeRepository.save(node)

                            // save relays
                            request.relays?.let { relays ->
                                if (relays.isNotEmpty()) {
                                    relayRepository.saveAll(
                                        relays.map {
                                            Relay(
                                                nodeId = savedNode.id!!,
                                                addr = it.addr,
                                                port = it.port
                                            )
                                        }
                                    )
                                }
                            }

                            // send it to the channel for monitoring
                            nodesChannel.tryEmit(savedNode)

                            // send all to the client for ui updates
                            val nodes = nodeRepository.findAll(Sort.by(Sort.Direction.ASC, "name")).filter { !it.isDeleted }
                            webSocketTemplate.convertAndSend(
                                "/topic/messages",
                                SocketResponse.Success(type = "nodes", data = nodes)
                            )
                        } finally {
                            // Cleanup
                            defaultHostConnection.command(
                                "rm -f /tmp/protocol-parameters.json /tmp/transaction.txbody /tmp/transaction.txsigned /tmp/core.pool.cert  /tmp/feepayer.payment.skey /tmp/owner.staking.skey /tmp/owner.staking.vkey /tmp/owner.staking.cert /tmp/owner.deleg.cert /tmp/rewards.staking.skey /tmp/rewards.staking.vkey /tmp/rewards.staking.cert /tmp/core.node.skey /tmp/core.node.vkey /tmp/core.node.counter /tmp/core.vrf.skey /tmp/core.vrf.vkey /tmp/core.kes.skey /tmp/core.kes.vkey /tmp/core.node.opcert /tmp/core.pool.id /tmp/core.itn.skey /tmp/core.itn.vkey /tmp/metadata.json"
                            )
                        }
                    }

                    else -> {
                        throw IOException("Invalid node type: ${request.type}")
                    }
                }

                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Success("createnode", "${request.name} created successfully!")
                )
            } catch (e: Throwable) {
                log.error("Error Creating Node!", e)
                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Error(type = "createnode", exception = e)
                )
                // rethrow so db transaction is rolled back
                throw RuntimeException(e)
            }
        }

        @MessageMapping("/updatepoolconfig")
        @Transactional
        fun updatePoolConfig(request: EditPoolConfigRequest) {
            try {
                if (!walletUtils.isValidSpendingPassword(request.spendingPassword)) {
                    throw IllegalArgumentException("Invalid spending password!")
                }
                nodeRepository.findDefault()?.let { defaultNode ->
                    fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)?.let { genesisFile ->
                        val genesis = shelleyGenesisAdapter.fromJson(genesisFile.content)!!
                        val magicString =
                            if (genesis.networkId.equals("testnet", ignoreCase = true)) {
                                "--testnet-magic ${genesis.networkMagic}"
                            } else {
                                "--mainnet"
                            }

                        hostRepository.findByIdOrNull(defaultNode.hostId)?.let { defaultHost ->
                            val defaultHostConnection = HostConnection(defaultHost, defaultNode)
                            val socketPath = "--socket-path ${defaultHost.nodeHomePath}/${defaultNode.name}/db/socket"
                            try {
                                val era = cardanoRepository.getEra(defaultHost, defaultNode, magicString, socketPath)
                                val protocolParamsJson =
                                    defaultHostConnection
                                        .command(
                                            "${defaultHost.cardanoCliPath} $era query protocol-parameters $magicString $socketPath --output-json"
                                        ).trim()
                                defaultHostConnection.commandWriteFile("/tmp/protocol-parameters.json", protocolParamsJson)
                                val protocolParameters =
                                    protocolParamsAdapter.fromJson(protocolParamsJson)
                                        ?: throw IOException("Invalid protocol params!")

                                // 1. Create a transaction to dump EVERYTHING into
                                var depositAndFees = BigInteger.ZERO
                                var witnessCount = 0
                                val transaction = StringBuilder()
                                val certificates = StringBuilder()
                                val signingKeys = StringBuilder()
                                transaction.append("${defaultHost.cardanoCliPath} $era transaction build-raw ")
                                val feePayerAccount =
                                    walletRepository.findByIdOrNull(request.registrationFeesAccount)
                                        ?: throw IOException("Registration fees account not found!")
                                val utxos =
                                    walletUtils.getUtxos(
                                        defaultHost,
                                        defaultHostConnection,
                                        magicString,
                                        socketPath,
                                        era,
                                        feePayerAccount.paymentAddr
                                    )
                                utxos.forEach { utxo ->
                                    transaction.append("--tx-in ${utxo.hash}#${utxo.ix} ")
                                }
                                val feePayerAccountBalance = utxos.sumByBigInteger { it.lovelace }
                                log.debug("feePayerAccount balance: {}", feePayerAccountBalance)
                                witnessCount++ // fee payer is a witness
                                defaultHostConnection.commandWriteFile(
                                    "/tmp/feepayer.payment.skey",
                                    walletUtils.getSKeyContent(
                                        requireNotNull(feePayerAccount.paymentSkey),
                                        request.spendingPassword
                                    )
                                )
                                signingKeys.append("--signing-key-file /tmp/feepayer.payment.skey ")

                                // initial dummy value to return change to the fee payer account.
                                // We'll replace this with the actual change to return later
                                transaction.append("--tx-out ${feePayerAccount.paymentAddr}+$feePayerAccountBalance ")

                                val queryTipJson =
                                    defaultHostConnection
                                        .command(
                                            "${defaultHost.cardanoCliPath} $era query tip $magicString $socketPath --output-json"
                                        ).trim()
                                val ttl = queryTipAdapter.fromJson(queryTipJson)?.let { it.slot + 21600 } ?: -1
                                transaction.append("--invalid-hereafter $ttl ")
                                transaction.append("--fee 200000 ")

                                // 2. Register owner address on the chain if not yet registered
                                val ownerStakingAccount =
                                    walletRepository.findByIdOrNull(request.ownerStakingAccount)
                                        ?: throw IOException("Owner staking account not found!")
                                defaultHostConnection.commandWriteFile(
                                    "/tmp/owner.staking.skey",
                                    walletUtils.getSKeyContent(
                                        requireNotNull(ownerStakingAccount.stakingSkey),
                                        request.spendingPassword
                                    )
                                )
                                defaultHostConnection.commandWriteFile(
                                    "/tmp/owner.staking.vkey",
                                    requireNotNull(ownerStakingAccount.stakingVkey?.content)
                                )
                                val ownerStakingWalletItem =
                                    walletUtils.getWalletItem(
                                        defaultHost,
                                        defaultHostConnection,
                                        magicString,
                                        socketPath,
                                        era,
                                        ownerStakingAccount
                                    )
                                if (!ownerStakingWalletItem.stakingAddrRegistered) {
                                    // owner staking address is *not* registered. We should register it on chain as part of the transaction
                                    ownerStakingAccount.stakingRegCert?.let { stakingRegCert ->
                                        val content =
                                            if ("Shelley" in stakingRegCert.content) {
                                                // we need to convert the shelley cert to a conway cert
                                                walletUtils
                                                    .upgradeShelleyRegcertToConway(
                                                        stakingRegCert,
                                                        protocolParameters
                                                    ).content
                                            } else {
                                                stakingRegCert.content
                                            }
                                        defaultHostConnection.commandWriteFile(
                                            "/tmp/owner.staking.cert",
                                            content
                                        )
                                    } ?: throw IOException("Staking reg cert not found on owner account!")
                                    depositAndFees += protocolParameters.stakeAddressDeposit
                                    certificates.append("--certificate /tmp/owner.staking.cert ")
                                }
                                witnessCount++ // the owner.staking.skey is a witness
                                signingKeys.append("--signing-key-file /tmp/owner.staking.skey ")

                                // 3. Register rewards address on the chain if not yet registered
                                val rewardsStakingAccount =
                                    walletRepository.findByIdOrNull(request.rewardsStakingAccount)
                                        ?: throw IOException("Rewards staking account not found!")
                                defaultHostConnection.commandWriteFile(
                                    "/tmp/rewards.staking.skey",
                                    walletUtils.getSKeyContent(
                                        requireNotNull(rewardsStakingAccount.stakingSkey),
                                        request.spendingPassword
                                    )
                                )
                                defaultHostConnection.commandWriteFile(
                                    "/tmp/rewards.staking.vkey",
                                    requireNotNull(rewardsStakingAccount.stakingVkey?.content)
                                )
                                if (request.rewardsStakingAccount != request.ownerStakingAccount) {
                                    val rewardsStakingWalletItem =
                                        walletUtils.getWalletItem(
                                            defaultHost,
                                            defaultHostConnection,
                                            magicString,
                                            socketPath,
                                            era,
                                            rewardsStakingAccount
                                        )
                                    if (!rewardsStakingWalletItem.stakingAddrRegistered) {
                                        // rewards staking address is *not* registered. We should register it on chain as part of the transaction
                                        rewardsStakingAccount.stakingRegCert?.let { stakingRegCert ->
                                            val content =
                                                if ("Shelley" in stakingRegCert.content) {
                                                    // we need to convert the shelley cert to a conway cert
                                                    walletUtils
                                                        .upgradeShelleyRegcertToConway(
                                                            stakingRegCert,
                                                            protocolParameters
                                                        ).content
                                                } else {
                                                    stakingRegCert.content
                                                }
                                            defaultHostConnection.commandWriteFile(
                                                "/tmp/rewards.staking.cert",
                                                content
                                            )
                                        } ?: throw IOException("Staking reg cert not found on rewards account!")
                                        depositAndFees += protocolParameters.stakeAddressDeposit
                                        certificates.append("--certificate /tmp/rewards.staking.cert ")
                                    }
                                }

                                nodeRepository.findByIdOrNull(request.id)?.let { node ->
                                    val coldSKeyFile =
                                        fileRepository.findByIdOrNull(node.coreSKeyId)
                                            ?: throw IOException("Cold skey not found!")
                                    defaultHostConnection.commandWriteFile(
                                        "/tmp/core.node.skey",
                                        walletUtils.getSKeyContent(coldSKeyFile, request.spendingPassword).trim()
                                    )
                                    val coldVKeyFile =
                                        fileRepository.findByIdOrNull(node.coreVKeyId)
                                            ?: throw IOException("Cold vkey not found!")
                                    defaultHostConnection.commandWriteFile("/tmp/core.node.vkey", coldVKeyFile.content)
                                    val vrfVKeyFile =
                                        fileRepository.findByIdOrNull(node.vrfVKeyId)
                                            ?: throw IOException("vrf vkey not found!")
                                    defaultHostConnection.commandWriteFile("/tmp/core.vrf.vkey", vrfVKeyFile.content)

                                    witnessCount++ // the core.node.skey is always a witness
                                    signingKeys.append("--signing-key-file /tmp/core.node.skey ")

                                    val relays = relayRepository.findByNodeId(requireNotNull(node.id))

                                    // download and get the hash!
                                    defaultHostConnection.command("curl ${node.metadataUrl} --output /tmp/metadata.json")
                                    val metadataHash =
                                        defaultHostConnection
                                            .command(
                                                "${defaultHost.cardanoCliPath} $era stake-pool metadata-hash --pool-metadata-file /tmp/metadata.json"
                                            ).trim()

                                    // 6. create the pool registration certificate
                                    val poolRegcertCommand =
                                        StringBuilder().apply {
                                            append("${defaultHost.cardanoCliPath} $era stake-pool registration-certificate ")
                                            append("--cold-verification-key-file /tmp/core.node.vkey ")
                                            append("--vrf-verification-key-file /tmp/core.vrf.vkey ")
                                            append("--pool-pledge ${request.poolPledge} ")
                                            append("--pool-cost ${request.poolCost} ")
                                            append("--pool-margin ${request.poolMargin} ")
                                            append("--pool-reward-account-verification-key-file /tmp/rewards.staking.vkey ")
                                            append("--pool-owner-stake-verification-key-file /tmp/owner.staking.vkey ")
                                            relays.forEach { relay ->
                                                if (relay.addr.matches(IP4_ADDRESS)) {
                                                    append("--pool-relay-ipv4 ${relay.addr} --pool-relay-port ${relay.port} ")
                                                } else {
                                                    append("--single-host-pool-relay ${relay.addr} --pool-relay-port ${relay.port} ")
                                                }
                                            }
                                            append("--metadata-url ${node.metadataUrl} --metadata-hash $metadataHash ")
                                            append("$magicString ")
                                            append("--out-file /tmp/core.pool.cert")
                                        }
                                    log.debug("poolRegcertCommand: $poolRegcertCommand")
                                    defaultHostConnection.command(poolRegcertCommand.toString())
                                    certificates.append("--certificate /tmp/core.pool.cert ")

                                    // 7. Create delegation certificates for owner(s) if it changed.
                                    if (request.ownerStakingAccount != node.ownerStakingAccountId) {
                                        defaultHostConnection.command(
                                            "${defaultHost.cardanoCliPath} $era stake-address stake-delegation-certificate --stake-verification-key-file /tmp/owner.staking.vkey --cold-verification-key-file /tmp/core.node.vkey --out-file /tmp/owner.deleg.cert"
                                        )
                                        certificates.append("--certificate /tmp/owner.deleg.cert ")
                                    }

                                    // 8. Calculate fees
                                    transaction.append(certificates)
                                    transaction.append("--out-file /tmp/transaction.txbody")
                                    defaultHostConnection.command(transaction.toString())

                                    log.debug("depositAndFees: $depositAndFees")
                                    val feesString =
                                        defaultHostConnection
                                            .command(
                                                "${defaultHost.cardanoCliPath} $era transaction calculate-min-fee --tx-body-file /tmp/transaction.txbody --protocol-params-file /tmp/protocol-parameters.json --tx-in-count ${utxos.size} --tx-out-count 1 $magicString --witness-count $witnessCount --byron-witness-count 0 --output-text"
                                            ).trim()
                                    val fees = feesString.split(" ")[0].toBigInteger()
                                    log.debug("fees: $fees")
                                    depositAndFees += fees
                                    log.debug("final depositAndFees: $depositAndFees")

                                    // 9. Create the transaction
                                    val change = utxos.sumByBigInteger { it.lovelace } - depositAndFees
                                    if (change < BigInteger.ONE) {
                                        throw IOException("Not enough funds to pay depositAndFees of $depositAndFees lovelace!")
                                    }

                                    val tokenChange = StringBuilder()
                                    utxos.toNativeAssetMap().forEach { (currency, amount) ->
                                        if (amount > BigInteger.ZERO) {
                                            tokenChange.append("+$amount $currency")
                                        }
                                    }

                                    val realTransaction =
                                        transaction
                                            .toString()
                                            .replace("--fee 200000 ", "--fee $fees ")
                                            .replace(
                                                "--tx-out ${feePayerAccount.paymentAddr}+$feePayerAccountBalance ",
                                                "--tx-out '${feePayerAccount.paymentAddr}+$change$tokenChange' "
                                            )
                                    log.debug("Pool Transaction Command: $realTransaction")
                                    defaultHostConnection.command(realTransaction)

                                    // 10. Sign the transaction
                                    defaultHostConnection.command(
                                        "${defaultHost.cardanoCliPath} $era transaction sign --tx-body-file /tmp/transaction.txbody $signingKeys $magicString --out-file /tmp/transaction.txsigned"
                                    )

                                    runBlocking {
                                        TransactionCache.withLock {
                                            // 11. Submit the transaction
                                            defaultHostConnection.command(
                                                "${defaultHost.cardanoCliPath} $era transaction submit --tx-file /tmp/transaction.txsigned $magicString $socketPath"
                                            )
                                            val txid =
                                                defaultHostConnection
                                                    .command(
                                                        "${defaultHost.cardanoCliPath} $era transaction txid --tx-body-file /tmp/transaction.txbody --output-text"
                                                    ).trim()
                                            val txSigned =
                                                defaultHostConnection.commandReadFile("/tmp/transaction.txsigned")
                                            TransactionCache.put(txid, txSigned)

                                            val cborBytes = txSignedAdapter.fromJson(txSigned)!!.cborHex.hexToByteArray()
                                            ledgerDao.updateLiveLedgerState(txid, cborBytes)
                                            transactionRepository.save(Transaction(txid = txid))
                                        }
                                    }

                                    // 13. Save node information
                                    nodeRepository.save(
                                        node.copy(
                                            ownerStakingAccountId = request.ownerStakingAccount,
                                            rewardsStakingAccountId = request.rewardsStakingAccount,
                                            poolPledge = request.poolPledge,
                                            poolCost = request.poolCost,
                                            poolMargin = request.poolMargin
                                        )
                                    )

                                    // send all to the client for ui updates
                                    val nodes =
                                        nodeRepository.findAll(Sort.by(Sort.Direction.ASC, "name")).filter { !it.isDeleted }
                                    webSocketTemplate.convertAndSend(
                                        "/topic/messages",
                                        SocketResponse.Success(type = "nodes", data = nodes)
                                    )
                                } ?: throw IOException("Node not found!")
                            } finally {
                                // Cleanup
                                defaultHostConnection.command(
                                    "rm -f /tmp/protocol-parameters.json /tmp/transaction.txbody /tmp/transaction.txsigned /tmp/core.pool.cert  /tmp/feepayer.payment.skey /tmp/owner.staking.skey /tmp/owner.staking.vkey /tmp/owner.staking.cert /tmp/owner.deleg.cert /tmp/rewards.staking.skey /tmp/rewards.staking.vkey /tmp/rewards.staking.cert /tmp/core.node.skey /tmp/core.node.vkey /tmp/core.vrf.skey /tmp/core.vrf.vkey /tmp/metadata.json"
                                )
                            }
                        } ?: throw IOException("Host not found for default node!")
                    } ?: throw IOException("Genesis file for default node not found!")
                } ?: throw IOException("Default node not found!")
                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Success("updatepoolconfig", "Pool Update Success!")
                )
            } catch (e: Throwable) {
                log.error("Error Updating Pool Config!", e)
                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Error(type = "updatepoolconfig", exception = e)
                )
                // rethrow so db transaction is rolled back
                throw RuntimeException(e)
            }
        }

        @MessageMapping("/rotatekes")
        @Transactional
        fun rotateKes(request: RotateKesRequest) {
            try {
                if (!walletUtils.isValidSpendingPassword(request.spendingPassword)) {
                    throw IllegalArgumentException("Invalid spending password!")
                }
                val defaultNode = nodeRepository.findDefault() ?: throw IOException("Default node not found!")
                val genesisFile =
                    fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)
                        ?: throw IOException("Genesis shelley not found!")
                val genesisShelley = shelleyGenesisAdapter.fromJson(genesisFile.content)!!
                val byronToShelleyEpochs = CardanoNetworkEpochs.byronToShelleyEpochs(genesisShelley)
                val magicString =
                    if (genesisShelley.networkId.equals("testnet", ignoreCase = true)) {
                        "--testnet-magic ${genesisShelley.networkMagic}"
                    } else {
                        "--mainnet"
                    }

                val genesisByronFile =
                    fileRepository.findByIdOrNull((defaultNode.genesisByronFileId))
                        ?: throw IOException("Genesis byron not found!")
                val genesisByron = byronGenesisAdapter.fromJson(genesisByronFile.content)!!

                val defaultHost =
                    hostRepository.findByIdOrNull(defaultNode.hostId)
                        ?: throw IOException("Default host not found!")
                val defaultHostConnection = HostConnection(defaultHost, defaultNode)
                val node = nodeRepository.findByIdOrNull(request.id) ?: throw IOException("Invalid node id: ${request.id}")
                val host = hostRepository.findByIdOrNull(node.hostId) ?: throw IOException("Host not found!")
                val hostConnection = HostConnection(host, node)
                val socketPath = "--socket-path ${defaultHost.nodeHomePath}/${defaultNode.name}/db/socket"
                try {
                    val era = cardanoRepository.getEra(defaultHost, defaultNode, magicString, socketPath)
                    // fetch cold keys and vrf
                    val coreSKeyContent =
                        fileRepository.findByIdOrNull(node.coreSKeyId)?.let { coreSKey ->
                            walletUtils.getSKeyContent(coreSKey, request.spendingPassword)
                        } ?: throw IOException("Could not find core skey!")
                    defaultHostConnection.commandWriteFile("/tmp/core.node.skey", coreSKeyContent)
                    val coreCounter =
                        fileRepository.findByIdOrNull(node.coreCounterId)
                            ?: throw IOException("Could not find core counter!")
                    defaultHostConnection.commandWriteFile(
                        "/tmp/core.node.counter",
                        coreCounter.content
                    )
                    val vrfSKeyContent =
                        fileRepository.findByIdOrNull(node.vrfSKeyId)?.let { vrfSKey ->
                            walletUtils.getSKeyContent(vrfSKey, request.spendingPassword)
                        } ?: throw IOException("Could not find vrf skey!")

                    // generate new KES keys
                    defaultHostConnection.command(
                        "${defaultHost.cardanoCliPath} $era node key-gen-KES --verification-key-file /tmp/core.kes.vkey --signing-key-file /tmp/core.kes.skey"
                    )
                    val kesSKeyContent = defaultHostConnection.commandReadFile("/tmp/core.kes.skey")
                    val kesVKeyContent = defaultHostConnection.commandReadFile("/tmp/core.kes.vkey")
                    val kesSKey =
                        com.swiftmako.jormanager.entities.File(
                            name = "${node.name}.kes.skey",
                            content =
                                walletUtils.encryptSKeyContent(
                                    kesSKeyContent,
                                    request.spendingPassword
                                )
                        )
                    fileRepository.findByIdOrNull(node.kesSKeyId)?.let { oldKesSkey ->
                        fileRepository.save(oldKesSkey.copy(name = "${oldKesSkey.name}-${System.currentTimeMillis()}"))
                    }
                    val kesVKey =
                        com.swiftmako.jormanager.entities.File(
                            name = "${node.name}.kes.vkey",
                            content = kesVKeyContent
                        )
                    fileRepository.findByIdOrNull(node.kesVKeyId)?.let { oldKesVkey ->
                        fileRepository.save(oldKesVkey.copy(name = "${oldKesVkey.name}-${System.currentTimeMillis()}"))
                    }
                    val kesSKeyId = fileRepository.save(kesSKey).id!!
                    val kesVKeyId = fileRepository.save(kesVKey).id!!

                    // calculate current kes period
                    val slotLength = genesisShelley.slotLength
                    val epochLength = genesisShelley.epochLength
                    val slotsPerKESPeriod = genesisShelley.slotsPerKESPeriod
                    val startTimeByron = genesisByron.startTime
                    val startTimeGenesis = genesisShelley.systemStart

                    val startTimeSec = defaultHostConnection.command("date --date=$startTimeGenesis +%s").trim().toLong()
                    val transTimeEnd = startTimeSec + (byronToShelleyEpochs * epochLength)
                    val byronSlots = (startTimeSec - startTimeByron) / 20L
                    val transSlots = (byronToShelleyEpochs * epochLength) / 20L

                    val currentTimeSec = defaultHostConnection.command("date -u +%s").trim().toLong()

                    val currentSlot =
                        if (currentTimeSec < transTimeEnd) {
                            // in transition phase between shelley genesis start and transition end
                            (byronSlots + (currentTimeSec - startTimeSec) / 20L)
                        } else {
                            // after transition phase
                            (byronSlots + transSlots + ((currentTimeSec - transTimeEnd) / slotLength))
                        }
                    var currentKESPeriod = ((currentSlot - byronSlots) / (slotsPerKESPeriod * slotLength))
                    if (currentKESPeriod < 0L) {
                        currentKESPeriod = 0L
                    }

                    val maxKESEvolutions = genesisShelley.maxKESEvolutions
                    val expiresKESPeriod = currentKESPeriod + maxKESEvolutions
                    val kesExpireTimeSec = (currentTimeSec + (slotLength * maxKESEvolutions * slotsPerKESPeriod))
                    val kesExpireDate = defaultHostConnection.command("date --date=@$kesExpireTimeSec").trim()
                    log.warn(
                        "RotatingKES: currentKESPeriod: $currentKESPeriod, expiresKESPeriod: $expiresKESPeriod, expireDate: $kesExpireDate"
                    )

                    defaultHostConnection.command(
                        "${defaultHost.cardanoCliPath} $era node issue-op-cert --hot-kes-verification-key-file /tmp/core.kes.vkey --cold-signing-key-file /tmp/core.node.skey --operational-certificate-issue-counter /tmp/core.node.counter --kes-period $currentKESPeriod --out-file /tmp/core.node.opcert"
                    )
                    val opcertContent = defaultHostConnection.commandReadFile("/tmp/core.node.opcert")
                    val opcertFile =
                        com.swiftmako.jormanager.entities.File(
                            name = "${node.name}.node.opcert",
                            content = opcertContent
                        )
                    val opcertId = fileRepository.save(opcertFile).id!!
                    fileRepository.findByIdOrNull(node.opcertId)?.let { oldOpcertFile ->
                        fileRepository.save(oldOpcertFile.copy(name = "${oldOpcertFile.name}-${System.currentTimeMillis()}"))
                    }

                    val coreCounterContent = defaultHostConnection.commandReadFile("/tmp/core.node.counter")
                    fileRepository.save(coreCounter.copy(content = coreCounterContent))

                    nodeRepository.save(
                        node.copy(
                            kesSKeyId = kesSKeyId,
                            kesVKeyId = kesVKeyId,
                            opcertId = opcertId,
                            kesExpireDate = kesExpireDate,
                            kesExpireTimeSec = kesExpireTimeSec
                        )
                    )

                    // upload kes and opcert
                    // 13. Create and Save node information
                    val (coreNode, nodeFolder, isUpdateBulkCredentials) =
                        if (node.type == "core") {
                            val nodeFolder = "${host.nodeHomePath}${File.separator}${node.name}"
                            createKesVrfOpcert(
                                node.name,
                                kesSKeyContent,
                                vrfSKeyContent,
                                opcertContent,
                                hostConnection,
                                nodeFolder
                            )
                            Triple(node, nodeFolder, nodeRepository.findByParentId(node.id!!).isNotEmpty())
                        } else {
                            // node.type == "pool"
                            val coreNode =
                                nodeRepository.findByIdOrNull(node.parentId!!)
                                    ?: throw IOException("pool does not have a parent!")
                            val nodeFolder = "${host.nodeHomePath}${File.separator}${coreNode.name}"
                            createKesVrfOpcert(
                                node.name,
                                kesSKeyContent,
                                vrfSKeyContent,
                                opcertContent,
                                hostConnection,
                                nodeFolder
                            )
                            Triple(coreNode, nodeFolder, true)
                        }

                    if (isUpdateBulkCredentials) {
                        val credentials = mutableListOf<MutableList<Key>>()
                        // core node's pool
                        credentials.add(
                            mutableListOf(
                                keyFileJsonAdapter.fromJson(fileRepository.findByIdOrNull(coreNode.opcertId)!!.content)!!,
                                keyFileJsonAdapter.fromJson(
                                    walletUtils.getSKeyContent(
                                        fileRepository.findByIdOrNull(
                                            coreNode.vrfSKeyId
                                        )!!,
                                        request.spendingPassword
                                    )
                                )!!,
                                keyFileJsonAdapter.fromJson(
                                    walletUtils.getSKeyContent(
                                        fileRepository.findByIdOrNull(
                                            coreNode.kesSKeyId
                                        )!!,
                                        request.spendingPassword
                                    )
                                )!!,
                            )
                        )
                        // all child pools (including the one being updated
                        nodeRepository.findByParentId(coreNode.id!!).forEach { pool ->
                            credentials.add(
                                mutableListOf(
                                    keyFileJsonAdapter.fromJson(fileRepository.findByIdOrNull(pool.opcertId)!!.content)!!,
                                    keyFileJsonAdapter.fromJson(
                                        walletUtils.getSKeyContent(
                                            fileRepository.findByIdOrNull(
                                                pool.vrfSKeyId
                                            )!!,
                                            request.spendingPassword
                                        )
                                    )!!,
                                    keyFileJsonAdapter.fromJson(
                                        walletUtils.getSKeyContent(
                                            fileRepository.findByIdOrNull(
                                                pool.kesSKeyId
                                            )!!,
                                            request.spendingPassword
                                        )
                                    )!!,
                                )
                            )
                        }
                        createBulkCredentials(hostConnection, nodeFolder, credentials)
                    }
                } finally {
                    // cleanup
                    defaultHostConnection.command(
                        "rm -f /tmp/core.node.skey /tmp/core.node.counter /tmp/core.kes.skey /tmp/core.kes.vkey /tmp/core.node.opcert"
                    )
                }

                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Success("rotatekes", "KES rotated successfully, restart node to activate!")
                )
            } catch (e: Throwable) {
                log.error("Error Rotating KES!", e)
                webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Error(type = "rotatekes", exception = e))
                // rethrow so db transaction is rolled back
                throw RuntimeException(e)
            }
        }

        @MessageMapping("/getmetadata")
        fun getMetadata(nodeId: Long) {
            try {
                val node = nodeRepository.findByIdOrNull(nodeId) ?: throw IOException("Node id not found!")
                val metadataRequest =
                    Request
                        .Builder()
                        .get()
                        .cacheControl(CacheControl.FORCE_NETWORK)
                        .url(node.metadataUrl!!)
                        .build()
                val metadataResponse = okHttpClient.newCall(metadataRequest).execute()
                if (metadataResponse.isSuccessful) {
                    val metadata = requireNotNull(metadataResponse.body).source().use { source -> metadataAdapter.fromJson(source) }
                    val extendedMetadataRequest =
                        Request
                            .Builder()
                            .get()
                            .cacheControl(CacheControl.FORCE_NETWORK)
                            .url(node.extendedMetadataUrl!!)
                            .build()
                    val extendedMetadataResponse = okHttpClient.newCall(extendedMetadataRequest).execute()
                    if (extendedMetadataResponse.isSuccessful) {
                        val extendedMetadata =
                            requireNotNull(extendedMetadataResponse.body)
                                .source()
                                .use { source -> extendedMetadataAdapter.fromJson(source) }
                        webSocketTemplate.convertAndSend(
                            "/topic/messages",
                            SocketResponse.Success(
                                type = "getmetadata",
                                data =
                                    mapOf(
                                        "metadata" to metadata,
                                        "extendedMetadata" to extendedMetadata
                                    )
                            )
                        )
                    } else {
                        throw IOException("Unable to download extendedmetadata!")
                    }
                } else {
                    throw IOException("Unable to download metadata!")
                }
            } catch (e: Throwable) {
                log.error("Error fetching metadata!", e)
                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Error(type = "getmetadata", exception = e)
                )
            }
        }

        @MessageMapping("/updatemetadata")
        @Transactional
        fun updateMetadata(request: UpdateMetadataRequest) {
            try {
                if (!walletUtils.isValidSpendingPassword(request.spendingPassword)) {
                    throw IllegalArgumentException("Invalid spending password!")
                }
                val defaultNode = nodeRepository.findDefault() ?: throw IOException("Default node not found!")
                val genesisFile =
                    fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)
                        ?: throw IOException("Genesis file for default node not found!")
                val genesis = shelleyGenesisAdapter.fromJson(genesisFile.content)!!
                val magicString =
                    if (genesis.networkId.equals("testnet", ignoreCase = true)) {
                        "--testnet-magic ${genesis.networkMagic}"
                    } else {
                        "--mainnet"
                    }

                val defaultHost =
                    hostRepository.findByIdOrNull(defaultNode.hostId)
                        ?: throw IOException("Host not found for default node!")
                val defaultHostConnection = HostConnection(defaultHost, defaultNode)
                val socketPath = "--socket-path ${defaultHost.nodeHomePath}/${defaultNode.name}/db/socket"
                try {
                    val era = cardanoRepository.getEra(defaultHost, defaultNode, magicString, socketPath)
                    val protocolParamsJson =
                        defaultHostConnection
                            .command(
                                "${defaultHost.cardanoCliPath} $era query protocol-parameters $magicString $socketPath --output-json"
                            ).trim()
                    defaultHostConnection.commandWriteFile("/tmp/protocol-parameters.json", protocolParamsJson)
                    val protocolParameters =
                        protocolParamsAdapter.fromJson(protocolParamsJson)
                            ?: throw IOException("Invalid protocol params!")

                    // 1. Create a transaction to dump EVERYTHING into
                    var depositAndFees = BigInteger.ZERO
                    var witnessCount = 0
                    val transaction = StringBuilder()
                    val certificates = StringBuilder()
                    val signingKeys = StringBuilder()
                    transaction.append("${defaultHost.cardanoCliPath} $era transaction build-raw ")
                    val feePayerAccount =
                        walletRepository.findByIdOrNull(request.registrationFeesAccount)
                            ?: throw IOException("Registration fees account not found!")
                    val utxos =
                        walletUtils.getUtxos(
                            defaultHost,
                            defaultHostConnection,
                            magicString,
                            socketPath,
                            era,
                            feePayerAccount.paymentAddr
                        )
                    utxos.forEach { utxo ->
                        transaction.append("--tx-in ${utxo.hash}#${utxo.ix} ")
                    }
                    val feePayerAccountBalance = utxos.sumByBigInteger { it.lovelace }
                    log.debug("feePayerAccount balance: {}", feePayerAccountBalance)
                    witnessCount++ // fee payer is a witness
                    defaultHostConnection.commandWriteFile(
                        "/tmp/feepayer.payment.skey",
                        walletUtils.getSKeyContent(
                            requireNotNull(feePayerAccount.paymentSkey),
                            request.spendingPassword
                        )
                    )
                    signingKeys.append("--signing-key-file /tmp/feepayer.payment.skey ")

                    // initial dummy value to return change to the fee payer account.
                    // We'll replace this with the actual change to return later
                    transaction.append("--tx-out ${feePayerAccount.paymentAddr}+$feePayerAccountBalance ")

                    val queryTipJson =
                        defaultHostConnection
                            .command("${defaultHost.cardanoCliPath} $era query tip $magicString $socketPath --output-json")
                            .trim()
                    val ttl = queryTipAdapter.fromJson(queryTipJson)?.let { it.slot + 21600 } ?: -1
                    transaction.append("--invalid-hereafter $ttl ")
                    transaction.append("--fee 200000 ")

                    nodeRepository.findByIdOrNull(request.id)?.let { node ->
                        // 2. Register owner address on the chain if not yet registered
                        val ownerStakingAccount =
                            walletRepository.findByIdOrNull(node.ownerStakingAccountId)
                                ?: throw IOException("Owner staking account not found!")
                        defaultHostConnection.commandWriteFile(
                            "/tmp/owner.staking.skey",
                            walletUtils.getSKeyContent(
                                requireNotNull(ownerStakingAccount.stakingSkey),
                                request.spendingPassword
                            )
                        )
                        defaultHostConnection.commandWriteFile(
                            "/tmp/owner.staking.vkey",
                            requireNotNull(ownerStakingAccount.stakingVkey?.content)
                        )
                        val ownerStakingWalletItem =
                            walletUtils.getWalletItem(
                                defaultHost,
                                defaultHostConnection,
                                magicString,
                                socketPath,
                                era,
                                ownerStakingAccount
                            )
                        if (!ownerStakingWalletItem.stakingAddrRegistered) {
                            // owner staking address is *not* registered. We should register it on chain as part of the transaction
                            ownerStakingAccount.stakingRegCert?.let { stakingRegCert ->
                                val content =
                                    if ("Shelley" in stakingRegCert.content) {
                                        // we need to convert the shelley cert to a conway cert
                                        walletUtils
                                            .upgradeShelleyRegcertToConway(
                                                stakingRegCert,
                                                protocolParameters
                                            ).content
                                    } else {
                                        stakingRegCert.content
                                    }
                                defaultHostConnection.commandWriteFile(
                                    "/tmp/owner.staking.cert",
                                    content
                                )
                            } ?: throw IOException("Staking reg cert not found on owner account!")
                            depositAndFees += protocolParameters.stakeAddressDeposit
                            certificates.append("--certificate /tmp/owner.staking.cert ")
                        }
                        witnessCount++ // the owner.staking.skey is a witness
                        signingKeys.append("--signing-key-file /tmp/owner.staking.skey ")

                        // 3. Register rewards address on the chain if not yet registered
                        val rewardsStakingAccount =
                            walletRepository.findByIdOrNull(node.rewardsStakingAccountId)
                                ?: throw IOException("Rewards staking account not found!")
                        defaultHostConnection.commandWriteFile(
                            "/tmp/rewards.staking.skey",
                            walletUtils.getSKeyContent(
                                requireNotNull(rewardsStakingAccount.stakingSkey),
                                request.spendingPassword
                            )
                        )
                        defaultHostConnection.commandWriteFile(
                            "/tmp/rewards.staking.vkey",
                            requireNotNull(rewardsStakingAccount.stakingVkey?.content)
                        )
                        if (node.rewardsStakingAccountId != node.ownerStakingAccountId) {
                            val rewardsStakingWalletItem =
                                walletUtils.getWalletItem(
                                    defaultHost,
                                    defaultHostConnection,
                                    magicString,
                                    socketPath,
                                    era,
                                    rewardsStakingAccount
                                )
                            if (!rewardsStakingWalletItem.stakingAddrRegistered) {
                                // rewards staking address is *not* registered. We should register it on chain as part of the transaction
                                rewardsStakingAccount.stakingRegCert?.let { stakingRegCert ->
                                    val content =
                                        if ("Shelley" in stakingRegCert.content) {
                                            // we need to convert the shelley cert to a conway cert
                                            walletUtils
                                                .upgradeShelleyRegcertToConway(
                                                    stakingRegCert,
                                                    protocolParameters
                                                ).content
                                        } else {
                                            stakingRegCert.content
                                        }
                                    defaultHostConnection.commandWriteFile(
                                        "/tmp/rewards.staking.cert",
                                        content
                                    )
                                } ?: throw IOException("Staking reg cert not found on rewards account!")
                                depositAndFees += protocolParameters.stakeAddressDeposit
                                certificates.append("--certificate /tmp/rewards.staking.cert ")
                            }
                        }

                        val coldSKeyFile =
                            fileRepository.findByIdOrNull(node.coreSKeyId)
                                ?: throw IOException("Cold skey not found!")
                        defaultHostConnection.commandWriteFile(
                            "/tmp/core.node.skey",
                            walletUtils.getSKeyContent(coldSKeyFile, request.spendingPassword).trim()
                        )
                        val coldVKeyFile =
                            fileRepository.findByIdOrNull(node.coreVKeyId)
                                ?: throw IOException("Cold vkey not found!")
                        defaultHostConnection.commandWriteFile("/tmp/core.node.vkey", coldVKeyFile.content)
                        val vrfVKeyFile =
                            fileRepository.findByIdOrNull(node.vrfVKeyId)
                                ?: throw IOException("vrf vkey not found!")
                        defaultHostConnection.commandWriteFile("/tmp/core.vrf.vkey", vrfVKeyFile.content)

                        witnessCount++ // the core.node.skey is always a witness
                        signingKeys.append("--signing-key-file /tmp/core.node.skey ")

                        val relays = relayRepository.findByNodeId(requireNotNull(node.id))

                        // 5. Create and upload metadata files
                        var itnPrivateKeyId = -1L
                        var itnPublicKeyId = -1L
                        val itnWitnessSign =
                            if (request.extended?.itn?.privateKey != null && defaultHost.jcliPath != null) {
                                val itnPrivateKey =
                                    com.swiftmako.jormanager.entities.File(
                                        name = "${request.name}.itn.skey",
                                        content =
                                            walletUtils.encryptSKeyContent(
                                                request.extended.itn.privateKey
                                                    .trim(),
                                                request.spendingPassword
                                            )
                                    )
                                itnPrivateKeyId = fileRepository.save(itnPrivateKey).id!!
                                defaultHostConnection.commandWriteFile("/tmp/core.pool.id", node.poolId!!)
                                defaultHostConnection.commandWriteFile(
                                    "/tmp/core.itn.skey",
                                    request.extended.itn.privateKey
                                        .trim()
                                )
                                defaultHostConnection
                                    .command(
                                        "${defaultHost.jcliPath} $era key sign --secret-key /tmp/core.itn.skey /tmp/core.pool.id"
                                    ).trim()
                            } else {
                                if (node.itnPrivateKeyId > -1) {
                                    val itnPrivateKey =
                                        fileRepository.findByIdOrNull(node.itnPrivateKeyId)
                                            ?: throw IOException("ITN private key not found in db!")
                                    defaultHostConnection.commandWriteFile("/tmp/core.pool.id", node.poolId!!)
                                    defaultHostConnection.commandWriteFile(
                                        "/tmp/core.itn.skey",
                                        walletUtils.getSKeyContent(itnPrivateKey, request.spendingPassword)
                                    )
                                    defaultHostConnection
                                        .command(
                                            "${defaultHost.jcliPath} $era key sign --secret-key /tmp/core.itn.skey /tmp/core.pool.id"
                                        ).trim()
                                } else {
                                    null
                                }
                            }
                        val itnWitnessOwner =
                            if (request.extended?.itn?.publicKey != null) {
                                val itnPublicKey =
                                    com.swiftmako.jormanager.entities.File(
                                        name = "${request.name}.itn.vkey",
                                        content =
                                            request.extended.itn.publicKey
                                                .trim()
                                    )
                                itnPublicKeyId = fileRepository.save(itnPublicKey).id!!
                                request.extended.itn.publicKey
                                    .trim()
                            } else {
                                if (node.itnPublicKeyId > -1) {
                                    val itnPublicKey =
                                        fileRepository.findByIdOrNull(node.itnPublicKeyId)
                                            ?: throw IOException("ITN public key not found in db!")
                                    itnPublicKey.content
                                } else {
                                    null
                                }
                            }

                        val extendedMetadata =
                            ExtendedMetadata(
                                itn =
                                    itnWitnessSign?.let {
                                        Itn(
                                            owner = itnWitnessOwner,
                                            witness = itnWitnessSign
                                        )
                                    },
                                info =
                                    Info(
                                        urlPngIcon64x64 = request.extended?.info?.icon64,
                                        urlPngLogo = request.extended?.info?.logo,
                                        location = request.extended?.info?.location,
                                        social =
                                            Social(
                                                twitterHandle =
                                                    request.extended
                                                        ?.info
                                                        ?.social
                                                        ?.twitter,
                                                telegramHandle =
                                                    request.extended
                                                        ?.info
                                                        ?.social
                                                        ?.telegram,
                                                facebookHandle =
                                                    request.extended
                                                        ?.info
                                                        ?.social
                                                        ?.facebook,
                                                youtubeHandle =
                                                    request.extended
                                                        ?.info
                                                        ?.social
                                                        ?.youtube,
                                                twitchHandle =
                                                    request.extended
                                                        ?.info
                                                        ?.social
                                                        ?.twitch,
                                                discordHandle =
                                                    request.extended
                                                        ?.info
                                                        ?.social
                                                        ?.discord,
                                                githubHandle =
                                                    request.extended
                                                        ?.info
                                                        ?.social
                                                        ?.github
                                            ),
                                        company =
                                            Company(
                                                name =
                                                    request.extended
                                                        ?.info
                                                        ?.company
                                                        ?.name,
                                                addr =
                                                    request.extended
                                                        ?.info
                                                        ?.company
                                                        ?.addr,
                                                city =
                                                    request.extended
                                                        ?.info
                                                        ?.company
                                                        ?.city,
                                                country =
                                                    request.extended
                                                        ?.info
                                                        ?.company
                                                        ?.country,
                                                companyId =
                                                    request.extended
                                                        ?.info
                                                        ?.company
                                                        ?.companyId,
                                                vatId =
                                                    request.extended
                                                        ?.info
                                                        ?.company
                                                        ?.vatId
                                            ),
                                        about =
                                            About(
                                                me =
                                                    request.extended
                                                        ?.info
                                                        ?.about
                                                        ?.me,
                                                server =
                                                    request.extended
                                                        ?.info
                                                        ?.about
                                                        ?.server,
                                                company =
                                                    request.extended
                                                        ?.info
                                                        ?.about
                                                        ?.company
                                            ),
                                        rss = request.extended?.info?.rss
                                    ),
                                telegramAdminHandle =
                                    request.extended?.telegramAdminHandle?.let { listOf(it) }
                                        ?: emptyList(),
                                myPoolIds = listOf(node.poolId!!),
                                whenSaturedThenRecommend = null,
                                adapoolsVerify = request.extended?.adapoolsVerify,
                            )

                        val extendedMetadataJson = extendedMetadataAdapter.indent(" ").toJson(extendedMetadata)
                        val extendedMetadataUrl =
                            if (request.custom) {
                                request.extendedMetadataUrl
                            } else {
                                uploadMetadata(extendedMetadataJson)
                            }
                        log.debug("extendedMetadataUrl: $extendedMetadataUrl")

                        val metadata =
                            com.swiftmako.jormanager.model.metadata.pool.Metadata(
                                name = requireNotNull(request.name),
                                description = requireNotNull(request.description),
                                ticker = requireNotNull(request.ticker),
                                homepage = requireNotNull(request.homepage),
                                extended = extendedMetadataUrl
                            )
                        val metadataJson = metadataAdapter.indent(" ").toJson(metadata)
                        val metadataUrl =
                            if (request.custom) {
                                request.metadataUrl
                            } else {
                                uploadMetadata(metadataJson)
                            }

                        // download and get the hash!
                        defaultHostConnection.command("curl $metadataUrl --output /tmp/metadata.json")
                        val metadataHash =
                            defaultHostConnection
                                .command(
                                    "${defaultHost.cardanoCliPath} $era stake-pool metadata-hash --pool-metadata-file /tmp/metadata.json"
                                ).trim()
                        log.debug("metadata hash for $metadataUrl is $metadataHash")

                        // 6. create the pool registration certificate
                        val poolRegcertCommand =
                            StringBuilder().apply {
                                append("${defaultHost.cardanoCliPath} $era stake-pool registration-certificate ")
                                append("--cold-verification-key-file /tmp/core.node.vkey ")
                                append("--vrf-verification-key-file /tmp/core.vrf.vkey ")
                                append("--pool-pledge ${node.poolPledge} ")
                                append("--pool-cost ${node.poolCost} ")
                                append("--pool-margin ${node.poolMargin} ")
                                append("--pool-reward-account-verification-key-file /tmp/rewards.staking.vkey ")
                                append("--pool-owner-stake-verification-key-file /tmp/owner.staking.vkey ")
                                relays.forEach { relay ->
                                    if (relay.addr.matches(IP4_ADDRESS)) {
                                        append("--pool-relay-ipv4 ${relay.addr} --pool-relay-port ${relay.port} ")
                                    } else {
                                        append("--single-host-pool-relay ${relay.addr} --pool-relay-port ${relay.port} ")
                                    }
                                }
                                append("--metadata-url $metadataUrl --metadata-hash $metadataHash ")
                                append("$magicString ")
                                append("--out-file /tmp/core.pool.cert")
                            }
                        log.debug("poolRegcertCommand: $poolRegcertCommand")
                        defaultHostConnection.command(poolRegcertCommand.toString())
                        certificates.append("--certificate /tmp/core.pool.cert ")

                        // 8. Calculate fees
                        transaction.append(certificates)
                        transaction.append("--out-file /tmp/transaction.txbody")
                        defaultHostConnection.command(transaction.toString())

                        log.debug("depositAndFees: $depositAndFees")
                        val feesString =
                            defaultHostConnection
                                .command(
                                    "${defaultHost.cardanoCliPath} $era transaction calculate-min-fee --tx-body-file /tmp/transaction.txbody --protocol-params-file /tmp/protocol-parameters.json --tx-in-count ${utxos.size} --tx-out-count 1 $magicString --witness-count $witnessCount --byron-witness-count 0 --output-text"
                                ).trim()
                        val fees = feesString.split(" ")[0].toBigInteger()
                        log.debug("fees: $fees")
                        depositAndFees += fees
                        log.debug("final depositAndFees: $depositAndFees")

                        // 9. Create the transaction
                        val change = utxos.sumByBigInteger { it.lovelace } - depositAndFees
                        if (change < BigInteger.ONE) {
                            throw IOException("Not enough funds to pay depositAndFees of $depositAndFees lovelace!")
                        }

                        val tokenChange = StringBuilder()
                        utxos.toNativeAssetMap().forEach { (currency, amount) ->
                            if (amount > BigInteger.ZERO) {
                                tokenChange.append("+$amount $currency")
                            }
                        }

                        val realTransaction =
                            transaction
                                .toString()
                                .replace("--fee 200000 ", "--fee $fees ")
                                .replace(
                                    "--tx-out ${feePayerAccount.paymentAddr}+$feePayerAccountBalance ",
                                    "--tx-out '${feePayerAccount.paymentAddr}+$change$tokenChange' "
                                )
                        log.debug("Pool Transaction Command: $realTransaction")
                        defaultHostConnection.command(realTransaction)

                        // 10. Sign the transaction
                        defaultHostConnection.command(
                            "${defaultHost.cardanoCliPath} $era transaction sign --tx-body-file /tmp/transaction.txbody $signingKeys $magicString --out-file /tmp/transaction.txsigned"
                        )

                        runBlocking {
                            TransactionCache.withLock {
                                // 11. Submit the transaction
                                defaultHostConnection.command(
                                    "${defaultHost.cardanoCliPath} $era transaction submit --tx-file /tmp/transaction.txsigned $magicString $socketPath"
                                )
                                val txid =
                                    defaultHostConnection
                                        .command(
                                            "${defaultHost.cardanoCliPath} $era transaction txid --tx-body-file /tmp/transaction.txbody --output-text"
                                        ).trim()
                                val txSigned = defaultHostConnection.commandReadFile("/tmp/transaction.txsigned")
                                TransactionCache.put(txid, txSigned)

                                val cborBytes = txSignedAdapter.fromJson(txSigned)!!.cborHex.hexToByteArray()
                                ledgerDao.updateLiveLedgerState(txid, cborBytes)
                                transactionRepository.save(Transaction(txid = txid))
                            }
                        }

                        // 13. Save node information
                        nodeRepository.save(
                            node.copy(
                                metadataUrl = metadataUrl,
                                extendedMetadataUrl = extendedMetadataUrl,
                                itnPrivateKeyId = itnPrivateKeyId,
                                itnPublicKeyId = itnPublicKeyId
                            )
                        )

                        // send all to the client for ui updates
                        val nodes = nodeRepository.findAll(Sort.by(Sort.Direction.ASC, "name")).filter { !it.isDeleted }
                        webSocketTemplate.convertAndSend(
                            "/topic/messages",
                            SocketResponse.Success(type = "nodes", data = nodes)
                        )
                    } ?: throw IOException("Node not found!")
                } finally {
                    // Cleanup
                    defaultHostConnection.command(
                        "rm -f /tmp/protocol-parameters.json /tmp/transaction.txbody /tmp/transaction.txsigned /tmp/core.pool.cert  /tmp/feepayer.payment.skey /tmp/owner.staking.skey /tmp/owner.staking.vkey /tmp/owner.staking.cert /tmp/owner.deleg.cert /tmp/rewards.staking.skey /tmp/rewards.staking.vkey /tmp/rewards.staking.cert /tmp/core.node.skey /tmp/core.node.vkey /tmp/core.vrf.skey /tmp/core.vrf.vkey /tmp/metadata.json"
                    )
                }

                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Success(
                        type = "updatemetadata",
                        data = "Metadata Update Success!. It may take several hours for wallets, pooltool, and adapools, etc to see metadata changes."
                    )
                )
            } catch (e: Throwable) {
                log.error("Error Updating Metadata!", e)
                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Error(type = "updatemetadata", exception = e)
                )
                // rethrow so db transaction is rolled back
                throw RuntimeException(e)
            }
        }

        @MessageMapping("/editrelays")
        @Transactional
        fun editRelays(request: EditRelaysRequest) {
            try {
                if (!walletUtils.isValidSpendingPassword(request.spendingPassword)) {
                    throw IllegalArgumentException("Invalid spending password!")
                }

                val defaultNode = nodeRepository.findDefault() ?: throw IOException("Default node not found!")
                val genesisFile =
                    fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)
                        ?: throw IOException("Genesis file for default node not found!")
                val genesis = shelleyGenesisAdapter.fromJson(genesisFile.content)!!
                val magicString =
                    if (genesis.networkId.equals("testnet", ignoreCase = true)) {
                        "--testnet-magic ${genesis.networkMagic}"
                    } else {
                        "--mainnet"
                    }

                val defaultHost =
                    hostRepository.findByIdOrNull(defaultNode.hostId)
                        ?: throw IOException("Host not found for default node!")
                val defaultHostConnection = HostConnection(defaultHost, defaultNode)
                val socketPath = "--socket-path ${defaultHost.nodeHomePath}/${defaultNode.name}/db/socket"
                try {
                    val era = cardanoRepository.getEra(defaultHost, defaultNode, magicString, socketPath)
                    val protocolParamsJson =
                        defaultHostConnection
                            .command(
                                "${defaultHost.cardanoCliPath} $era query protocol-parameters $magicString $socketPath --output-json"
                            ).trim()
                    defaultHostConnection.commandWriteFile("/tmp/protocol-parameters.json", protocolParamsJson)
                    val protocolParameters =
                        protocolParamsAdapter.fromJson(protocolParamsJson)
                            ?: throw IOException("Invalid protocol params!")

                    // 1. Create a transaction to dump EVERYTHING into
                    var depositAndFees = BigInteger.ZERO
                    var witnessCount = 0
                    val transaction = StringBuilder()
                    val certificates = StringBuilder()
                    val signingKeys = StringBuilder()
                    transaction.append("${defaultHost.cardanoCliPath} $era transaction build-raw ")
                    val feePayerAccount =
                        walletRepository.findByIdOrNull(request.registrationFeesAccount)
                            ?: throw IOException("Registration fees account not found!")
                    val utxos =
                        walletUtils.getUtxos(
                            defaultHost,
                            defaultHostConnection,
                            magicString,
                            socketPath,
                            era,
                            feePayerAccount.paymentAddr
                        )
                    utxos.forEach { utxo ->
                        transaction.append("--tx-in ${utxo.hash}#${utxo.ix} ")
                    }
                    val feePayerAccountBalance = utxos.sumByBigInteger { it.lovelace }
                    log.debug("feePayerAccount balance: {}", feePayerAccountBalance)
                    witnessCount++ // fee payer is a witness
                    defaultHostConnection.commandWriteFile(
                        "/tmp/feepayer.payment.skey",
                        walletUtils.getSKeyContent(
                            requireNotNull(feePayerAccount.paymentSkey),
                            request.spendingPassword
                        )
                    )
                    signingKeys.append("--signing-key-file /tmp/feepayer.payment.skey ")

                    // initial dummy value to return change to the fee payer account.
                    // We'll replace this with the actual change to return later
                    transaction.append("--tx-out ${feePayerAccount.paymentAddr}+$feePayerAccountBalance ")

                    val queryTipJson =
                        defaultHostConnection
                            .command("${defaultHost.cardanoCliPath} $era query tip $magicString $socketPath --output-json")
                            .trim()
                    val ttl = queryTipAdapter.fromJson(queryTipJson)?.let { it.slot + 21600 } ?: -1
                    transaction.append("--invalid-hereafter $ttl ")
                    transaction.append("--fee 200000 ")

                    nodeRepository.findByIdOrNull(request.nodeId)?.let { node ->

                        // 2. Register owner address on the chain if not yet registered
                        val ownerStakingAccount =
                            walletRepository.findByIdOrNull(node.ownerStakingAccountId)
                                ?: throw IOException("Owner staking account not found!")
                        defaultHostConnection.commandWriteFile(
                            "/tmp/owner.staking.skey",
                            walletUtils.getSKeyContent(
                                requireNotNull(ownerStakingAccount.stakingSkey),
                                request.spendingPassword
                            )
                        )
                        defaultHostConnection.commandWriteFile(
                            "/tmp/owner.staking.vkey",
                            requireNotNull(ownerStakingAccount.stakingVkey?.content)
                        )
                        val ownerStakingWalletItem =
                            walletUtils.getWalletItem(
                                defaultHost,
                                defaultHostConnection,
                                magicString,
                                socketPath,
                                era,
                                ownerStakingAccount
                            )
                        if (!ownerStakingWalletItem.stakingAddrRegistered) {
                            // owner staking address is *not* registered. We should register it on chain as part of the transaction
                            ownerStakingAccount.stakingRegCert?.let { stakingRegCert ->
                                val content =
                                    if ("Shelley" in stakingRegCert.content) {
                                        // we need to convert the shelley cert to a conway cert
                                        walletUtils
                                            .upgradeShelleyRegcertToConway(
                                                stakingRegCert,
                                                protocolParameters
                                            ).content
                                    } else {
                                        stakingRegCert.content
                                    }
                                defaultHostConnection.commandWriteFile(
                                    "/tmp/owner.staking.cert",
                                    content
                                )
                            } ?: throw IOException("Staking reg cert not found on owner account!")
                            depositAndFees += protocolParameters.stakeAddressDeposit
                            certificates.append("--certificate /tmp/owner.staking.cert ")
                        }
                        witnessCount++ // the owner.staking.skey is a witness
                        signingKeys.append("--signing-key-file /tmp/owner.staking.skey ")

                        // 3. Register rewards address on the chain if not yet registered
                        val rewardsStakingAccount =
                            walletRepository.findByIdOrNull(node.rewardsStakingAccountId)
                                ?: throw IOException("Rewards staking account not found!")
                        defaultHostConnection.commandWriteFile(
                            "/tmp/rewards.staking.skey",
                            walletUtils.getSKeyContent(
                                requireNotNull(rewardsStakingAccount.stakingSkey),
                                request.spendingPassword
                            )
                        )
                        defaultHostConnection.commandWriteFile(
                            "/tmp/rewards.staking.vkey",
                            requireNotNull(rewardsStakingAccount.stakingVkey?.content)
                        )
                        if (node.rewardsStakingAccountId != node.ownerStakingAccountId) {
                            val rewardsStakingWalletItem =
                                walletUtils.getWalletItem(
                                    defaultHost,
                                    defaultHostConnection,
                                    magicString,
                                    socketPath,
                                    era,
                                    rewardsStakingAccount
                                )
                            if (!rewardsStakingWalletItem.stakingAddrRegistered) {
                                // rewards staking address is *not* registered. We should register it on chain as part of the transaction
                                rewardsStakingAccount.stakingRegCert?.let { stakingRegCert ->
                                    val content =
                                        if ("Shelley" in stakingRegCert.content) {
                                            // we need to convert the shelley cert to a conway cert
                                            walletUtils
                                                .upgradeShelleyRegcertToConway(
                                                    stakingRegCert,
                                                    protocolParameters
                                                ).content
                                        } else {
                                            stakingRegCert.content
                                        }
                                    defaultHostConnection.commandWriteFile(
                                        "/tmp/rewards.staking.cert",
                                        content
                                    )
                                } ?: throw IOException("Staking reg cert not found on rewards account!")
                                depositAndFees += protocolParameters.stakeAddressDeposit
                                certificates.append("--certificate /tmp/rewards.staking.cert ")
                            }
                        }

                        val coldSKeyFile =
                            fileRepository.findByIdOrNull(node.coreSKeyId)
                                ?: throw IOException("Cold skey not found!")
                        defaultHostConnection.commandWriteFile(
                            "/tmp/core.node.skey",
                            walletUtils.getSKeyContent(coldSKeyFile, request.spendingPassword).trim()
                        )
                        val coldVKeyFile =
                            fileRepository.findByIdOrNull(node.coreVKeyId)
                                ?: throw IOException("Cold vkey not found!")
                        defaultHostConnection.commandWriteFile("/tmp/core.node.vkey", coldVKeyFile.content)
                        val vrfVKeyFile =
                            fileRepository.findByIdOrNull(node.vrfVKeyId)
                                ?: throw IOException("vrf vkey not found!")
                        defaultHostConnection.commandWriteFile("/tmp/core.vrf.vkey", vrfVKeyFile.content)

                        witnessCount++ // the core.node.skey is always a witness
                        signingKeys.append("--signing-key-file /tmp/core.node.skey ")

                        val nodeId = requireNotNull(node.id)
                        val relays =
                            relayRepository.findByNodeId(nodeId).let { relays ->
                                relayRepository.deleteAll(relays)
                                request.relays.map { relay ->
                                    Relay(
                                        nodeId = nodeId,
                                        addr = relay.addr,
                                        port = relay.port
                                    ).also { relayRepository.save(it) }
                                }
                            }

                        // download and get the hash!
                        defaultHostConnection.command("curl ${node.metadataUrl} --output /tmp/metadata.json")
                        val metadataHash =
                            defaultHostConnection
                                .command(
                                    "${defaultHost.cardanoCliPath} $era stake-pool metadata-hash --pool-metadata-file /tmp/metadata.json"
                                ).trim()

                        // 6. create the pool registration certificate
                        val poolRegcertCommand =
                            StringBuilder().apply {
                                append("${defaultHost.cardanoCliPath} $era stake-pool registration-certificate ")
                                append("--cold-verification-key-file /tmp/core.node.vkey ")
                                append("--vrf-verification-key-file /tmp/core.vrf.vkey ")
                                append("--pool-pledge ${node.poolPledge} ")
                                append("--pool-cost ${node.poolCost} ")
                                append("--pool-margin ${node.poolMargin} ")
                                append("--pool-reward-account-verification-key-file /tmp/rewards.staking.vkey ")
                                append("--pool-owner-stake-verification-key-file /tmp/owner.staking.vkey ")
                                relays.forEach { relay ->
                                    if (relay.addr.matches(IP4_ADDRESS)) {
                                        append("--pool-relay-ipv4 ${relay.addr} --pool-relay-port ${relay.port} ")
                                    } else {
                                        append("--single-host-pool-relay ${relay.addr} --pool-relay-port ${relay.port} ")
                                    }
                                }
                                append("--metadata-url ${node.metadataUrl} --metadata-hash $metadataHash ")
                                append("$magicString ")
                                append("--out-file /tmp/core.pool.cert")
                            }
                        log.debug("poolRegcertCommand: $poolRegcertCommand")
                        defaultHostConnection.command(poolRegcertCommand.toString())
                        certificates.append("--certificate /tmp/core.pool.cert ")

                        // 8. Calculate fees
                        transaction.append(certificates)
                        transaction.append("--out-file /tmp/transaction.txbody")
                        defaultHostConnection.command(transaction.toString())

                        log.debug("depositAndFees: $depositAndFees")
                        val feesString =
                            defaultHostConnection
                                .command(
                                    "${defaultHost.cardanoCliPath} $era transaction calculate-min-fee --tx-body-file /tmp/transaction.txbody --protocol-params-file /tmp/protocol-parameters.json --tx-in-count ${utxos.size} --tx-out-count 1 $magicString --witness-count $witnessCount --byron-witness-count 0 --output-text"
                                ).trim()
                        val fees = feesString.split(" ")[0].toBigInteger()
                        log.debug("fees: $fees")
                        depositAndFees += fees
                        log.debug("final depositAndFees: $depositAndFees")

                        // 9. Create the transaction
                        val change = utxos.sumByBigInteger { it.lovelace } - depositAndFees
                        if (change < BigInteger.ONE) {
                            throw IOException("Not enough funds to pay depositAndFees of $depositAndFees lovelace!")
                        }

                        val tokenChange = StringBuilder()
                        utxos.toNativeAssetMap().forEach { (currency, amount) ->
                            if (amount > BigInteger.ZERO) {
                                tokenChange.append("+$amount $currency")
                            }
                        }

                        val realTransaction =
                            transaction
                                .toString()
                                .replace("--fee 200000 ", "--fee $fees ")
                                .replace(
                                    "--tx-out ${feePayerAccount.paymentAddr}+$feePayerAccountBalance ",
                                    "--tx-out '${feePayerAccount.paymentAddr}+$change$tokenChange' "
                                )
                        log.debug("Pool Transaction Command: $realTransaction")
                        defaultHostConnection.command(realTransaction)

                        // 10. Sign the transaction
                        defaultHostConnection.command(
                            "${defaultHost.cardanoCliPath} $era transaction sign --tx-body-file /tmp/transaction.txbody $signingKeys $magicString --out-file /tmp/transaction.txsigned"
                        )

                        runBlocking {
                            TransactionCache.withLock {
                                // 11. Submit the transaction
                                defaultHostConnection.command(
                                    "${defaultHost.cardanoCliPath} $era transaction submit --tx-file /tmp/transaction.txsigned $magicString $socketPath"
                                )
                                val txid =
                                    defaultHostConnection
                                        .command(
                                            "${defaultHost.cardanoCliPath} $era transaction txid --tx-body-file /tmp/transaction.txbody --output-text"
                                        ).trim()
                                val txSigned = defaultHostConnection.commandReadFile("/tmp/transaction.txsigned")
                                TransactionCache.put(txid, txSigned)

                                val cborBytes = txSignedAdapter.fromJson(txSigned)!!.cborHex.hexToByteArray()
                                ledgerDao.updateLiveLedgerState(txid, cborBytes)
                                transactionRepository.save(Transaction(txid = txid))
                            }
                        }
                    } ?: throw IOException("Node not found!")
                } finally {
                    // Cleanup
                    defaultHostConnection.command(
                        "rm -f /tmp/protocol-parameters.json /tmp/transaction.txbody /tmp/transaction.txsigned /tmp/core.pool.cert /tmp/feepayer.payment.skey /tmp/owner.staking.skey /tmp/owner.staking.vkey /tmp/owner.staking.cert /tmp/owner.deleg.cert /tmp/rewards.staking.skey /tmp/rewards.staking.vkey /tmp/rewards.staking.cert /tmp/core.node.skey /tmp/core.node.vkey /tmp/core.vrf.skey /tmp/core.vrf.vkey /tmp/metadata.json"
                    )
                }

                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Success(type = "editrelays", data = "Update Relays Success!")
                )
            } catch (e: Throwable) {
                log.error("Error Editing Relays!", e)
                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Error(type = "editrelays", exception = e)
                )
                // rethrow so db transaction is rolled back
                throw RuntimeException(e)
            }
        }

        @MessageMapping("/retirepool")
        @Transactional
        fun retirePool(request: RetirePoolRequest) {
            try {
                if (!walletUtils.isValidSpendingPassword(request.spendingPassword)) {
                    throw IllegalArgumentException("Invalid spending password!")
                }
                val defaultNode = nodeRepository.findDefault() ?: throw IOException("Default node not found!")
                val defaultHost =
                    hostRepository.findByIdOrNull(defaultNode.hostId)
                        ?: throw IOException("Default Host not found!")
                val defaultHostConnection = HostConnection(defaultHost, defaultNode)
                val socketPath = "--socket-path ${defaultHost.nodeHomePath}/${defaultNode.name}/db/socket"

                val node = nodeRepository.findByIdOrNull(request.id) ?: throw IOException("Node not found!")
                if (node.isDefault) {
                    throw IOException("Cannot delete the default node!")
                }
                val host = hostRepository.findByIdOrNull(node.hostId) ?: throw IOException("Host not found for node!")
                val hostConnection = HostConnection(host)

                // validate sudo password right away
                hostConnection.sudoCommand("pwd", request.sudoPassword)

                val genesisFile =
                    fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)
                        ?: throw IOException("Shelley genesis filenot found!")
                val genesis = shelleyGenesisAdapter.fromJson(genesisFile.content)!!
                val magicString =
                    if (genesis.networkId.equals("testnet", ignoreCase = true)) {
                        "--testnet-magic ${genesis.networkMagic}"
                    } else {
                        "--mainnet"
                    }

                try {
                    val era = cardanoRepository.getEra(defaultHost, defaultNode, magicString, socketPath)
                    val protocolParamsJson =
                        defaultHostConnection
                            .command(
                                "${defaultHost.cardanoCliPath} conway query protocol-parameters $magicString $socketPath --output-json"
                            ).trim()
                    defaultHostConnection.commandWriteFile("/tmp/protocol-parameters.json", protocolParamsJson)

                    // 1. Create a transaction to dump EVERYTHING into
                    var depositAndFees = BigInteger.ZERO
                    var witnessCount = 0
                    val transaction = StringBuilder()
                    val certificates = StringBuilder()
                    val signingKeys = StringBuilder()
                    transaction.append("${defaultHost.cardanoCliPath} conway transaction build-raw ")
                    val feePayerAccount =
                        walletRepository.findByIdOrNull(request.retireFeesAccount)
                            ?: throw IOException("Registration fees account not found!")
                    val utxos =
                        walletUtils.getUtxos(
                            defaultHost,
                            defaultHostConnection,
                            magicString,
                            socketPath,
                            era,
                            feePayerAccount.paymentAddr
                        )
                    utxos.forEach { utxo ->
                        transaction.append("--tx-in ${utxo.hash}#${utxo.ix} ")
                    }
                    val feePayerAccountBalance = utxos.sumByBigInteger { it.lovelace }
                    log.debug("feePayerAccount balance: {}", feePayerAccountBalance)
                    witnessCount++ // fee payer is a witness
                    defaultHostConnection.commandWriteFile(
                        "/tmp/feepayer.payment.skey",
                        walletUtils.getSKeyContent(requireNotNull(feePayerAccount.paymentSkey), request.spendingPassword)
                    )
                    signingKeys.append("--signing-key-file /tmp/feepayer.payment.skey ")

                    // initial dummy value to return change to the fee payer account.
                    // We'll replace this with the actual change to return later
                    transaction.append("--tx-out ${feePayerAccount.paymentAddr}+$feePayerAccountBalance ")

                    val queryTipJson =
                        defaultHostConnection
                            .command("${defaultHost.cardanoCliPath} conway query tip $magicString $socketPath --output-json")
                            .trim()
                    val ttl = queryTipAdapter.fromJson(queryTipJson)?.let { it.slot + 21600 } ?: -1
                    transaction.append("--invalid-hereafter $ttl ")
                    transaction.append("--fee 200000 ")

                    val coldSKeyFile =
                        fileRepository.findByIdOrNull(node.coreSKeyId)
                            ?: throw IOException("Cold skey not found!")
                    defaultHostConnection.commandWriteFile(
                        "/tmp/core.node.skey",
                        walletUtils.getSKeyContent(coldSKeyFile, request.spendingPassword).trim()
                    )
                    val coldVKeyFile =
                        fileRepository.findByIdOrNull(node.coreVKeyId)
                            ?: throw IOException("Cold vkey not found!")
                    defaultHostConnection.commandWriteFile("/tmp/core.node.vkey", coldVKeyFile.content)

                    // generate dereg cert
                    defaultHostConnection.command(
                        "${defaultHost.cardanoCliPath} $era stake-pool deregistration-certificate --cold-verification-key-file /tmp/core.node.vkey --epoch ${request.retireEpoch} --out-file /tmp/core.dereg-cert"
                    )
                    certificates.append("--certificate /tmp/core.dereg-cert ")

                    witnessCount++ // the core.node.skey is a witness
                    signingKeys.append("--signing-key-file /tmp/core.node.skey ")

                    // 8. Calculate fees
                    transaction.append(certificates)
                    transaction.append("--out-file /tmp/transaction.txbody")
                    defaultHostConnection.command(transaction.toString())

                    log.debug("depositAndFees: $depositAndFees")
                    val feesString =
                        defaultHostConnection
                            .command(
                                "${defaultHost.cardanoCliPath} conway transaction calculate-min-fee --tx-body-file /tmp/transaction.txbody --protocol-params-file /tmp/protocol-parameters.json --tx-in-count ${utxos.size} --tx-out-count 1 $magicString --witness-count $witnessCount --byron-witness-count 0 --output-text"
                            ).trim()
                    val fees = feesString.split(" ")[0].toBigInteger()
                    log.debug("fees: $fees")
                    depositAndFees += fees
                    log.debug("final depositAndFees: $depositAndFees")

                    // 9. Create the transaction
                    val change = utxos.sumByBigInteger { it.lovelace } - depositAndFees
                    if (change < BigInteger.ONE) {
                        throw IOException("Not enough funds to pay depositAndFees of $depositAndFees lovelace!")
                    }

                    val tokenChange = StringBuilder()
                    utxos.toNativeAssetMap().forEach { (currency, amount) ->
                        if (amount > BigInteger.ZERO) {
                            tokenChange.append("+$amount $currency")
                        }
                    }

                    val realTransaction =
                        transaction
                            .toString()
                            .replace("--fee 200000 ", "--fee $fees ")
                            .replace(
                                "--tx-out ${feePayerAccount.paymentAddr}+$feePayerAccountBalance ",
                                "--tx-out '${feePayerAccount.paymentAddr}+$change$tokenChange' "
                            )
                    log.debug("Pool Transaction Command: $realTransaction")
                    defaultHostConnection.command(realTransaction)

                    // 10. Sign the transaction
                    defaultHostConnection.command(
                        "${defaultHost.cardanoCliPath} conway transaction sign --tx-body-file /tmp/transaction.txbody $signingKeys $magicString --out-file /tmp/transaction.txsigned"
                    )

                    nodeRepository.save(node.copy(isDeleted = true))

                    // do not stop node right now. It might be retiring in the future.
//                if (hostConnection.hasSystemd) {
//                    hostConnection.sudoCommand("systemctl stop ${node.name}-node.service", request.sudoPassword)
//                    hostConnection.sudoCommand("systemctl disable ${node.name}-node.service", request.sudoPassword)
//                }
                    val now = System.currentTimeMillis()
                    fileRepository.findByIdOrNull(node.coreSKeyId)?.let {
                        fileRepository.save(it.copy(name = it.name + "-$now"))
                    }
                    fileRepository.findByIdOrNull(node.coreVKeyId)?.let {
                        fileRepository.save(it.copy(name = it.name + "-$now"))
                    }
                    fileRepository.findByIdOrNull(node.vrfVKeyId)?.let {
                        fileRepository.save(it.copy(name = it.name + "-$now"))
                    }
                    fileRepository.findByIdOrNull(node.vrfSKeyId)?.let {
                        fileRepository.save(it.copy(name = it.name + "-$now"))
                    }
                    fileRepository.findByIdOrNull(node.kesSKeyId)?.let {
                        fileRepository.save(it.copy(name = it.name + "-$now"))
                    }
                    fileRepository.findByIdOrNull(node.kesVKeyId)?.let {
                        fileRepository.save(it.copy(name = it.name + "-$now"))
                    }
                    fileRepository.findByIdOrNull(node.opcertId)?.let {
                        fileRepository.save(it.copy(name = it.name + "-$now"))
                    }
                    fileRepository.findByIdOrNull(node.coreCounterId)?.let {
                        fileRepository.save(it.copy(name = it.name + "-$now"))
                    }
                    fileRepository.findByIdOrNull(node.itnPublicKeyId)?.let {
                        fileRepository.save(it.copy(name = it.name + "-$now"))
                    }
                    fileRepository.findByIdOrNull(node.itnPrivateKeyId)?.let {
                        fileRepository.save(it.copy(name = it.name + "-$now"))
                    }

                    val txid =
                        runBlocking {
                            TransactionCache.withLock {
                                // 11. Submit the transaction
                                defaultHostConnection.command(
                                    "${defaultHost.cardanoCliPath} conway transaction submit --tx-file /tmp/transaction.txsigned $magicString $socketPath"
                                )
                                val txid =
                                    defaultHostConnection
                                        .command(
                                            "${defaultHost.cardanoCliPath} conway transaction txid --tx-body-file /tmp/transaction.txbody --output-text"
                                        ).trim()
                                val txSigned = defaultHostConnection.commandReadFile("/tmp/transaction.txsigned")
                                TransactionCache.put(txid, txSigned)

                                val cborBytes = txSignedAdapter.fromJson(txSigned)!!.cborHex.hexToByteArray()
                                ledgerDao.updateLiveLedgerState(txid, cborBytes)
                                transactionRepository.save(Transaction(txid = txid))
                                txid
                            }
                        }

                    // send all to the client for ui updates
                    val nodes = nodeRepository.findAll(Sort.by(Sort.Direction.ASC, "name")).filter { !it.isDeleted }
                    webSocketTemplate.convertAndSend(
                        "/topic/messages",
                        SocketResponse.Success(type = "nodes", data = nodes)
                    )

                    webSocketTemplate.convertAndSend(
                        "/topic/messages",
                        SocketResponse.Success(
                            type = "retirepool",
                            data = "${node.name} scheduled for retirement. TxId: $txid"
                        )
                    )
                } finally {
                    // Cleanup
                    defaultHostConnection.command(
                        "rm -f /tmp/protocol-parameters.json /tmp/transaction.txbody /tmp/transaction.txsigned /tmp/feepayer.payment.skey /tmp/core.node.skey /tmp/core.node.vkey /tmp/core.dereg-cert"
                    )
                }
            } catch (e: Throwable) {
                log.error("Error Retiring Pool!", e)
                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Error(type = "retirepool", exception = e)
                )
                // rethrow so db transaction is rolled back
                throw RuntimeException(e)
            }
        }

        private fun isPoolOnChain(
            defaultHost: Host,
            defaultHostConnection: HostConnection,
            poolId: String,
            era: String,
            magicString: String,
            socketPath: String,
        ): Boolean {
            val poolIdBech32 = Bech32.encode("pool", poolId.hexToByteArray())
            val pools =
                defaultHostConnection
                    .command("${defaultHost.cardanoCliPath} $era query stake-pools $magicString $socketPath --output-text")
                    .trim()
            return poolIdBech32 in pools
        }

        private fun uploadMetadata(metadataJson: String): String =
            runBlocking {
                val service =
                    retrofit
                        .newBuilder()
                        .baseUrl("https://1v27dl8wn5.execute-api.us-west-2.amazonaws.com")
                        .build()
                        .create(MetadataService::class.java)
                val metadataPostInfo = service.getMetadataPostInfo()

                val uploadService =
                    retrofit
                        .newBuilder()
                        .baseUrl("https://s3.us-west-2.amazonaws.com")
                        .build()
                        .create(MetadataService::class.java)

                val response =
                    uploadService.saveMetadataFile(
                        contentType =
                            MultipartBody.Part.createFormData(
                                "content-type",
                                null,
                                "application/json".toRequestBody("text/plain".toMediaTypeOrNull())
                            ),
                        awsAccessKeyId =
                            MultipartBody.Part.createFormData(
                                "AWSAccessKeyId",
                                null,
                                metadataPostInfo.fields.awsAccessKeyId.toRequestBody("text/plain".toMediaTypeOrNull())
                            ),
                        key =
                            MultipartBody.Part.createFormData(
                                "key",
                                null,
                                metadataPostInfo.fields.key.toRequestBody("text/plain".toMediaTypeOrNull())
                            ),
                        policy =
                            MultipartBody.Part.createFormData(
                                "policy",
                                null,
                                metadataPostInfo.fields.policy.toRequestBody("text/plain".toMediaTypeOrNull())
                            ),
                        signature =
                            MultipartBody.Part.createFormData(
                                "signature",
                                null,
                                metadataPostInfo.fields.signature.toRequestBody("text/plain".toMediaTypeOrNull())
                            ),
                        securityToken =
                            MultipartBody.Part.createFormData(
                                "x-amz-security-token",
                                null,
                                metadataPostInfo.fields.securityToken.toRequestBody("text/plain".toMediaTypeOrNull())
                            ),
                        file =
                            MultipartBody.Part.createFormData(
                                "file",
                                metadataPostInfo.fields.key,
                                metadataJson.toRequestBody("application/json".toMediaTypeOrNull())
                            )
                    )

                if (response.isSuccessful) {
                    return@runBlocking "https://cardanostakehouse.com/${metadataPostInfo.fields.key}"
                }
                throw IOException("Could not upload json file!")
            }

        private fun createManualStartupScripts(
            request: CreateNodeRequest,
            startupNodeType: String,
            host: Host,
            hostConnection: HostConnection,
            tracingHost: String,
            tracingPort: Int?,
        ) {
            val startNodeContent =
                renderManualStartupScript(
                    request = request,
                    startupNodeType = startupNodeType,
                    host = host,
                    tracingHost = tracingHost,
                    tracingPort = tracingPort,
                )

            hostConnection.commandWriteFile("${host.nodeHomePath}/${request.name}/startNode.sh", startNodeContent)
            hostConnection.command("chmod 555 ${host.nodeHomePath}/${request.name}/startNode.sh")

            val stopNodeContent =
                """
            |#!/bin/bash
            |OLDPWD=`pwd`
            |cd ${host.nodeHomePath}/${request.name}
            |PID=`cat ${request.name}.pid`
            |kill -s INT ${'$'}PID >/dev/null 2>&1
            |sleep 3
            |kill -s KILL ${'$'}PID >/dev/null 2>&1
            |rm -f ${request.name}.pid
            |cd ${'$'}OLDPWD
                """.trimMargin()
            hostConnection.commandWriteFile("${host.nodeHomePath}/${request.name}/stopNode.sh", stopNodeContent)
            hostConnection.command("chmod 555 ${host.nodeHomePath}/${request.name}/stopNode.sh")
        }

        private fun createSystemdFile(
            request: CreateNodeRequest,
            startupNodeType: String,
            host: Host,
            hostConnection: HostConnection,
            name: String,
            processorThreads: Int,
            tracingHost: String,
            tracingPort: Int?,
        ) {
            val systemdContent =
                renderSystemdContent(
                    startupNodeType = startupNodeType,
                    host = host,
                    name = name,
                    processorThreads = processorThreads,
                    tracingHost = tracingHost,
                    tracingPort = tracingPort,
                )

            hostConnection.sudoCommandWriteFile(
                "/etc/systemd/system/$name-node.service",
                systemdContent,
                request.sudoPassword
            )

            if (hostConnection.hasSystemd) {
                hostConnection.sudoCommand("systemctl daemon-reload", request.sudoPassword)
                hostConnection.sudoCommand("systemctl stop $name-node.service", request.sudoPassword)
                hostConnection.sudoCommand("systemctl start $name-node.service", request.sudoPassword)
                hostConnection.sudoCommand("systemctl enable $name-node.service", request.sudoPassword)
            }
        }

        private fun createEnvFile(
            hostConnection: HostConnection,
            nodeType: String,
            nodeName: String,
            nodeFolder: String,
            listen: String,
            port: Int,
            tracingHost: String,
            tracingPort: Int?,
        ): String {
            hostConnection.commandWriteFile(
                "$nodeFolder/env",
                renderEnvContent(
                    nodeType = nodeType,
                    nodeName = nodeName,
                    nodeFolder = nodeFolder,
                    listen = listen,
                    port = port,
                    tracingHost = tracingHost,
                    tracingPort = tracingPort,
                )
            )
            return hostConnection.command("chmod 400 $nodeFolder/env")
        }

        internal fun renderEnvContent(
            nodeType: String,
            nodeName: String,
            nodeFolder: String,
            listen: String,
            port: Int,
            tracingHost: String,
            tracingPort: Int?,
        ): String {
            val tracingEnv =
                tracingPort?.let {
                    "|TRACING_HOST=$tracingHost\n|TRACING_PORT=$tracingPort\n"
                } ?: ""

            return when (nodeType) {
                NODE_TYPE_RELAY -> {
                    """
                |TOPOLOGY=$nodeFolder/topology.json
                |DATABASE_PATH=$nodeFolder/db
                |SOCKET_PATH=$nodeFolder/db/socket
                |HOST_ADDR=$listen
                |PORT=$port
                |CONFIG=$nodeFolder/config.json
                $tracingEnv
                    """.trimMargin()
                }

                NODE_TYPE_CORE -> {
                    """
                |TOPOLOGY=$nodeFolder/topology.json
                |DATABASE_PATH=$nodeFolder/db
                |SOCKET_PATH=$nodeFolder/db/socket
                |HOST_ADDR=$listen
                |PORT=$port
                |CONFIG=$nodeFolder/config.json
                |SHELLEY_KES_KEY=$nodeFolder/$nodeName.kes.skey
                |SHELLEY_VRF_KEY=$nodeFolder/$nodeName.vrf.skey
                |SHELLEY_OPCERT=$nodeFolder/$nodeName.node.opcert
                $tracingEnv
                    """.trimMargin()
                }

                else -> {
                    """
                |TOPOLOGY=$nodeFolder/topology.json
                |DATABASE_PATH=$nodeFolder/db
                |SOCKET_PATH=$nodeFolder/db/socket
                |HOST_ADDR=$listen
                |PORT=$port
                |CONFIG=$nodeFolder/config.json
                |BULK_CREDENTIALS=$nodeFolder/credentials.json
                $tracingEnv
                    """.trimMargin()
                }
            }
        }

        private fun createBulkCredentials(
            hostConnection: HostConnection,
            nodeFolder: String,
            credentials: MutableList<MutableList<Key>>
        ) {
            val credentialsJson = bulkCredentialsJsonAdapter.indent("  ").toJson(credentials)!!
            hostConnection.commandWriteFile("$nodeFolder/credentials.json", credentialsJson)
            hostConnection.command("chmod 400 $nodeFolder/credentials.json")
        }

        private fun createKesVrfOpcert(
            nodeName: String,
            kesSKeyContent: String,
            vrfSKeyContent: String,
            opcertContent: String,
            hostConnection: HostConnection,
            nodeFolder: String
        ) {
            if (hostConnection.commandFileExists("$nodeFolder/$nodeName.kes.skey")) {
                hostConnection.command("chmod 600 $nodeFolder/$nodeName.kes.skey")
            }
            hostConnection.commandWriteFile("$nodeFolder/$nodeName.kes.skey", kesSKeyContent)
            hostConnection.command("chmod 400 $nodeFolder/$nodeName.kes.skey")

            if (hostConnection.commandFileExists("$nodeFolder/$nodeName.vrf.skey")) {
                hostConnection.command("chmod 600 $nodeFolder/$nodeName.vrf.skey")
            }
            hostConnection.commandWriteFile("$nodeFolder/$nodeName.vrf.skey", vrfSKeyContent)
            hostConnection.command("chmod 400 $nodeFolder/$nodeName.vrf.skey")

            if (hostConnection.commandFileExists("$nodeFolder/$nodeName.node.opcert")) {
                hostConnection.command("chmod 600 $nodeFolder/$nodeName.node.opcert")
            }
            hostConnection.commandWriteFile("$nodeFolder/$nodeName.node.opcert", opcertContent)
            hostConnection.command("chmod 400 $nodeFolder/$nodeName.node.opcert")
        }

        internal fun allocatePrometheusPort(
            hostId: Long,
            hostConnection: HostConnection,
            isPortUsed: (Int) -> Boolean = { port -> isPortUsed(hostConnection, port) },
        ): Int {
            var promPort = 12789 + nodeRepository.countForHost(hostId)
            while (isPortUsed(promPort)) {
                promPort++
            }

            return promPort
        }

        private fun createConfigFile(
            genesisByronFileName: String,
            nodeType: String,
            promPort: Int,
            prometheusListen: String,
            hostConnection: HostConnection,
            nodeFolder: String,
            maxConcurrencyDeadline: String,
            peerSharing: Boolean,
        ): Pair<Long, Int> {
            val configFile = fileRepository.findByName(genesisByronFileName.substringBeforeLast("-byron") + "-config.json")
            val configFileContent =
                configFile?.content?.let {
                    renderManagedConfig(
                        templateContent = it,
                        nodeType = nodeType,
                        maxConcurrencyDeadline = maxConcurrencyDeadline,
                        peerSharing = peerSharing,
                        prometheusListen = prometheusListen,
                        promPort = promPort,
                    )
                }
            /*
                configFile
                    ?.content
                    ?.replace(Regex(""""ConwayGenesisFile": .*,"""), """"ConwayGenesisFile": "conway-genesis.json",""")
                    ?.replace(Regex(""""AlonzoGenesisFile": .*,"""), """"AlonzoGenesisFile": "alonzo-genesis.json",""")
                    ?.replace(Regex(""""ByronGenesisFile": .*,"""), """"ByronGenesisFile": "byron-genesis.json",""")
                    ?.replace(Regex(""""ShelleyGenesisFile": .*,"""), """"ShelleyGenesisFile": "shelley-genesis.json",""")
                    ?.replace(Regex(""""GenesisFile": .*,"""), """"GenesisFile": "shelley-genesis.json",""")
                    ?.replace(Regex(""""PeerSharing": .*"""), """"PeerSharing": $peerSharing,""")
                    ?.replace(
                        Regex(""""TraceBlockFetchDecisions":.*(true|false),"""),
                        """"TraceBlockFetchDecisions": true,"""
                    )?.replace(
                        Regex(""".*"defaultScribes.*\[\n.*\[\n.*StdoutSK.*\n.*stdout.*\n.*\]\n.*\],"""),
                        """
                                                |  "defaultScribes": [
                                                |    [
                                                |      "FileSK",
                                                |      "logs/node.json"
                                                |    ]
                                                |  ],
                        """.trimMargin()
                    )?.replace(Regex(""""rpLogLimitBytes": .*,"""), """"rpLogLimitBytes": 20000000,""")
                    ?.replace(Regex(""""scFormat.*,"""), """"scFormat": "ScJson",""")
                    ?.replace(Regex(""""scKind.*,"""), """"scKind": "FileSK",""")
                    ?.replace(Regex(""""scName.*,"""), """"scName": "logs/node.json",""")
                    ?.replace("12798", "$promPort")
                    ?.replace(
                        Regex(""""MaxConcurrencyDeadline.*,""""),
                        """"MaxConcurrencyDeadline": $maxConcurrencyDeadline,"""
                    )
             */
            configFileContent?.let {
                log.debug("Creating $nodeFolder/config.json from db file ${configFile.name}")
                hostConnection.commandWriteFile("$nodeFolder/config.json", it)
                maybeCreateCheckpointsFile(configFile.name, it, hostConnection, nodeFolder)
            } ?: throw IOException("Config file not found in db!")

            return Pair(configFile.id!!, promPort)
        }

        internal fun renderManagedConfig(
            templateContent: String,
            nodeType: String,
            maxConcurrencyDeadline: String,
            peerSharing: Boolean,
            prometheusListen: String,
            promPort: Int,
        ): String {
            val root = objectMapper.readTree(templateContent).deepCopy<ObjectNode>()
            normalizeManagedConfig(root, nodeType, maxConcurrencyDeadline, peerSharing, prometheusListen, promPort)
            return objectMapper.writeValueAsString(root) + "\n"
        }

        private fun normalizeManagedConfig(
            root: ObjectNode,
            nodeType: String,
            maxConcurrencyDeadline: String,
            peerSharing: Boolean,
            prometheusListen: String,
            promPort: Int,
        ) {
            root.put("ConwayGenesisFile", "conway-genesis.json")
            root.put("AlonzoGenesisFile", "alonzo-genesis.json")
            root.put("ByronGenesisFile", "byron-genesis.json")
            root.put("ShelleyGenesisFile", "shelley-genesis.json")
            root.put("GenesisFile", "shelley-genesis.json")
            root.path("CheckpointsFile")?.takeIf { !it.isMissingNode && it.isTextual }?.let {
                root.put("CheckpointsFile", "checkpoints.json")
            }
            root.put("PeerSharing", peerSharing)
            root.put("MaxConcurrencyDeadline", maxConcurrencyDeadline.toInt())

            normalizeTracingConfig(root, nodeType, prometheusListen, promPort)
        }

        private fun normalizeTracingConfig(
            root: ObjectNode,
            nodeType: String,
            prometheusListen: String,
            promPort: Int,
        ) {
            val traceOptions = (root.get("TraceOptions") as? ObjectNode) ?: root.putObject("TraceOptions")

            val rootTraceOptions = traceOptions.get("") as ObjectNode
            rootTraceOptions.remove("detail")
            if (!rootTraceOptions.has("severity")) {
                rootTraceOptions.put("severity", MARKUS_ROOT_TRACE_SEVERITY)
            }

            val backends = objectMapper.createArrayNode().apply {
                add(STDOUT_MACHINE_FORMAT_BACKEND)
                if (nodeType in setOf(NODE_TYPE_CORE, NODE_TYPE_RELAY)) {
                    add(FORWARDER_BACKEND)
                }
                add("PrometheusSimple suffix $prometheusListen $promPort")
            }
            rootTraceOptions.set<ArrayNode>("backends", backends)

            traceOptions.putObject("Forge.Loop").put("severity", "Silence")
            traceOptions.putObject("Forge.Loop.ForgedBlock").put("severity", "Info")
            traceOptions.putObject("Forge.Loop.AdoptedBlock").put("severity", "Info")
            traceOptions.putObject("ChainDB.AddBlockEvent.AddedToCurrentChain").put("severity", "Silence")
            traceOptions.putObject("Net.ConnectionManager.Remote").put("severity", "Silence")
            traceOptions.putObject("Net.PeerSelection").put("severity", "Silence")
            traceOptions.putObject("Net.InboundGovernor.Remote").put("severity", "Silence")
            traceOptions.putObject("Net.InboundGovernor.Remote.InboundGovernorCounters").put("severity", "Silence")
            traceOptions.putObject("ChainSync.Client.DownloadedHeader").put("severity", "Silence")
            traceOptions.putObject("BlockFetch.Client.SendFetchRequest").put("severity", "Silence")
            traceOptions.putObject("BlockFetch.Client.CompletedBlockFetch").put("severity", "Silence")
            traceOptions.putObject("Resources").put("severity", "Silence")
            traceOptions.putObject("Mempool.AddedTx").put("severity", "Silence")
            traceOptions.putObject("Mempool.RemoveTxs").put("severity", "Silence")

            root.put("UseTraceDispatcher", true)
            root.put("TurnOnLogging", true)
            root.put("TurnOnLogMetrics", true)
            root.put("minSeverity", MIN_TRACE_SEVERITY)

            if (nodeType in setOf(NODE_TYPE_CORE, NODE_TYPE_RELAY)) {
                val forwarderOptions = objectMapper.createObjectNode().apply {
                    put("connQueueSize", TRACE_FORWARDER_CONN_QUEUE_SIZE)
                    put("disconnQueueSize", TRACE_FORWARDER_DISCONN_QUEUE_SIZE)
                    put("maxReconnectDelay", TRACE_FORWARDER_MAX_RECONNECT_DELAY)
                }
                root.set<ObjectNode>("TraceOptionForwarder", forwarderOptions)
            } else {
                root.remove("TraceOptionForwarder")
            }

            LEGACY_TRACING_KEYS_TO_REMOVE.forEach(root::remove)
            root
                .fieldNames()
                .asSequence()
                .toList()
                .filter {
                    it.startsWith("Trace") &&
                        it != "TraceOptions" &&
                        it != "TraceOptionForwarder" &&
                        it != "TraceOptionMetricsPrefix" &&
                        it != "TraceOptionResourceFrequency"
                }.forEach(root::remove)
        }

        private fun maybeCreateCheckpointsFile(
            configFileName: String,
            configFileContent: String,
            hostConnection: HostConnection,
            nodeFolder: String,
        ) {
            val checkpointsFileName =
                objectMapper
                    .readTree(configFileContent)
                    .path("CheckpointsFile")
                    .takeIf { !it.isMissingNode && it.isTextual }
                    ?.asText()
                    ?: return

            val sourceCheckpointsFile =
                fileRepository.findByName(configFileName.removeSuffix("-config.json") + "-checkpoints.json")
                    ?: throw IOException("Checkpoints file $checkpointsFileName referenced by $configFileName not found in db!")

            log.debug("Creating $nodeFolder/checkpoints.json from db file ${sourceCheckpointsFile.name}")
            hostConnection.commandWriteFile("$nodeFolder/checkpoints.json", sourceCheckpointsFile.content)
        }

        private fun isPortUsed(
            hostConnection: HostConnection,
            port: Int
        ): Boolean = hostConnection.command("ss -tulw").trim().contains(":$port")

        internal fun allocateTracingPort(
            promPort: Int,
            isPortUsed: (Int) -> Boolean,
        ): Int {
            var tracingPort = promPort + 1
            while (isPortUsed(tracingPort)) {
                tracingPort++
            }
            return tracingPort
        }

        internal fun renderTracingListenerArgument(
            tracingHost: String,
            tracingPort: Int?,
        ): String? {
            if (tracingPort == null) {
                return null
            }

            return "--tracer-socket-network-accept ${'$'}{TRACING_HOST}:${'$'}{TRACING_PORT}"
        }

        internal fun renderManualStartupScript(
            request: CreateNodeRequest,
            startupNodeType: String,
            host: Host,
            tracingHost: String,
            tracingPort: Int?,
        ): String {
            val listenerArgument = renderTracingListenerArgument(tracingHost, tracingPort)
            val listenerLine = listenerArgument?.let { "|  $it \\\n" } ?: ""

            return when (startupNodeType) {
                NODE_TYPE_RELAY -> {
                    """
                |#!/bin/bash
                |OLDPWD=`pwd`
                |cd ${host.nodeHomePath}/${request.name}
                |source ${host.nodeHomePath}/${request.name}/env
                |nohup ${host.cardanoNodePath} \
                |  +RTS -N${request.processorThreads} -RTS run \
                |  --topology ${'$'}{TOPOLOGY} \
                |  --database-path ${'$'}{DATABASE_PATH} \
                |  --socket-path ${'$'}{SOCKET_PATH} \
                |  --host-addr ${'$'}{HOST_ADDR} \
                |  --port ${'$'}{PORT} \
                |  --config ${'$'}{CONFIG} \
                $listenerLine|  > ${request.name}.log 2>&1 &
                |echo ${'$'}! > ${request.name}.pid
                |cd ${'$'}OLDPWD
                |echo "Started with logfile ${request.name}.log"
                    """.trimMargin()
                }

                else -> {
                    """
                |#!/bin/bash
                |OLDPWD=`pwd`
                |cd ${host.nodeHomePath}/${request.name}
                |source ${host.nodeHomePath}/${request.name}/env
                |nohup ${host.cardanoNodePath} \
                |  +RTS -N${request.processorThreads} -RTS run \
                |  --topology ${'$'}{TOPOLOGY} \
                |  --database-path ${'$'}{DATABASE_PATH} \
                |  --socket-path ${'$'}{SOCKET_PATH} \
                |  --host-addr ${'$'}{HOST_ADDR} \
                |  --port ${'$'}{PORT} \
                |  --config ${'$'}{CONFIG} \
                |  --shelley-kes-key ${'$'}{SHELLEY_KES_KEY} \
                |  --shelley-vrf-key ${'$'}{SHELLEY_VRF_KEY} \
                |  --shelley-operational-certificate ${'$'}{SHELLEY_OPCERT} \
                $listenerLine|  > ${request.name}.log 2>&1 &
                |echo ${'$'}! > ${request.name}.pid
                |cd ${'$'}OLDPWD
                |echo "Started with logfile ${request.name}.log"
                    """.trimMargin()
                }
            }
        }

        internal fun renderSystemdContent(
            startupNodeType: String,
            host: Host,
            name: String,
            processorThreads: Int,
            tracingHost: String,
            tracingPort: Int?,
        ): String {
            val listenerArgument = renderTracingListenerArgument(tracingHost, tracingPort)
            val listenerLine = listenerArgument?.let { "|  $it \\\n" } ?: ""

            return when (startupNodeType) {
                NODE_TYPE_RELAY -> {
                    """
                |[Unit]
                |Description=Cardano Haskell Node - $name
                |After=syslog.target
                |StartLimitIntervalSec=0
                |
                |[Service]
                |Type=simple
                |Restart=always
                |RestartSec=5
                |User=${host.sshUser}
                |LimitNOFILE=131072
                |WorkingDirectory=${host.nodeHomePath}/$name
                |EnvironmentFile=${host.nodeHomePath}/$name/env
                |ExecStart=${host.cardanoNodePath} \
                |  +RTS -N$processorThreads -RTS run \
                |  --topology ${'$'}{TOPOLOGY} \
                |  --database-path ${'$'}{DATABASE_PATH} \
                |  --socket-path ${'$'}{SOCKET_PATH} \
                |  --host-addr ${'$'}{HOST_ADDR} \
                |  --port ${'$'}{PORT} \
                |  --config ${'$'}{CONFIG} \
                $listenerLine|KillSignal=SIGINT
                |SyslogIdentifier=$name-node
                |
                |[Install]
                |WantedBy=multi-user.target
                    """.trimMargin()
                }

                NODE_TYPE_CORE -> {
                    """
                |[Unit]
                |Description=Cardano Haskell Node - $name
                |After=syslog.target
                |StartLimitIntervalSec=0
                |
                |[Service]
                |Type=simple
                |Restart=always
                |RestartSec=5
                |User=${host.sshUser}
                |LimitNOFILE=131072
                |WorkingDirectory=${host.nodeHomePath}/$name
                |EnvironmentFile=${host.nodeHomePath}/$name/env
                |ExecStart=${host.cardanoNodePath} \
                |  +RTS -N$processorThreads -RTS run \
                |  --topology ${'$'}{TOPOLOGY} \
                |  --database-path ${'$'}{DATABASE_PATH} \
                |  --socket-path ${'$'}{SOCKET_PATH} \
                |  --host-addr ${'$'}{HOST_ADDR} \
                |  --port ${'$'}{PORT} \
                |  --config ${'$'}{CONFIG} \
                |  --shelley-kes-key ${'$'}{SHELLEY_KES_KEY} \
                |  --shelley-vrf-key ${'$'}{SHELLEY_VRF_KEY} \
                |  --shelley-operational-certificate ${'$'}{SHELLEY_OPCERT} \
                $listenerLine|KillSignal=SIGINT
                |SyslogIdentifier=$name-node
                |
                |[Install]
                |WantedBy=multi-user.target
                    """.trimMargin()
                }

                else -> {
                    """
                |[Unit]
                |Description=Cardano Haskell Node - $name
                |After=syslog.target
                |StartLimitIntervalSec=0
                |
                |[Service]
                |Type=simple
                |Restart=always
                |RestartSec=5
                |User=${host.sshUser}
                |LimitNOFILE=131072
                |WorkingDirectory=${host.nodeHomePath}/$name
                |EnvironmentFile=${host.nodeHomePath}/$name/env
                |ExecStart=${host.cardanoNodePath} \
                |  +RTS -N$processorThreads -RTS run \
                |  --topology ${'$'}{TOPOLOGY} \
                |  --database-path ${'$'}{DATABASE_PATH} \
                |  --socket-path ${'$'}{SOCKET_PATH} \
                |  --host-addr ${'$'}{HOST_ADDR} \
                |  --port ${'$'}{PORT} \
                |  --config ${'$'}{CONFIG} \
                |  --bulk-credentials-file ${'$'}{BULK_CREDENTIALS} \
                $listenerLine|KillSignal=SIGINT
                |SyslogIdentifier=$name-node
                |
                |[Install]
                |WantedBy=multi-user.target
                    """.trimMargin()
                }
            }
        }

        @MessageMapping("/governancevote")
        @Transactional
        fun submitGovernanceVote(request: GovernanceVoteRequest) {
            try {
                if (!walletUtils.isValidSpendingPassword(request.spendingPassword)) {
                    throw IllegalArgumentException("Invalid spending password!")
                }

                // Validate governance action ID format (must be bech32 gov_action1...)
                if (!request.govActionId.startsWith("gov_action1")) {
                    throw IllegalArgumentException("Invalid governance action ID format. Must be bech32 format starting with 'gov_action1'")
                }

                val defaultNode = nodeRepository.findDefault() ?: throw IOException("Default node not found!")
                val defaultHost =
                    hostRepository.findByIdOrNull(defaultNode.hostId)
                        ?: throw IOException("Default Host not found!")
                val defaultHostConnection = HostConnection(defaultHost, defaultNode)
                val socketPath = "--socket-path ${defaultHost.nodeHomePath}/${defaultNode.name}/db/socket"

                val genesisFile =
                    fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)
                        ?: throw IOException("Shelley genesis file not found!")
                val genesis = shelleyGenesisAdapter.fromJson(genesisFile.content)!!
                val magicString =
                    if (genesis.networkId.equals("testnet", ignoreCase = true)) {
                        "--testnet-magic ${genesis.networkMagic}"
                    } else {
                        "--mainnet"
                    }

                val tempFiles = mutableListOf<String>()
                tempFiles.add("/tmp/protocol-parameters.json")
                tempFiles.add("/tmp/governance-vote.txbody")
                tempFiles.add("/tmp/governance-vote.txsigned")

                try {
                    val era = cardanoRepository.getEra(defaultHost, defaultNode, magicString, socketPath)
                    val protocolParamsJson =
                        defaultHostConnection
                            .command(
                                "${defaultHost.cardanoCliPath} conway query protocol-parameters $magicString $socketPath --output-json"
                            ).trim()
                    defaultHostConnection.commandWriteFile("/tmp/protocol-parameters.json", protocolParamsJson)

                    // Build the transaction
                    var witnessCount = 0
                    val transaction = StringBuilder()
                    val voteFiles = StringBuilder()
                    val signingKeys = StringBuilder()

                    transaction.append("${defaultHost.cardanoCliPath} conway transaction build-raw ")

                    // Get fee payer account
                    val feePayerAccount =
                        walletRepository.findByIdOrNull(request.feesAccountId)
                            ?: throw IOException("Fees account not found!")
                    val utxos =
                        walletUtils.getUtxos(
                            defaultHost,
                            defaultHostConnection,
                            magicString,
                            socketPath,
                            era,
                            feePayerAccount.paymentAddr
                        )
                    utxos.forEach { utxo ->
                        transaction.append("--tx-in ${utxo.hash}#${utxo.ix} ")
                    }
                    val feePayerAccountBalance = utxos.sumByBigInteger { it.lovelace }
                    log.debug("feePayerAccount balance: {}", feePayerAccountBalance)

                    witnessCount++ // fee payer is a witness
                    defaultHostConnection.commandWriteFile(
                        "/tmp/feepayer.payment.skey",
                        walletUtils.getSKeyContent(requireNotNull(feePayerAccount.paymentSkey), request.spendingPassword)
                    )
                    signingKeys.append("--signing-key-file /tmp/feepayer.payment.skey ")
                    tempFiles.add("/tmp/feepayer.payment.skey")

                    // initial dummy value to return change to the fee payer account
                    transaction.append("--tx-out ${feePayerAccount.paymentAddr}+$feePayerAccountBalance ")

                    // Get TTL
                    val queryTipJson =
                        defaultHostConnection
                            .command("${defaultHost.cardanoCliPath} conway query tip $magicString $socketPath --output-json")
                            .trim()
                    val ttl = queryTipAdapter.fromJson(queryTipJson)?.let { it.slot + 21600 } ?: -1
                    transaction.append("--invalid-hereafter $ttl ")
                    transaction.append("--fee 200000 ")

                    // Generate vote files for each node
                    for (nodeVote in request.votes) {
                        val node = nodeRepository.findByIdOrNull(nodeVote.nodeId)
                            ?: throw IOException("Node ${nodeVote.nodeId} not found!")

                        if (node.type == NODE_TYPE_RELAY) {
                            throw IOException("Cannot vote with relay node ${node.name}!")
                        }

                        val coldVKeyFile =
                            fileRepository.findByIdOrNull(node.coreVKeyId)
                                ?: throw IOException("Cold vkey not found for node ${node.name}!")
                        val coldSKeyFile =
                            fileRepository.findByIdOrNull(node.coreSKeyId)
                                ?: throw IOException("Cold skey not found for node ${node.name}!")

                        // Write the vkey file
                        val vkeyPath = "/tmp/node_${node.id}.node.vkey"
                        val skeyPath = "/tmp/node_${node.id}.node.skey"
                        val votePath = "/tmp/node_${node.id}.vote"
                        defaultHostConnection.commandWriteFile(vkeyPath, coldVKeyFile.content)
                        defaultHostConnection.commandWriteFile(
                            skeyPath,
                            walletUtils.getSKeyContent(coldSKeyFile, request.spendingPassword).trim()
                        )
                        tempFiles.add(vkeyPath)
                        tempFiles.add(skeyPath)
                        tempFiles.add(votePath)

                        // Determine vote choice flag
                        val voteFlag = when (nodeVote.vote.uppercase()) {
                            "YES" -> "--yes"
                            "NO" -> "--no"
                            "ABSTAIN" -> "--abstain"
                            else -> throw IOException("Invalid vote choice: ${nodeVote.vote}")
                        }

                        // Generate the vote file using bech32 governance action ID
                        val decodedGovAction = Bech32.decode(request.govActionId)
                        val govActionBytes = decodedGovAction.bytes

                        // The final byte is the index
                        val indexByte = govActionBytes.last()
                        val govActionIndex = (indexByte.toInt() and 0xFF).toString()

                        // The entire beginning is the transaction id bytes
                        val govActionTxIdBytes = govActionBytes.sliceArray(0 until govActionBytes.size - 1)
                        val govActionTxId = govActionTxIdBytes.joinToString("") { "%02x".format(it) }

                        val voteCreateCommand = StringBuilder()
                        voteCreateCommand.append("${defaultHost.cardanoCliPath} conway governance vote create ")
                        voteCreateCommand.append("$voteFlag ")
                        voteCreateCommand.append("--governance-action-tx-id $govActionTxId ")
                        voteCreateCommand.append("--governance-action-index $govActionIndex ")
                        voteCreateCommand.append("--cold-verification-key-file $vkeyPath ")
                        voteCreateCommand.append("--out-file $votePath")

                        log.debug("Creating vote file for node ${node.name}: $voteCreateCommand")
                        defaultHostConnection.command(voteCreateCommand.toString())

                        voteFiles.append("--vote-file $votePath ")
                        signingKeys.append("--signing-key-file $skeyPath ")
                        witnessCount++
                    }

                    // Build dummy transaction for fee calculation
                    transaction.append(voteFiles)
                    transaction.append("--out-file /tmp/governance-vote.txbody")
                    log.debug("Building dummy transaction: $transaction")
                    defaultHostConnection.command(transaction.toString())

                    // Calculate fees
                    val feesString =
                        defaultHostConnection
                            .command(
                                "${defaultHost.cardanoCliPath} conway transaction calculate-min-fee --tx-body-file /tmp/governance-vote.txbody --protocol-params-file /tmp/protocol-parameters.json --tx-in-count ${utxos.size} --tx-out-count 1 $magicString --witness-count $witnessCount --byron-witness-count 0 --output-text"
                            ).trim()
                    val fees = feesString.split(" ")[0].toBigInteger()
                    log.debug("Calculated fees: $fees")

                    // Calculate change
                    val change = feePayerAccountBalance - fees
                    if (change < BigInteger.ONE) {
                        throw IOException("Not enough funds to pay fees of $fees lovelace!")
                    }

                    // Handle token change
                    val tokenChange = StringBuilder()
                    utxos.toNativeAssetMap().forEach { (currency, amount) ->
                        if (amount > BigInteger.ZERO) {
                            tokenChange.append("+$amount $currency")
                        }
                    }

                    // Rebuild transaction with correct fee
                    val realTransaction =
                        transaction
                            .toString()
                            .replace("--fee 200000 ", "--fee $fees ")
                            .replace(
                                "--tx-out ${feePayerAccount.paymentAddr}+$feePayerAccountBalance ",
                                "--tx-out '${feePayerAccount.paymentAddr}+$change$tokenChange' "
                            )
                    log.debug("Final transaction command: $realTransaction")
                    defaultHostConnection.command(realTransaction)

                    // Sign the transaction
                    defaultHostConnection.command(
                        "${defaultHost.cardanoCliPath} conway transaction sign --tx-body-file /tmp/governance-vote.txbody $signingKeys $magicString --out-file /tmp/governance-vote.txsigned"
                    )

                    val txid = runBlocking {
                        TransactionCache.withLock {
                            // Submit the transaction
                            defaultHostConnection.command(
                                "${defaultHost.cardanoCliPath} conway transaction submit --tx-file /tmp/governance-vote.txsigned $magicString $socketPath"
                            )
                            val txid =
                                defaultHostConnection
                                    .command(
                                        "${defaultHost.cardanoCliPath} conway transaction txid --tx-body-file /tmp/governance-vote.txbody --output-text"
                                    ).trim()
                            val txSigned = defaultHostConnection.commandReadFile("/tmp/governance-vote.txsigned")
                            TransactionCache.put(txid, txSigned)

                            val cborBytes = txSignedAdapter.fromJson(txSigned)!!.cborHex.hexToByteArray()
                            ledgerDao.updateLiveLedgerState(txid, cborBytes)
                            transactionRepository.save(Transaction(txid = txid))
                            txid
                        }
                    }

                    webSocketTemplate.convertAndSend(
                        "/topic/messages",
                        SocketResponse.Success(
                            type = "governancevote",
                            data = "Governance vote submitted successfully. TxId: $txid"
                        )
                    )
                } finally {
                    // Cleanup all temp files
                    defaultHostConnection.command("rm -f ${tempFiles.joinToString(" ")}")
                }
            } catch (e: Throwable) {
                log.error("Error submitting governance vote!", e)
                webSocketTemplate.convertAndSend(
                    "/topic/messages",
                    SocketResponse.Error(type = "governancevote", exception = e)
                )
                // rethrow so db transaction is rolled back
                throw RuntimeException(e)
            }
        }

        private fun createTopologyFile(
            byronGenesisFileName: String,
            hostConnection: HostConnection,
            nodeFolder: String
        ) {
            val topologyFile =
                fileRepository.findByName(byronGenesisFileName.substringBeforeLast("-byron") + "-topology.json")
            topologyFile?.let {
                if (!hostConnection.commandFileExists("$nodeFolder/topology.json")) {
                    log.debug("Creating $nodeFolder/topology.json from db file ${topologyFile.name}")
                    hostConnection.commandWriteFile("$nodeFolder/topology.json", topologyFile.content)
                } else {
                    log.debug("SKIP Creating $nodeFolder/topology.json from db file ${topologyFile.name}")
                }
            } ?: throw IOException("Topology file not found in db!")
        }

        private fun createGenesisFile(
            prefix: String,
            genesisFileId: Long,
            hostConnection: HostConnection,
            nodeFolder: String
        ): com.swiftmako.jormanager.entities.File {
            val genesisFile = fileRepository.findByIdOrNull(genesisFileId)
            genesisFile?.let {
                log.debug("Creating $nodeFolder/$prefix-genesis.json from db file ${genesisFile.name}")
                hostConnection.commandWriteFile("$nodeFolder/$prefix-genesis.json", genesisFile.content)
                return genesisFile
            } ?: throw IOException("Genesis file not found in db!")
        }

        private fun createNodeFolders(
            hostConnection: HostConnection,
            nodeFolder: String
        ) {
            hostConnection.command("mkdir -p $nodeFolder/db")
            hostConnection.command("mkdir -p $nodeFolder/logs")
        }

        companion object {
            const val NODE_TYPE_RELAY = "relay"
            const val NODE_TYPE_CORE = "core"
            const val NODE_TYPE_POOL = "pool"
            private const val STDOUT_MACHINE_FORMAT_BACKEND = "Stdout MachineFormat"
            private const val FORWARDER_BACKEND = "Forwarder"
            private const val MARKUS_ROOT_TRACE_SEVERITY = "Notice"
            private const val MIN_TRACE_SEVERITY = "Critical"
            private const val TRACE_FORWARDER_CONN_QUEUE_SIZE = 64
            private const val TRACE_FORWARDER_DISCONN_QUEUE_SIZE = 128
            private const val TRACE_FORWARDER_MAX_RECONNECT_DELAY = 30
            private val MARKUS_TRACE_OPTIONS_JSON =
                """
                {
                  "": {
                    "backends": [
                      "Stdout MachineFormat",
                      "PrometheusSimple 127.0.0.1 12798"
                    ],
                    "severity": "Notice"
                  },
                  "Version.NodeVersion": {
                    "severity": "Info"
                  },
                  "Startup.DiffusionInit": {
                    "severity": "Info"
                  },
                  "Resources": {
                    "severity": "Info",
                    "maxFrequency": 0.0167
                  },
                  "ChainDB.LedgerEvent.Replay": {
                    "severity": "Info",
                    "maxFrequency": 0.0668
                  },
                  "ChainDB.ImmDbEvent": {
                    "severity": "Warning"
                  },
                  "ChainDB.ImmDbEvent.ChunkValidation.ValidatedChunk": {
                    "severity": "Info"
                  },
                  "Net.ConnectionManager.Remote": {
                    "severity": "Info"
                  },
                  "Net.PeerSelection": {
                    "severity": "Info"
                  },
                  " Net.PeerSelection.Actions.ConnectionError": {
                    "severity": "Silence"
                  },
                  "Net.InboundGovernor.Remote": {
                    "severity": "Info"
                  },
                  "Net.InboundGovernor.Remote.InboundGovernorCounters": {
                    "severity": "Info",
                    "maxFrequency": 0.0167
                  },
                  "Net.ErrorPolicy": {
                    "severity": "Info"
                  },
                  "Net.AcceptPolicy.ConnectionRateLimiting": {
                    "severity": "Info",
                    "maxFrequency": 0.0167
                  },
                  "Net.AcceptPolicy.ConnectionLimitResume": {
                    "severity": "Info",
                    "maxFrequency": 0.0167
                  },
                  "ChainSync.Client": {
                    "severity": "Warning"
                  },
                  "Forge.Loop": {
                    "severity": "Info"
                  },
                  "Forge.Loop.NodeNotLeader": {
                    "severity": "Silence"
                  },
                  "Forge.Loop.StartLeadershipCheck": {
                    "severity": "Silence"
                  },
                  "Forge.StateInfo": {
                    "severity": "Info",
                    "maxFrequency": 0.0002783
                  },
                  "ChainDB.AddBlockEvent.SwitchedToAFork": {
                    "severity": "Info"
                  },
                  "ChainDB.AddBlockEvent.AddedToCurrentChain": {
                    "maxFrequency": 2.0
                  },
                  "ChainSync.Client.DownloadedHeader": {
                    "severity": "Info",
                    "maxFrequency": 14.0
                  },
                  "BlockFetch.Client.SendFetchRequest": {
                    "severity": "Info"
                  },
                  "BlockFetch.Client.CompletedBlockFetch": {
                    "severity": "Info",
                    "maxFrequency": 4.0
                  }
                }
                """.trimIndent()
            private val LEGACY_TRACING_KEYS_TO_REMOVE =
                listOf(
                    "defaultBackends",
                    "defaultScribes",
                    "setupBackends",
                    "setupScribes",
                    "rotation",
                    "TracingVerbosity",
                    "EKGBackend",
                    "PrometheusSimple",
                    "hasEkg",
                    "hasEKG",
                    "hasPrometheus",
                )
            private val IP4_ADDRESS =
                Regex(
                    "(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)"
                )
        }
    }
