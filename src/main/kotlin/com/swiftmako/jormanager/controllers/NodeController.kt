package com.swiftmako.jormanager.controllers

import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.Host
import com.swiftmako.jormanager.entities.Node
import com.swiftmako.jormanager.entities.Relay
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.entities.Transaction
import com.swiftmako.jormanager.ktx.sumByLong
import com.swiftmako.jormanager.model.CreateNodeRequest
import com.swiftmako.jormanager.model.Genesis
import com.swiftmako.jormanager.model.GenesisByron
import com.swiftmako.jormanager.model.ProtocolParameters
import com.swiftmako.jormanager.model.QueryTip
import com.swiftmako.jormanager.model.RotateKesRequest
import com.swiftmako.jormanager.model.metadata.pool.About
import com.swiftmako.jormanager.model.metadata.pool.Company
import com.swiftmako.jormanager.model.metadata.pool.ExtendedMetadata
import com.swiftmako.jormanager.model.metadata.pool.Info
import com.swiftmako.jormanager.model.metadata.pool.Itn
import com.swiftmako.jormanager.model.metadata.pool.Social
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import com.swiftmako.jormanager.repositories.RelayRepository
import com.swiftmako.jormanager.repositories.TransactionRepository
import com.swiftmako.jormanager.repositories.WalletRepository
import com.swiftmako.jormanager.services.MetadataService
import kotlinx.coroutines.channels.BroadcastChannel
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
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import retrofit2.Retrofit
import java.io.File
import java.io.IOException

@Controller
class NodeController @Autowired constructor(
        private val nodeRepository: NodeRepository,
        private val hostRepository: HostRepository,
        private val fileRepository: FileRepository,
        private val walletRepository: WalletRepository,
        private val transactionRepository: TransactionRepository,
        private val relayRepository: RelayRepository,
        private val walletUtils: WalletUtils,
        private val webSocketTemplate: SimpMessagingTemplate,
        @Qualifier("nodesChannel") private val nodesChannel: BroadcastChannel<Node>,
        moshi: Moshi,
        private val retrofit: Retrofit,
        private val okHttpClient: OkHttpClient,
) {
    private val log = LoggerFactory.getLogger(NodeController::class.java)

    private val genesisAdapter by lazy { moshi.adapter(Genesis::class.java) }
    private val genesisByronAdapter by lazy { moshi.adapter(GenesisByron::class.java) }
    private val protocolParamsAdapter by lazy { moshi.adapter(ProtocolParameters::class.java) }
    private val queryTipAdapter by lazy { moshi.adapter(QueryTip::class.java) }
    private val extendedMetadataAdapter by lazy { moshi.adapter(ExtendedMetadata::class.java) }
    private val metadataAdapter by lazy { moshi.adapter(com.swiftmako.jormanager.model.metadata.pool.Metadata::class.java) }

    @MessageMapping("/nodes")
    @SendTo("/topic/messages")
    fun getNodes(): SocketResponse<List<Node>> {
        val nodes = nodeRepository.findAll(Sort.by(Sort.Direction.ASC, "name"))
        return SocketResponse.Success(type = "nodes", data = nodes)
    }

    @MessageMapping("/restartnode")
    @SendTo("/topic/messages")
    @Synchronized
    fun restartNode(nodeId: Long): SocketResponse<String> {
        return try {
            nodeRepository.findByIdOrNull(nodeId)?.let { node ->
                hostRepository.findByIdOrNull(node.hostId)?.let { host ->
                    HostConnection(host).use { hostConnection ->
                        val processId = if (hostConnection.hasSystemd) {
                            hostConnection.command("systemctl show --property MainPID --value ${node.name}-node.service").trim()
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
                    }
                } ?: throw IllegalArgumentException("Host not found!")
            } ?: throw IllegalArgumentException("Node not found!")
        } catch (e: Throwable) {
            log.error("Error Restarting Node!", e)
            SocketResponse.Error(type = "restartnode", exception = e)
        }
    }

    @MessageMapping("/createnode")
    @Transactional
    fun createNode(request: CreateNodeRequest) {
        try {
            if (!walletUtils.isValidSpendingPassword(request.spendingPassword)) {
                throw IllegalArgumentException("Invalid spending password!")
            }
            hostRepository.findByIdOrNull(request.hostId)?.let { host ->
                HostConnection(host).use { hostConnection ->
                    // validate sudo password right away
                    hostConnection.sudoCommand("pwd", request.sudoPassword)

                    when (request.type) {
                        NODE_TYPE_RELAY -> {
                            val nodeFolder = "${host.nodeHomePath}${File.separator}${request.name}"
                            createNodeFolders(hostConnection, nodeFolder)
                            val genesisByronFile = createGenesisFile("byron", request.genesisByronFileId, hostConnection, nodeFolder)
                            createGenesisFile("shelley", request.genesisShelleyFileId, hostConnection, nodeFolder)
                            createTopologyFile(genesisByronFile.name, hostConnection, nodeFolder)
                            val (configFileId, ekgPort, promPort) = createConfigFile(request.hostId, genesisByronFile.name, request.ekgPort, request.promPort, hostConnection, nodeFolder)
                            createEnvFile(hostConnection, request.type, request.name, nodeFolder, request.listen, request.port)
                            createSystemdFile(request, host, hostConnection)
                            createManualStartupScripts(request, host, hostConnection)

                            if (request.isDefault) {
                                val oldDefault = nodeRepository.findDefault()
                                oldDefault?.let {
                                    // Make old default no longer the default
                                    nodeRepository.save(oldDefault.copy(isDefault = false))
                                }
                            }

                            val node = Node(
                                    hostId = host.id!!,
                                    color = request.color,
                                    type = request.type,
                                    processorThreads = request.processorThreads,
                                    name = request.name,
                                    listen = request.listen,
                                    port = request.port,
                                    ekgPort = ekgPort,
                                    promPort = promPort,
                                    genesisByronFileId = request.genesisByronFileId,
                                    genesisShelleyFileId = request.genesisShelleyFileId,
                                    configFileId = configFileId,
                                    isDefault = request.isDefault
                            )
                            val savedNode = nodeRepository.save(node)

                            // send it to the channel for monitoring
                            nodesChannel.offer(savedNode)

                            // send all to the client for ui updates
                            val nodes = nodeRepository.findAll(Sort.by(Sort.Direction.ASC, "name"))
                            webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success(type = "nodes", data = nodes))
                        }
                        NODE_TYPE_CORE -> {
                            nodeRepository.findDefault()?.let { defaultNode ->
                                fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)?.let { genesisFile ->
                                    val genesis = genesisAdapter.fromJson(genesisFile.content)!!
                                    val magicString = if (genesis.networkId.equals("testnet", ignoreCase = true)) {
                                        "--testnet-magic ${genesis.networkMagic}"
                                    } else {
                                        "--mainnet"
                                    }
                                    fileRepository.findByIdOrNull((defaultNode.genesisByronFileId))?.let { genesisByronFile ->
                                        val genesisByron = genesisByronAdapter.fromJson(genesisByronFile.content)!!

                                        hostRepository.findByIdOrNull(defaultNode.hostId)?.let { defaultHost ->
                                            HostConnection(defaultHost, defaultNode).use { defaultHostConnection ->
                                                try {
                                                    val protocolParamsJson = defaultHostConnection.command("${host.cardanoCliPath} shelley query protocol-parameters --cardano-mode $magicString").trim()
                                                    defaultHostConnection.commandWriteFile("/tmp/protocol-parameters.json", protocolParamsJson)
                                                    val protocolParameters = protocolParamsAdapter.fromJson(protocolParamsJson)
                                                            ?: throw IOException("Invalid protocol params!")

                                                    // 1. Create a transaction to dump EVERYTHING into
                                                    var depositAndFees = 0L
                                                    var witnessCount = 0
                                                    val transaction = StringBuilder()
                                                    val certificates = StringBuilder()
                                                    val signingKeys = StringBuilder()
                                                    transaction.append("${host.cardanoCliPath} shelley transaction build-raw ")
                                                    val feePayerAccount = walletRepository.findByIdOrNull(request.registrationFeesAccount)
                                                            ?: throw IOException("Registration fees account not found!")
                                                    val utxos = walletUtils.getUtxos(host, defaultHostConnection, magicString, feePayerAccount.paymentAddr)
                                                    utxos.forEach { utxo ->
                                                        transaction.append("--tx-in ${utxo.hash}#${utxo.ix} ")
                                                    }
                                                    log.debug("feePayerAccount balance: ${utxos.sumByLong { it.lovelace }}")
                                                    witnessCount++ // fee payer is a witness
                                                    defaultHostConnection.commandWriteFile("/tmp/feepayer.payment.skey", walletUtils.getSKeyContent(requireNotNull(feePayerAccount.paymentSkey), request.spendingPassword))
                                                    signingKeys.append("--signing-key-file /tmp/feepayer.payment.skey ")

                                                    // initial dummy value to return change to the fee payer account.
                                                    // We'll replace this with the actual change to return later
                                                    transaction.append("--tx-out ${feePayerAccount.paymentAddr}+1234567890 ")

                                                    val queryTipString = defaultHostConnection.command("${host.cardanoCliPath} shelley query tip $magicString").trim()
                                                    val ttl = queryTipAdapter.fromJson(queryTipString)?.let { it.slotNo + 1000 }
                                                            ?: -1
                                                    transaction.append("--ttl $ttl ")
                                                    transaction.append("--fee 100 ")

                                                    // 2. Register owner address on the chain if not yet registered
                                                    val ownerStakingAccount = walletRepository.findByIdOrNull(request.ownerStakingAccount)
                                                            ?: throw IOException("Owner staking account not found!")
                                                    defaultHostConnection.commandWriteFile("/tmp/owner.staking.skey", walletUtils.getSKeyContent(requireNotNull(ownerStakingAccount.stakingSkey), request.spendingPassword))
                                                    defaultHostConnection.commandWriteFile("/tmp/owner.staking.vkey", requireNotNull(ownerStakingAccount.stakingVkey?.content))
                                                    val ownerStakingWalletItem = walletUtils.getWalletItem(defaultHost, defaultHostConnection, magicString, ownerStakingAccount)
                                                    if (!ownerStakingWalletItem.stakingAddrRegistered) {
                                                        // owner staking address is *not* registered. We should register it on chain as part of the transaction
                                                        ownerStakingAccount.stakingRegCert?.let { stakingRegCert ->
                                                            defaultHostConnection.commandWriteFile("/tmp/owner.staking.cert", stakingRegCert.content)
                                                        }
                                                                ?: throw IOException("Staking reg cert not found on owner account!")
                                                        depositAndFees += protocolParameters.keyDeposit
                                                        certificates.append("--certificate /tmp/owner.staking.cert ")
                                                    }
                                                    witnessCount++ // the owner.staking.skey is a witness
                                                    signingKeys.append("--signing-key-file /tmp/owner.staking.skey ")

                                                    // 3. Register rewards address on the chain if not yet registered
                                                    val rewardsStakingAccount = walletRepository.findByIdOrNull(request.rewardsStakingAccount)
                                                            ?: throw IOException("Rewards staking account not found!")
                                                    defaultHostConnection.commandWriteFile("/tmp/rewards.staking.skey", walletUtils.getSKeyContent(requireNotNull(rewardsStakingAccount.stakingSkey), request.spendingPassword))
                                                    defaultHostConnection.commandWriteFile("/tmp/rewards.staking.vkey", requireNotNull(rewardsStakingAccount.stakingVkey?.content))
                                                    if (request.rewardsStakingAccount != request.ownerStakingAccount) {
                                                        val rewardsStakingWalletItem = walletUtils.getWalletItem(defaultHost, defaultHostConnection, magicString, rewardsStakingAccount)
                                                        if (!rewardsStakingWalletItem.stakingAddrRegistered) {
                                                            // rewards staking address is *not* registered. We should register it on chain as part of the transaction
                                                            rewardsStakingAccount.stakingRegCert?.let { stakingRegCert ->
                                                                defaultHostConnection.commandWriteFile("/tmp/rewards.staking.cert", stakingRegCert.content)
                                                            }
                                                                    ?: throw IOException("Staking reg cert not found on rewards account!")
                                                            depositAndFees += protocolParameters.keyDeposit
                                                            certificates.append("--certificate /tmp/rewards.staking.cert ")
                                                        }
                                                    }
                                                    if (ownerStakingAccount != rewardsStakingAccount) {
                                                        witnessCount++ // the rewards account is a witness
                                                        signingKeys.append("--signing-key-file /tmp/rewards.staking.skey ")
                                                    }

                                                    // 4. Keys and opcert
                                                    val coreSKeyId: Long
                                                    val coreVKeyId: Long
                                                    val coreCounterId: Long
                                                    if (request.generateColdKeys) {
                                                        defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley node key-gen --verification-key-file /tmp/core.node.vkey --signing-key-file /tmp/core.node.skey --operational-certificate-issue-counter /tmp/core.node.counter")
                                                        val coreSKeyContent = defaultHostConnection.commandReadFile("/tmp/core.node.skey")
                                                        val coreVKeyContent = defaultHostConnection.commandReadFile("/tmp/core.node.vkey")
                                                        val coreSKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.node.skey", content = walletUtils.encryptSKeyContent(coreSKeyContent, request.spendingPassword))
                                                        val coreVKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.node.vkey", content = coreVKeyContent)
                                                        coreSKeyId = fileRepository.save(coreSKey).id!!
                                                        coreVKeyId = fileRepository.save(coreVKey).id!!
                                                    } else {
                                                        defaultHostConnection.commandWriteFile("/tmp/core.node.skey", requireNotNull(request.coldSKey).trim())
                                                        defaultHostConnection.commandWriteFile("/tmp/core.node.vkey", requireNotNull(request.coldVKey).trim())
                                                        defaultHostConnection.commandWriteFile("/tmp/core.node.counter", requireNotNull(request.coldCounter).trim())
                                                        val coreSKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.node.skey", content = walletUtils.encryptSKeyContent(requireNotNull(request.coldSKey).trim(), request.spendingPassword))
                                                        val coreVKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.node.vkey", content = requireNotNull(request.coldVKey).trim())
                                                        coreSKeyId = fileRepository.save(coreSKey).id!!
                                                        coreVKeyId = fileRepository.save(coreVKey).id!!
                                                    }
                                                    witnessCount++ // the core.node.skey is always a witness
                                                    signingKeys.append("--signing-key-file /tmp/core.node.skey ")
                                                    val vrfSKeyId: Long
                                                    val vrfVKeyId: Long
                                                    val vrfSKeyContent = if (request.generateVRFKeys) {
                                                        defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley node key-gen-VRF --verification-key-file /tmp/core.vrf.vkey --signing-key-file /tmp/core.vrf.skey")
                                                        val vrfSKeyContent = defaultHostConnection.commandReadFile("/tmp/core.vrf.skey")
                                                        val vrfVKeyContent = defaultHostConnection.commandReadFile("/tmp/core.vrf.vkey")
                                                        val vrfSKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.vrf.skey", content = walletUtils.encryptSKeyContent(vrfSKeyContent, request.spendingPassword))
                                                        val vrfVKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.vrf.vkey", content = vrfVKeyContent)
                                                        vrfSKeyId = fileRepository.save(vrfSKey).id!!
                                                        vrfVKeyId = fileRepository.save(vrfVKey).id!!
                                                        vrfSKeyContent
                                                    } else {
                                                        defaultHostConnection.commandWriteFile("/tmp/core.vrf.skey", requireNotNull(request.vrfSKey).trim())
                                                        defaultHostConnection.commandWriteFile("/tmp/core.vrf.vkey", requireNotNull(request.vrfVKey).trim())
                                                        val vrfSKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.vrf.skey", content = walletUtils.encryptSKeyContent(requireNotNull(request.vrfSKey).trim(), request.spendingPassword))
                                                        val vrfVKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.vrf.vkey", content = requireNotNull(request.vrfVKey).trim())
                                                        vrfSKeyId = fileRepository.save(vrfSKey).id!!
                                                        vrfVKeyId = fileRepository.save(vrfVKey).id!!
                                                        request.vrfSKey
                                                    }
                                                    val kesSKeyId: Long
                                                    val kesVKeyId: Long
                                                    val kesSKeyContent = if (request.generateKESKeys) {
                                                        defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley node key-gen-KES --verification-key-file /tmp/core.kes.vkey --signing-key-file /tmp/core.kes.skey")
                                                        val kesSKeyContent = defaultHostConnection.commandReadFile("/tmp/core.kes.skey")
                                                        val kesVKeyContent = defaultHostConnection.commandReadFile("/tmp/core.kes.vkey")
                                                        val kesSKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.kes.skey", content = walletUtils.encryptSKeyContent(kesSKeyContent, request.spendingPassword))
                                                        val kesVKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.kes.vkey", content = kesVKeyContent)
                                                        kesSKeyId = fileRepository.save(kesSKey).id!!
                                                        kesVKeyId = fileRepository.save(kesVKey).id!!
                                                        kesSKeyContent
                                                    } else {
                                                        defaultHostConnection.commandWriteFile("/tmp/core.kes.skey", requireNotNull(request.kesSKey).trim())
                                                        defaultHostConnection.commandWriteFile("/tmp/core.kes.vkey", requireNotNull(request.kesVKey).trim())
                                                        val kesSKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.kes.skey", content = walletUtils.encryptSKeyContent(requireNotNull(request.kesSKey).trim(), request.spendingPassword))
                                                        val kesVKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.kes.vkey", content = requireNotNull(request.kesVKey).trim())
                                                        kesSKeyId = fileRepository.save(kesSKey).id!!
                                                        kesVKeyId = fileRepository.save(kesVKey).id!!
                                                        request.kesSKey
                                                    }

                                                    val slotLength = genesis.slotLength
                                                    val epochLength = genesis.epochLength
                                                    val slotsPerKESPeriod = genesis.slotsPerKESPeriod
                                                    val startTimeByron = genesisByron.startTime
                                                    val startTimeGenesis = genesis.systemStart

                                                    val startTimeSec = defaultHostConnection.command("date --date=$startTimeGenesis +%s").trim().toLong()
                                                    val transTimeEnd = startTimeSec + (BYRON_TO_SHELLEY_EPOCHS * epochLength)
                                                    val byronSlots = (startTimeSec - startTimeByron) / 20L
                                                    val transSlots = (BYRON_TO_SHELLEY_EPOCHS * epochLength) / 20L

                                                    val currentTimeSec = defaultHostConnection.command("date -u +%s").trim().toLong()

                                                    val currentSlot = if (currentTimeSec < transTimeEnd) {
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
                                                    log.debug("currentKESPeriod: $currentKESPeriod")

                                                    val maxKESEvolutions = genesis.maxKESEvolutions
                                                    val expiresKESPeriod = currentKESPeriod + maxKESEvolutions
                                                    val kesExpireTimeSec = (currentTimeSec + (slotLength * maxKESEvolutions * slotsPerKESPeriod))
                                                    val kesExpireDate = defaultHostConnection.command("date --date=@$kesExpireTimeSec").trim()
                                                    log.debug("expiresKESPeriod: $expiresKESPeriod, expireDate: $kesExpireDate")

                                                    defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley node issue-op-cert --hot-kes-verification-key-file /tmp/core.kes.vkey --cold-signing-key-file /tmp/core.node.skey --operational-certificate-issue-counter /tmp/core.node.counter --kes-period $currentKESPeriod --out-file /tmp/core.node.opcert")
                                                    val opcertContent = defaultHostConnection.commandReadFile("/tmp/core.node.opcert")
                                                    val opcertFile = com.swiftmako.jormanager.entities.File(name = "${request.name}.node.opcert", content = opcertContent)
                                                    val opcertId = fileRepository.save(opcertFile).id!!
                                                    val coreCounterContent = defaultHostConnection.commandReadFile("/tmp/core.node.counter")
                                                    val coreCounter = com.swiftmako.jormanager.entities.File(name = "${request.name}.node.counter", content = coreCounterContent)
                                                    coreCounterId = fileRepository.save(coreCounter).id!!

                                                    val poolId = defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley stake-pool id --verification-key-file /tmp/core.node.vkey --output-format hex").trim()
                                                    log.debug("poolId: $poolId")
                                                    val isPoolOnChain = isPoolOnChain(poolId)

                                                    // 5. Create and upload metadata files
                                                    var itnPrivateKeyId = -1L
                                                    var itnPublicKeyId = -1L
                                                    val itnWitnessSign = if (request.metadata?.extended?.itn?.privateKey != null && defaultHost.jcliPath != null) {
                                                        val itnPrivateKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.itn.skey", content = walletUtils.encryptSKeyContent(request.metadata.extended.itn.privateKey.trim(), request.spendingPassword))
                                                        itnPrivateKeyId = fileRepository.save(itnPrivateKey).id!!
                                                        defaultHostConnection.commandWriteFile("/tmp/core.pool.id", poolId)
                                                        defaultHostConnection.commandWriteFile("/tmp/core.itn.skey", request.metadata.extended.itn.privateKey.trim())
                                                        defaultHostConnection.command("${defaultHost.jcliPath} key sign --secret-key /tmp/core.itn.skey /tmp/core.pool.id").trim()
                                                    } else {
                                                        null
                                                    }
                                                    val itnWitnessOwner = if (request.metadata?.extended?.itn?.publicKey != null) {
                                                        val itnPublicKey = com.swiftmako.jormanager.entities.File(name = "${request.name}.itn.vkey", content = request.metadata.extended.itn.publicKey.trim())
                                                        itnPublicKeyId = fileRepository.save(itnPublicKey).id!!
                                                        request.metadata.extended.itn.publicKey.trim()
                                                    } else {
                                                        null
                                                    }

                                                    val otherPoolIds = nodeRepository.findAll().filter { node -> node.type == "core" }.mapNotNull { node -> node.poolId }

                                                    val extendedMetadata = ExtendedMetadata(
                                                            itn = itnWitnessSign?.let { Itn(owner = itnWitnessOwner, witness = itnWitnessSign) },
                                                            info = Info(
                                                                    urlPngIcon64x64 = request.metadata?.extended?.info?.icon64,
                                                                    urlPngLogo = request.metadata?.extended?.info?.logo,
                                                                    location = request.metadata?.extended?.info?.location,
                                                                    social = Social(
                                                                            twitterHandle = request.metadata?.extended?.info?.social?.twitter,
                                                                            telegramHandle = request.metadata?.extended?.info?.social?.telegram,
                                                                            facebookHandle = request.metadata?.extended?.info?.social?.facebook,
                                                                            youtubeHandle = request.metadata?.extended?.info?.social?.youtube,
                                                                            twitchHandle = request.metadata?.extended?.info?.social?.twitch,
                                                                            discordHandle = request.metadata?.extended?.info?.social?.discord,
                                                                            githubHandle = request.metadata?.extended?.info?.social?.github
                                                                    ),
                                                                    company = Company(
                                                                            name = request.metadata?.extended?.info?.company?.name,
                                                                            addr = request.metadata?.extended?.info?.company?.addr,
                                                                            city = request.metadata?.extended?.info?.company?.city,
                                                                            country = request.metadata?.extended?.info?.company?.country,
                                                                            companyId = request.metadata?.extended?.info?.company?.companyId,
                                                                            vatId = request.metadata?.extended?.info?.company?.vatId
                                                                    ),
                                                                    about = About(
                                                                            me = request.metadata?.extended?.info?.about?.me,
                                                                            server = request.metadata?.extended?.info?.about?.server,
                                                                            company = request.metadata?.extended?.info?.about?.company
                                                                    ),
                                                                    rss = request.metadata?.extended?.info?.rss
                                                            ),
                                                            telegramAdminHandle = request.metadata?.extended?.telegramAdminHandle?.let { listOf(it) }
                                                                    ?: emptyList(),
                                                            myPoolIds = otherPoolIds + poolId,
                                                            whenSaturedThenRecommend = otherPoolIds
                                                    )

                                                    val extendedMetadataJson = extendedMetadataAdapter.indent(" ").toJson(extendedMetadata)
                                                    val extendedMetadataUrl = uploadMetadata(extendedMetadataJson)
                                                    log.debug("extendedMetadataUrl: $extendedMetadataUrl")

                                                    val metadata = com.swiftmako.jormanager.model.metadata.pool.Metadata(
                                                            name = requireNotNull(request.metadata?.name),
                                                            description = requireNotNull(request.metadata?.description),
                                                            ticker = requireNotNull(request.metadata?.ticker),
                                                            homepage = requireNotNull(request.metadata?.homepage),
                                                            extended = extendedMetadataUrl
                                                    )
                                                    val metadataJson = metadataAdapter.indent(" ").toJson(metadata)
                                                    val metadataUrl = uploadMetadata(metadataJson)
                                                    log.debug("metadataUrl: $metadataUrl")

                                                    // download and get the hash!
                                                    defaultHostConnection.command("curl $metadataUrl --output /tmp/metadata.json")
                                                    val metadataHash = defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley stake-pool metadata-hash --pool-metadata-file /tmp/metadata.json").trim()

                                                    // 6. create the pool registration certificate
                                                    val poolRegcertCommand = StringBuilder().apply {
                                                        append("${defaultHost.cardanoCliPath} shelley stake-pool registration-certificate ")
                                                        append("--cold-verification-key-file /tmp/core.node.vkey ")
                                                        append("--vrf-verification-key-file /tmp/core.vrf.vkey ")
                                                        append("--pool-pledge ${request.poolPledge} ")
                                                        append("--pool-cost ${request.poolCost} ")
                                                        append("--pool-margin ${request.poolMargin} ")
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
                                                    depositAndFees += if (!isPoolOnChain) {
                                                        protocolParameters.poolDeposit
                                                    } else {
                                                        0
                                                    }

                                                    // 7. Create delegation certificates for owner(s)
                                                    defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley stake-address delegation-certificate --stake-verification-key-file /tmp/owner.staking.vkey --cold-verification-key-file /tmp/core.node.vkey --out-file /tmp/owner.deleg.cert")
                                                    certificates.append("--certificate /tmp/owner.deleg.cert ")

                                                    // 8. Calculate fees
                                                    transaction.append(certificates)
                                                    transaction.append("--out-file /tmp/transaction.txbody")
                                                    defaultHostConnection.command(transaction.toString())

                                                    log.debug("depositAndFees: $depositAndFees")
                                                    val feesString = defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley transaction calculate-min-fee --tx-body-file /tmp/transaction.txbody --protocol-params-file /tmp/protocol-parameters.json --tx-in-count ${utxos.size} --tx-out-count 1 $magicString --witness-count $witnessCount --byron-witness-count 0").trim()
                                                    val fees = feesString.split(" ")[0].toLong()
                                                    log.debug("fees: $fees")
                                                    depositAndFees += fees
                                                    log.debug("final depositAndFees: $depositAndFees")

                                                    // 9. Create the transaction
                                                    val change = utxos.sumByLong { it.lovelace } - depositAndFees
                                                    if (change < 1) {
                                                        throw IOException("Not enough funds to pay depositAndFees of $depositAndFees lovelace!")
                                                    }

                                                    val realTransaction = transaction.toString()
                                                            .replace("--fee 100 ", "--fee $fees ")
                                                            .replace("--tx-out ${feePayerAccount.paymentAddr}+1234567890 ", "--tx-out ${feePayerAccount.paymentAddr}+$change ")
                                                    log.debug("Pool Transaction Command: $realTransaction")
                                                    defaultHostConnection.command(realTransaction)

                                                    // 10. Sign the transaction
                                                    defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley transaction sign --tx-body-file /tmp/transaction.txbody $signingKeys $magicString --out-file /tmp/transaction.txsigned")

                                                    // 11. Submit the transaction
                                                    defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley transaction submit --tx-file /tmp/transaction.txsigned --cardano-mode $magicString")
                                                    val txid = defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley transaction txid --tx-body-file /tmp/transaction.txbody")
                                                    transactionRepository.save(Transaction(txid = txid))

                                                    // 13. Create and Save node information
                                                    val nodeFolder = "${host.nodeHomePath}${File.separator}${request.name}"
                                                    createNodeFolders(hostConnection, nodeFolder)
                                                    createGenesisFile("byron", request.genesisByronFileId, hostConnection, nodeFolder)
                                                    createGenesisFile("shelley", request.genesisShelleyFileId, hostConnection, nodeFolder)
                                                    createTopologyFile(genesisByronFile.name, hostConnection, nodeFolder)
                                                    val (configFileId, ekgPort, promPort) = createConfigFile(request.hostId, genesisByronFile.name, request.ekgPort, request.promPort, hostConnection, nodeFolder)
                                                    createKesVrfOpcert(request.name, kesSKeyContent, vrfSKeyContent, opcertContent, hostConnection, nodeFolder)
                                                    createEnvFile(hostConnection, request.type, request.name, nodeFolder, request.listen, request.port)
                                                    createSystemdFile(request, host, hostConnection)
                                                    createManualStartupScripts(request, host, hostConnection)

                                                    val node = Node(
                                                            hostId = host.id!!,
                                                            color = request.color,
                                                            type = request.type,
                                                            processorThreads = request.processorThreads,
                                                            name = request.name,
                                                            listen = request.listen,
                                                            port = request.port,
                                                            ekgPort = ekgPort,
                                                            promPort = promPort,
                                                            genesisByronFileId = request.genesisByronFileId,
                                                            genesisShelleyFileId = request.genesisShelleyFileId,
                                                            configFileId = configFileId,
                                                            isDefault = false,
                                                            poolId = poolId,
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
                                                    )
                                                    val savedNode = nodeRepository.save(node)

                                                    // save relays
                                                    request.relays?.let { relays ->
                                                        if (relays.isNotEmpty()) {
                                                            relayRepository.saveAll(
                                                                    relays.map { Relay(nodeId = savedNode.id!!, addr = it.addr, port = it.port) }
                                                            )
                                                        }
                                                    }

                                                    // send it to the channel for monitoring
                                                    nodesChannel.offer(savedNode)

                                                    // send all to the client for ui updates
                                                    val nodes = nodeRepository.findAll(Sort.by(Sort.Direction.ASC, "name"))
                                                    webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success(type = "nodes", data = nodes))
                                                } finally {
                                                    // Cleanup
                                                    defaultHostConnection.command("rm -f /tmp/protocol-parameters.json /tmp/transaction.txbody /tmp/transaction.txsigned /tmp/core.pool.cert  /tmp/feepayer.payment.skey /tmp/owner.staking.skey /tmp/owner.staking.vkey /tmp/owner.staking.cert /tmp/owner.deleg.cert /tmp/rewards.staking.skey /tmp/rewards.staking.vkey /tmp/rewards.staking.cert /tmp/core.node.skey /tmp/core.node.vkey /tmp/core.node.counter /tmp/core.vrf.skey /tmp/core.vrf.vkey /tmp/core.kes.skey /tmp/core.kes.vkey /tmp/core.node.opcert /tmp/core.pool.id /tmp/core.itn.skey /tmp/core.itn.vkey /tmp/metadata.json")
                                                }
                                            }
                                        } ?: throw IOException("Host not found for default node!")
                                    } ?: throw IOException("Genesis Byron file for default node not found!")
                                } ?: throw IOException("Genesis file for default node not found!")
                            } ?: throw IOException("No default node!")
                        }
                        else -> {
                            throw IOException("Invalid node type: ${request.type}")
                        }
                    }
                }

                webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success("createnode", "${request.name} created successfully!"))
            } ?: throw IOException("Invalid HostId: ${request.hostId}")
        } catch (e: Throwable) {
            log.error("Error Creating Node!", e)
            webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Error(type = "createnode", exception = e))
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
            nodeRepository.findDefault()?.let { defaultNode ->
                fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)?.let { genesisFile ->
                    val genesis = genesisAdapter.fromJson(genesisFile.content)!!
                    val magicString = if (genesis.networkId.equals("testnet", ignoreCase = true)) {
                        "--testnet-magic ${genesis.networkMagic}"
                    } else {
                        "--mainnet"
                    }
                    fileRepository.findByIdOrNull((defaultNode.genesisByronFileId))?.let { genesisByronFile ->
                        val genesisByron = genesisByronAdapter.fromJson(genesisByronFile.content)!!

                        hostRepository.findByIdOrNull(defaultNode.hostId)?.let { defaultHost ->
                            HostConnection(defaultHost, defaultNode).use { defaultHostConnection ->
                                nodeRepository.findByIdOrNull(request.id)?.let { node ->
                                    hostRepository.findByIdOrNull(node.hostId)?.let { host ->
                                        HostConnection(host, node).use { hostConnection ->
                                            try {
                                                // fetch cold keys
                                                val coreSKeyContent = fileRepository.findByIdOrNull(node.coreSKeyId)?.let { coreSKey ->
                                                    walletUtils.getSKeyContent(coreSKey, request.spendingPassword)
                                                } ?: throw IOException("Could not find core skey!")
                                                defaultHostConnection.commandWriteFile("/tmp/core.node.skey", coreSKeyContent)
                                                val coreCounter = fileRepository.findByIdOrNull(node.coreCounterId)
                                                        ?: throw IOException("Could not find core counter!")
                                                defaultHostConnection.commandWriteFile("/tmp/core.node.counter", coreCounter.content)

                                                // generate new KES keys
                                                defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley node key-gen-KES --verification-key-file /tmp/core.kes.vkey --signing-key-file /tmp/core.kes.skey")
                                                val kesSKeyContent = defaultHostConnection.commandReadFile("/tmp/core.kes.skey")
                                                val kesVKeyContent = defaultHostConnection.commandReadFile("/tmp/core.kes.vkey")
                                                val kesSKey = com.swiftmako.jormanager.entities.File(name = "${node.name}.kes.skey", content = walletUtils.encryptSKeyContent(kesSKeyContent, request.spendingPassword))
                                                fileRepository.findByIdOrNull(node.kesSKeyId)?.let { oldKesSkey ->
                                                    fileRepository.save(oldKesSkey.copy(name = "${oldKesSkey.name}-${System.currentTimeMillis()}"))
                                                }
                                                val kesVKey = com.swiftmako.jormanager.entities.File(name = "${node.name}.kes.vkey", content = kesVKeyContent)
                                                fileRepository.findByIdOrNull(node.kesVKeyId)?.let { oldKesVkey ->
                                                    fileRepository.save(oldKesVkey.copy(name = "${oldKesVkey.name}-${System.currentTimeMillis()}"))
                                                }
                                                val kesSKeyId = fileRepository.save(kesSKey).id!!
                                                val kesVKeyId = fileRepository.save(kesVKey).id!!

                                                // calculate current kes period
                                                val slotLength = genesis.slotLength
                                                val epochLength = genesis.epochLength
                                                val slotsPerKESPeriod = genesis.slotsPerKESPeriod
                                                val startTimeByron = genesisByron.startTime
                                                val startTimeGenesis = genesis.systemStart

                                                val startTimeSec = defaultHostConnection.command("date --date=$startTimeGenesis +%s").trim().toLong()
                                                val transTimeEnd = startTimeSec + (BYRON_TO_SHELLEY_EPOCHS * epochLength)
                                                val byronSlots = (startTimeSec - startTimeByron) / 20L
                                                val transSlots = (BYRON_TO_SHELLEY_EPOCHS * epochLength) / 20L

                                                val currentTimeSec = defaultHostConnection.command("date -u +%s").trim().toLong()

                                                val currentSlot = if (currentTimeSec < transTimeEnd) {
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
                                                log.debug("currentKESPeriod: $currentKESPeriod")

                                                val maxKESEvolutions = genesis.maxKESEvolutions
                                                val expiresKESPeriod = currentKESPeriod + maxKESEvolutions
                                                val kesExpireTimeSec = (currentTimeSec + (slotLength * maxKESEvolutions * slotsPerKESPeriod))
                                                val kesExpireDate = defaultHostConnection.command("date --date=@$kesExpireTimeSec").trim()
                                                log.debug("expiresKESPeriod: $expiresKESPeriod, expireDate: $kesExpireDate")

                                                defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley node issue-op-cert --hot-kes-verification-key-file /tmp/core.kes.vkey --cold-signing-key-file /tmp/core.node.skey --operational-certificate-issue-counter /tmp/core.node.counter --kes-period $currentKESPeriod --out-file /tmp/core.node.opcert")
                                                val opcertContent = defaultHostConnection.commandReadFile("/tmp/core.node.opcert")
                                                val opcertFile = com.swiftmako.jormanager.entities.File(name = "${node.name}.node.opcert", content = opcertContent)
                                                val opcertId = fileRepository.save(opcertFile).id!!
                                                fileRepository.findByIdOrNull(node.opcertId)?.let { oldOpcertFile ->
                                                    fileRepository.save(oldOpcertFile.copy(name = "${oldOpcertFile.name}-${System.currentTimeMillis()}"))
                                                }

                                                val coreCounterContent = defaultHostConnection.commandReadFile("/tmp/core.node.counter")
                                                fileRepository.save(coreCounter.copy(content = coreCounterContent))

                                                nodeRepository.save(node.copy(kesSKeyId = kesSKeyId, kesVKeyId = kesVKeyId, opcertId = opcertId))

                                                // upload kes and opcert
//                                                ***
                                            } finally {
                                                // cleanup
                                                defaultHostConnection.command("rm -f /tmp/core.node.skey /tmp/core.node.vkey /tmp/core.node.counter /tmp/core.vrf.skey /tmp/core.vrf.vkey /tmp/core.kes.skey /tmp/core.kes.vkey /tmp/core.node.opcert")
                                            }
                                        }
                                    } ?: throw IOException("Host not found!")
                                } ?: throw IOException("Invalid node id: ${request.id}")
                            }
                        } ?: throw IOException("Default host not found!")
                    } ?: throw IOException("Genesis byron not found!")
                } ?: throw IOException("Genesis shelley not found!")
            } ?: throw IOException("Default node not found!")

            webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success("rotatekes", "KES rotated successfully!"))
        } catch (e: Throwable) {
            log.error("Error Rotating KES!", e)
            webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Error(type = "rotatekes", exception = e))
            // rethrow so db transaction is rolled back
            throw RuntimeException(e)
        }
    }

    private fun isPoolOnChain(poolId: String): Boolean {
        val request = Request.Builder()
                .head()
                .cacheControl(CacheControl.FORCE_NETWORK)
                .url("https://js.adapools.org/pools/$poolId/summary.json")
                .build()
        val response = okHttpClient.newCall(request).execute()
        return response.isSuccessful
    }

    private fun uploadMetadata(metadataJson: String): String = runBlocking {

        val service = retrofit.newBuilder().baseUrl("https://1v27dl8wn5.execute-api.us-west-2.amazonaws.com").build().create(MetadataService::class.java)
        val metadataPostInfo = service.getMetadataPostInfo()
//        println(metadataPostInfo)

        val uploadService = retrofit.newBuilder().baseUrl("https://s3.us-west-2.amazonaws.com").build().create(MetadataService::class.java)

        val response = uploadService.saveMetadataFile(
                contentType = MultipartBody.Part.createFormData("content-type", null, "application/json".toRequestBody("text/plain".toMediaTypeOrNull())),
                awsAccessKeyId = MultipartBody.Part.createFormData("AWSAccessKeyId", null, metadataPostInfo.fields.awsAccessKeyId.toRequestBody("text/plain".toMediaTypeOrNull())),
                key = MultipartBody.Part.createFormData("key", null, metadataPostInfo.fields.key.toRequestBody("text/plain".toMediaTypeOrNull())),
                policy = MultipartBody.Part.createFormData("policy", null, metadataPostInfo.fields.policy.toRequestBody("text/plain".toMediaTypeOrNull())),
                signature = MultipartBody.Part.createFormData("signature", null, metadataPostInfo.fields.signature.toRequestBody("text/plain".toMediaTypeOrNull())),
                securityToken = MultipartBody.Part.createFormData("x-amz-security-token", null, metadataPostInfo.fields.securityToken.toRequestBody("text/plain".toMediaTypeOrNull())),
                file = MultipartBody.Part.createFormData("file", metadataPostInfo.fields.key, metadataJson.toRequestBody("application/json".toMediaTypeOrNull()))
        )

        if (response.isSuccessful) {
            return@runBlocking "https://cardanostakehouse.com/${metadataPostInfo.fields.key}"
        }
        throw IOException("Could not upload json file!")
    }

    private fun createManualStartupScripts(request: CreateNodeRequest, host: Host, hostConnection: HostConnection) {
        val startNodeContent = when (request.type) {
            NODE_TYPE_RELAY -> {
                """
                    |#!/bin/bash
                    |cd ${host.nodeHomePath}/${request.name}
                    |source ${host.nodeHomePath}/${request.name}/env
                    |${host.cardanoNodePath} \
                    |  +RTS -N${request.processorThreads} -RTS run \
                    |  --topology ${'$'}{TOPOLOGY} \
                    |  --database-path ${'$'}{DATABASE_PATH} \
                    |  --socket-path ${'$'}{SOCKET_PATH} \
                    |  --host-addr ${'$'}{HOST_ADDR} \
                    |  --port ${'$'}{PORT} \
                    |  --config ${'$'}{CONFIG}
                """.trimMargin()
            }
            else -> {
                """
                    |#!/bin/bash
                    |cd ${host.nodeHomePath}/${request.name}
                    |source ${host.nodeHomePath}/${request.name}/env
                    |${host.cardanoNodePath} \
                    |  +RTS -N${request.processorThreads} -RTS run \
                    |  --topology ${'$'}{TOPOLOGY} \
                    |  --database-path ${'$'}{DATABASE_PATH} \
                    |  --socket-path ${'$'}{SOCKET_PATH} \
                    |  --host-addr ${'$'}{HOST_ADDR} \
                    |  --port ${'$'}{PORT} \
                    |  --config ${'$'}{CONFIG} \
                    |  --shelley-kes-key ${'$'}{SHELLEY_KES_KEY} \
                    |  --shelley-vrf-key ${'$'}{SHELLEY_VRF_KEY} \
                    |  --shelley-operational-certificate ${'$'}{SHELLEY_OPCERT}
                """.trimMargin()
            }
        }

        hostConnection.commandWriteFile("${host.nodeHomePath}/${request.name}/startNode.sh", startNodeContent)
        hostConnection.command("chmod 555 ${host.nodeHomePath}/${request.name}/startNode.sh")

        val stopNodeContent = """
            |#!/bin/bash
            |PID=`ps -Af | grep cardano-node | grep ${request.name}\/topology | awk '{ print ${'$'}2 }'`
            |kill -s INT ${'$'}PID
            |sleep 3
            |kill -s KILL ${'$'}PID
        """.trimMargin()
        hostConnection.commandWriteFile("${host.nodeHomePath}/${request.name}/stopNode.sh", stopNodeContent)
        hostConnection.command("chmod 555 ${host.nodeHomePath}/${request.name}/stopNode.sh")
    }

    private fun createSystemdFile(request: CreateNodeRequest, host: Host, hostConnection: HostConnection) {
        val systemdContent = when (request.type) {
            NODE_TYPE_RELAY -> {
                """
                    |[Unit]
                    |Description=Cardano Haskell Node - ${request.name}
                    |After=syslog.target
                    |StartLimitIntervalSec=0
                    |
                    |[Service]
                    |Type=simple
                    |Restart=always
                    |RestartSec=5
                    |User=${host.sshUser}
                    |LimitNOFILE=131072
                    |WorkingDirectory=${host.nodeHomePath}/${request.name}
                    |EnvironmentFile=${host.nodeHomePath}/${request.name}/env
                    |ExecStart=${host.cardanoNodePath} \
                    |  +RTS -N${request.processorThreads} -RTS run \
                    |  --topology ${'$'}{TOPOLOGY} \
                    |  --database-path ${'$'}{DATABASE_PATH} \
                    |  --socket-path ${'$'}{SOCKET_PATH} \
                    |  --host-addr ${'$'}{HOST_ADDR} \
                    |  --port ${'$'}{PORT} \
                    |  --config ${'$'}{CONFIG}
                    |KillSignal=SIGINT
                    |StandardOutput=syslog
                    |StandardError=syslog
                    |SyslogIdentifier=${request.name}-node
                    |
                    |[Install]
                    |WantedBy=multi-user.target
                """.trimMargin()
            }
            else -> {
                """
                    |[Unit]
                    |Description=Cardano Haskell Node - ${request.name}
                    |After=syslog.target
                    |StartLimitIntervalSec=0
                    |
                    |[Service]
                    |Type=simple
                    |Restart=always
                    |RestartSec=5
                    |User=${host.sshUser}
                    |LimitNOFILE=131072
                    |WorkingDirectory=${host.nodeHomePath}/${request.name}
                    |EnvironmentFile=${host.nodeHomePath}/${request.name}/env
                    |ExecStart=${host.cardanoNodePath} \
                    |  +RTS -N${request.processorThreads} -RTS run \
                    |  --topology ${'$'}{TOPOLOGY} \
                    |  --database-path ${'$'}{DATABASE_PATH} \
                    |  --socket-path ${'$'}{SOCKET_PATH} \
                    |  --host-addr ${'$'}{HOST_ADDR} \
                    |  --port ${'$'}{PORT} \
                    |  --config ${'$'}{CONFIG} \
                    |  --shelley-kes-key ${'$'}{SHELLEY_KES_KEY} \
                    |  --shelley-vrf-key ${'$'}{SHELLEY_VRF_KEY} \
                    |  --shelley-operational-certificate ${'$'}{SHELLEY_OPCERT}
                    |KillSignal=SIGINT
                    |StandardOutput=syslog
                    |StandardError=syslog
                    |SyslogIdentifier=${request.name}-node
                    |
                    |[Install]
                    |WantedBy=multi-user.target
                """.trimMargin()
            }
        }

        hostConnection.sudoCommandWriteFile("/etc/systemd/system/${request.name}-node.service", systemdContent, request.sudoPassword)

        if (hostConnection.hasSystemd) {
            hostConnection.sudoCommand("systemctl daemon-reload", request.sudoPassword)
            hostConnection.sudoCommand("systemctl stop ${request.name}-node.service", request.sudoPassword)
            hostConnection.sudoCommand("systemctl start ${request.name}-node.service", request.sudoPassword)
            hostConnection.sudoCommand("systemctl enable ${request.name}-node.service", request.sudoPassword)
        }
    }

    private fun createEnvFile(hostConnection: HostConnection, nodeType: String, nodeName: String, nodeFolder: String, listen: String, port: Int): String {
        when (nodeType) {
            NODE_TYPE_RELAY -> {
                hostConnection.commandWriteFile("${nodeFolder}/env",
                        """
                |TOPOLOGY=${nodeFolder}/topology.json
                |DATABASE_PATH=${nodeFolder}/db
                |SOCKET_PATH=${nodeFolder}/db/socket
                |HOST_ADDR=${listen}
                |PORT=${port}
                |CONFIG=${nodeFolder}/config.json
                """.trimMargin()
                )
            }
            NODE_TYPE_CORE -> {
                hostConnection.commandWriteFile("${nodeFolder}/env",
                        """
                |TOPOLOGY=${nodeFolder}/topology.json
                |DATABASE_PATH=${nodeFolder}/db
                |SOCKET_PATH=${nodeFolder}/db/socket
                |HOST_ADDR=${listen}
                |PORT=${port}
                |CONFIG=${nodeFolder}/config.json
                |SHELLEY_KES_KEY=${nodeFolder}/${nodeName}.kes.skey
                |SHELLEY_VRF_KEY=${nodeFolder}/${nodeName}.vrf.skey
                |SHELLEY_OPCERT=${nodeFolder}/${nodeName}.node.opcert
                """.trimMargin()
                )
            }
        }
        return hostConnection.command("chmod 400 ${nodeFolder}/env")
    }

    private fun createKesVrfOpcert(nodeName: String, kesSKeyContent: String, vrfSKeyContent: String, opcertContent: String, hostConnection: HostConnection, nodeFolder: String) {
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


    private fun createConfigFile(hostId: Long, genesisByronFileName: String, requestEkgPort: Int, requestPromPort: Int, hostConnection: HostConnection, nodeFolder: String): Triple<Long, Int, Int> {
        var ekgPort = requestEkgPort

        if (ekgPort == -1) {
            ekgPort = 12788 + (2 * nodeRepository.countForHost(hostId))
            while (isPortUsed(hostConnection, ekgPort)) {
                ekgPort++
            }
        }
        var promPort = requestPromPort
        if (promPort == -1) {
            promPort = ekgPort + 1
            while (isPortUsed(hostConnection, promPort)) {
                promPort++
            }
        }
        val configFile = fileRepository.findByName(genesisByronFileName.substringBeforeLast("-byron") + "-config.json")
        val configFileContent = configFile?.content
                ?.replace(Regex(""""ByronGenesisFile": .*,"""), """"ByronGenesisFile": "byron-genesis.json",""")
                ?.replace(Regex(""""ShelleyGenesisFile": .*,"""), """"ShelleyGenesisFile": "shelley-genesis.json",""")
                ?.replace(Regex(""""GenesisFile": .*,"""), """"GenesisFile": "shelley-genesis.json",""")
                ?.replace(Regex(""""TraceBlockFetchDecisions":.*(true|false),"""), """"TraceBlockFetchDecisions": true,""")
                ?.replace(Regex(""".*"defaultScribes.*\[\n.*\[\n.*StdoutSK.*\n.*stdout.*\n.*\]\n.*\],"""),
                        """
                                                |  "defaultScribes": [
                                                |    [
                                                |      "FileSK",
                                                |      "logs/node.json"
                                                |    ]
                                                |  ],
                                                """.trimMargin())
                ?.replace(Regex(""""rpLogLimitBytes": .*,"""), """"rpLogLimitBytes": 20000000,""")
                ?.replace(Regex(""""scFormat.*,"""), """"scFormat": "ScJson",""")
                ?.replace(Regex(""""scKind.*,"""), """"scKind": "FileSK",""")
                ?.replace(Regex(""""scName.*,"""), """"scName": "logs/node.json",""")
                ?.replace("12788", "$ekgPort")
                ?.replace("12798", "$promPort")
        configFileContent?.let {
            log.debug("Creating ${nodeFolder}/config.json from db file ${configFile.name}")
            hostConnection.commandWriteFile("${nodeFolder}/config.json", it)
        } ?: throw IOException("Config file not found in db!")

        return Triple(configFile.id!!, ekgPort, promPort)
    }

    private fun isPortUsed(hostConnection: HostConnection, port: Int): Boolean {
        return hostConnection.command("ss -tulw").trim().contains(":$port")
    }

    private fun createTopologyFile(byronGenesisFileName: String, hostConnection: HostConnection, nodeFolder: String) {
        val topologyFile = fileRepository.findByName(byronGenesisFileName.substringBeforeLast("-byron") + "-topology.json")
        topologyFile?.let {
            if (!hostConnection.commandFileExists("${nodeFolder}/topology.json")) {
                log.debug("Creating ${nodeFolder}/topology.json from db file ${topologyFile.name}")
                hostConnection.commandWriteFile("${nodeFolder}/topology.json", topologyFile.content)
            } else {
                log.debug("SKIP Creating ${nodeFolder}/topology.json from db file ${topologyFile.name}")
            }
        } ?: throw IOException("Topology file not found in db!")
    }

    private fun createGenesisFile(prefix: String, genesisFileId: Long, hostConnection: HostConnection, nodeFolder: String): com.swiftmako.jormanager.entities.File {
        val genesisFile = fileRepository.findByIdOrNull(genesisFileId)
        genesisFile?.let {
            log.debug("Creating ${nodeFolder}/$prefix-genesis.json from db file ${genesisFile.name}")
            hostConnection.commandWriteFile("${nodeFolder}/$prefix-genesis.json", genesisFile.content)
            return genesisFile
        } ?: throw IOException("Genesis file not found in db!")
    }

    private fun createNodeFolders(hostConnection: HostConnection, nodeFolder: String) {
        hostConnection.command("mkdir -p ${nodeFolder}/db")
        hostConnection.command("mkdir -p ${nodeFolder}/logs")
    }

    companion object {
        const val NODE_TYPE_RELAY = "relay"
        const val NODE_TYPE_CORE = "core"
        private val IP4_ADDRESS = Regex("(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)")
        private const val BYRON_TO_SHELLEY_EPOCHS = 208L
    }
}