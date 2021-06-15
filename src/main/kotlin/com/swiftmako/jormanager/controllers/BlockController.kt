package com.swiftmako.jormanager.controllers

import com.google.iot.cbor.CborByteString
import com.google.iot.cbor.CborReader
import com.muquit.libsodiumjna.SodiumLibrary
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.controllers.utils.BlockUtils
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.Block
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.*
import com.swiftmako.jormanager.model.key.Key
import com.swiftmako.jormanager.repositories.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory.SCOPE_SINGLETON
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Scope
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.coroutines.CoroutineContext
import kotlin.math.ceil

@Controller
@Scope(SCOPE_SINGLETON)
class BlockController @Autowired constructor(
        private val buildProperties: BuildProperties,
        private val blockRepository: BlockRepository,
        private val nodeRepository: NodeRepository,
        private val hostRepository: HostRepository,
        private val fileRepository: FileRepository,
        private val blockUtils: BlockUtils,
        private val walletUtils: WalletUtils,
        private val webSocketTemplate: SimpMessagingTemplate,
        private val byronGenesisAdapter: JsonAdapter<GenesisByron>,
        private val shelleyGenesisAdapter: JsonAdapter<GenesisShelley>,
        private val protocolParamsAdapter: JsonAdapter<ProtocolParameters>,
        private val queryTipAdapter: JsonAdapter<QueryTip>,
        private val keyAdapter: JsonAdapter<Key>,
        private val stakeSnapshotAdapter: JsonAdapter<StakeSnapshot>,
        private val chainRepository: ChainRepository,
        private val moshi: Moshi,
        @Value("\${jormanager.mp:false}") private val mp: Boolean,
        @Value("\${jormanager.blocks.pastEpochs:2}") private val pastEpochsToShow: Long,
) : CoroutineScope {

    private val log = LoggerFactory.getLogger(BlockController::class.java)

    override val coroutineContext: CoroutineContext = Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            log.error("Uncaught coroutine exception!", throwable)
        }
    }

    @MessageMapping("/version")
    fun getVersion() {
        try {
            val defaultNode = nodeRepository.findDefault() ?: throw IOException("No default node!")

            val genesisFile = fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)
                    ?: throw IOException("Genesis file for default node not found!")
            val genesisShelley = shelleyGenesisAdapter.fromJson(genesisFile.content)!!
            val magicString = if (genesisShelley.networkId.equals("testnet", ignoreCase = true)) {
                "--testnet-magic ${genesisShelley.networkMagic}"
            } else {
                "--mainnet"
            }

            val defaultHost = hostRepository.findByIdOrNull(defaultNode.hostId)
                    ?: throw IOException("Host not found for default node!")
            val defaultHostConnection = HostConnection(defaultHost, defaultNode)
            try {
                val protocolParamsJson = defaultHostConnection.command("${defaultHost.cardanoCliPath} query protocol-parameters $magicString").trim()
                defaultHostConnection.commandWriteFile("/tmp/protocol-parameters.json", protocolParamsJson)
                val protocolParameters = protocolParamsAdapter.fromJson(protocolParamsJson)
                        ?: throw IOException("Invalid protocol params!")

                webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success(type = "version", data = JorManagerVersion(version = "JorManager ${buildProperties.version.split('-')[0]}", mp = mp, minUTxOValue = protocolParameters.minUTxOValue)))
            } finally {
                // Cleanup
                defaultHostConnection.command("rm -f /tmp/protocol-parameters.json")
            }
        } catch (e: Throwable) {
            log.error("Error Getting Version!", e)
            webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Error(type = "version", exception = e))
            // rethrow so db transaction is rolled back
            throw RuntimeException(e)
        }
    }

    @MessageMapping("/blocks")
    @SendTo("/topic/messages")
    fun getBlocks(): SocketResponse<List<Block>> {
        blockRepository.findAllEpochless().map { block ->
            // block needs to be updated with epoch/slot in epoch
            nodeRepository.findDefault()?.let { node ->
                fileRepository.findByIdOrNull(node.genesisByronFileId)?.let { byronFile ->
                    byronGenesisAdapter.fromJson(byronFile.content)?.let { byron ->
                        fileRepository.findByIdOrNull(node.genesisShelleyFileId)?.let { shelleyFile ->
                            shelleyGenesisAdapter.fromJson(shelleyFile.content)?.let { shelley ->
                                val (epoch, slotInEpoch) = blockUtils.getEpochAndSlot(byron, shelley, block.slot)
                                if (epoch > 0L && slotInEpoch > 0L) {
                                    blockRepository.save(block.copy(epoch = epoch, slotInEpoch = slotInEpoch))
                                } else {
                                    log.warn("blockutils returned bad epoch/slotInEpoch")
                                    block
                                }
                            } ?: run {
                                log.warn("could not parse shelley genesis json!")
                                block
                            }
                        } ?: run {
                            log.warn("shelley genesis file not found!")
                            block
                        }
                    } ?: run {
                        log.warn("could not parse byron genesis json!")
                        block
                    }
                } ?: run {
                    log.warn("byron genesis file not found!")
                    block
                }
            } ?: run {
                log.warn("No default node found!")
                block
            }
        }

        val blocks = blockRepository.findLatestBlocks(pastEpochsToShow)
        return SocketResponse.Success(type = "blocks", data = blocks)
    }

    @OptIn(FlowPreview::class)
    @MessageMapping("/leaderlogs")
    @Transactional
    fun calculateLeaderLogs(request: LeaderLogsRequest) {
        launch {
            try {
                if (!walletUtils.isValidSpendingPassword(request.spendingPassword)) {
                    throw IllegalArgumentException("Invalid spending password!")
                }

                nodeRepository.findDefault()?.let { defaultNode ->
                    val coreNodes = nodeRepository.findAll().filter { it.type != "relay" && !it.isDeleted }
                    fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)?.let { genesisShelleyFile ->
                        val genesisShelley = shelleyGenesisAdapter.fromJson(genesisShelleyFile.content)!!
                        val magicString = if (genesisShelley.networkId.equals("testnet", ignoreCase = true)) {
                            "--testnet-magic ${genesisShelley.networkMagic}"
                        } else {
                            "--mainnet"
                        }
                        fileRepository.findByIdOrNull((defaultNode.genesisByronFileId))?.let { genesisByronFile ->
                            val genesisByron = byronGenesisAdapter.fromJson(genesisByronFile.content)!!

                            hostRepository.findByIdOrNull(defaultNode.hostId)?.let { defaultHost ->
                                webSocketTemplate.convertAndSend(
                                        "/topic/messages",
                                        SocketResponse.Success("leaderlogs", "Leader Logs Started... please be patient.")
                                )

                                val defaultHostConnection = HostConnection(defaultHost, defaultNode)
                                val tipJson =
                                        defaultHostConnection.command("${defaultHost.cardanoCliPath} query tip $magicString")
                                                .trim()
                                val tipSlotNumber = queryTipAdapter.fromJson(tipJson)?.slot
                                        ?: throw IOException("Unable to query tip!")
//                                val ledgerStateFile = "/tmp/ledger-state-${genesisShelley.networkMagic}.json"
//                                defaultHostConnection.bashCommand("${defaultHost.cardanoCliPath} query ledger-state $magicString | jq -c > $ledgerStateFile", timeoutSecs = 300L)
//                                val ledger = defaultHostConnection.commandGetFileBufferedSource(ledgerStateFile).use { ledgerStateJsonSource ->
//                                    val poolIds = coreNodes.mapNotNull { it.poolId }.toSet()
//                                    val ledgerAdapter = LeaderLogLedgerJsonAdapter(moshi, poolIds)
//                                    ledgerAdapter.fromJson(ledgerStateJsonSource)
//                                            ?: throw IOException("Error dumping ledger state!")
//                                }
//                                defaultHostConnection.command("rm -f $ledgerStateFile")

                                val poolIdToSigma = mutableMapOf<String, BigDecimal>()
                                val futurePoolIdToSigma = mutableMapOf<String, BigDecimal>()
                                val stakeSnapshotAsyncs = mutableListOf<Deferred<Unit?>>()
                                val mapMutex = Mutex()
                                coreNodes.asFlow().flowOn(Dispatchers.IO).collect { node ->
                                    val d = async {
                                        node.poolId?.let { poolId ->
                                            val stakeSnapshotJson = defaultHostConnection.command("${defaultHost.cardanoCliPath} query stake-snapshot --stake-pool-id $poolId $magicString").trim()
                                            val stakeSnapshot = stakeSnapshotAdapter.fromJson(stakeSnapshotJson)
                                                    ?: throw IOException("Unable to parse stakeSnapshot json for $poolId!")
                                            log.debug("pool: ${poolId.substring(0, 6)} - $stakeSnapshot")
                                            mapMutex.withLock {
                                                poolIdToSigma[poolId] = BigDecimal(stakeSnapshot.poolStakeSet).divide(BigDecimal(stakeSnapshot.activeStakeSet), 34, RoundingMode.HALF_UP)
                                                futurePoolIdToSigma[poolId] = BigDecimal(stakeSnapshot.poolStakeMark).divide(BigDecimal(stakeSnapshot.activeStakeMark), 34, RoundingMode.HALF_UP)
                                            }
                                        }
                                    }
                                    stakeSnapshotAsyncs.add(d)
                                }
                                stakeSnapshotAsyncs.awaitAll()

                                // if we're doing the stake-snapshot command, assume future d stays the same and no entropy
                                val protocolParamsJson = defaultHostConnection.command("${defaultHost.cardanoCliPath} query protocol-parameters $magicString").trim()
                                val protocolParameters = protocolParamsAdapter.fromJson(protocolParamsJson)
                                        ?: throw IOException("Invalid protocol params!")

                                val ledger = LeaderLogLedger(
                                        decentralizationParameter = protocolParameters.decentralisationParam,
                                        futureDecentralizationParameter = protocolParameters.decentralisationParam,
                                        poolIdToSigma = poolIdToSigma,
                                        futurePoolIdToSigma = futurePoolIdToSigma,
                                        extraPraosEntropy = null,
                                        futureExtraPraosEntropy = null,
                                )

                                // Pretend our tip came from the next epoch if user wants to grab future blocks before current epoch is done.
                                // This only works as long as the decentralizationParam isn't going to change in the next epoch.
                                val slotsPerEpoch = genesisShelley.epochLength.toInt()
                                val additionalSlots = if (request.requestType == "futureEpoch") slotsPerEpoch else 0
                                val firstSlotOfEpoch = blockUtils.getFirstSlotOfEpoch(
                                        genesisByron,
                                        genesisShelley,
                                        tipSlotNumber + additionalSlots
                                )
                                val firstSlotOfPreviousEpoch = firstSlotOfEpoch - slotsPerEpoch
                                val stabilityWindow =
                                        ceil(3 * genesisByron.protocolConsts.k / genesisShelley.activeSlotsCoeff).toLong()
                                log.debug("stabilityWindow: $stabilityWindow")
                                val decentralizationParam =
                                        if (request.requestType == "futureEpoch") ledger.futureDecentralizationParameter.toBigDecimal() else ledger.decentralizationParameter.toBigDecimal()

                                val stabilityWindowStart = firstSlotOfEpoch - stabilityWindow
                                val nc = chainRepository.findFirstBeforeSlot(stabilityWindowStart).firstOrNull()?.etaV
                                        ?: throw IOException("Not enough blocks sync'd to calculate! Try again later after slot $stabilityWindowStart is sync'd.")
                                log.debug("nc: $nc")
                                val nh =
                                        chainRepository.findFirstBeforeSlot(firstSlotOfPreviousEpoch).firstOrNull()?.prevHash
                                                ?: throw IOException("Not enough blocks sync'd to calculate! Try again later.")
                                log.debug("nh: $nh")

                                var epochNonce = SodiumLibrary.cryptoBlake2bHash((nc + nh).hexToByteArray(), null)
                                if (request.requestType == "futureEpoch") {
                                    ledger.futureExtraPraosEntropy?.let {
                                        epochNonce = SodiumLibrary.cryptoBlake2bHash((epochNonce.toHexString() + it).hexToByteArray(), null)
                                    }
                                } else {
                                    ledger.extraPraosEntropy?.let {
                                        epochNonce = SodiumLibrary.cryptoBlake2bHash((epochNonce.toHexString() + it).hexToByteArray(), null)
                                    }
                                }
                                log.info("Leader Logs Epoch Nonce: ${epochNonce.toHexString()}")
                                log.info("Ledger State: $ledger")

                                val poolIdToVrfSkey = mutableMapOf<String, ByteArray>()
                                val poolIdToHostname = mutableMapOf<String, String>()
                                val leadershipCount = mutableMapOf<String, Int>()
                                var lastLoggedTime = System.currentTimeMillis()

                                val mutex = Mutex()
                                val logMutex = Mutex()
                                val deferreds = mutableListOf<Deferred<Unit>>()
                                flow {
                                    repeat(slotsPerEpoch) { index ->
                                        emit(firstSlotOfEpoch + index)
                                    }
                                }.filterNot { slot ->
                                    blockUtils.isOverlaySlot(firstSlotOfEpoch, slot, decentralizationParam)
                                }.buffer().map { slot ->
                                    flow {
                                        coreNodes.forEach { coreNode ->
                                            emit(Pair(slot, coreNode))
                                        }
                                    }
                                }.flattenMerge().collect { (slot, coreNode) ->
                                    val d = async {
                                        requireNotNull(coreNode.poolId)
                                        val sigma = if (request.requestType == "futureEpoch") {
                                            ledger.futurePoolIdToSigma[coreNode.poolId]
                                                    ?: throw IOException("No sigma found for poolId ${coreNode.poolId}")
                                        } else {
                                            ledger.poolIdToSigma[coreNode.poolId]
                                                    ?: throw IOException("No sigma found for poolId ${coreNode.poolId}")
                                        }

                                        val poolVrfSkey = mutex.withLock {
                                            poolIdToVrfSkey[coreNode.poolId] ?: run {
                                                val vrfSkeyFile = fileRepository.findByIdOrNull(coreNode.vrfSKeyId)
                                                        ?: throw IOException("No VRF Skey for ${coreNode.name}")
                                                val vrfJsonString =
                                                        walletUtils.getSKeyContent(vrfSkeyFile, request.spendingPassword)
                                                val vrfSkey = keyAdapter.fromJson(vrfJsonString)
                                                        ?: throw IOException("Unable to parse VRF Skey!")
                                                val reader = CborReader.createFromByteArray(vrfSkey.cborHex.hexToByteArray())
                                                (reader.readDataItem() as CborByteString).byteArrayValue().also {
                                                    poolIdToVrfSkey[coreNode.poolId] = it
                                                }
                                            }
                                        }
                                        val isSlotLeader = blockUtils.isSlotLeader(
                                                slot = slot,
                                                f = genesisShelley.activeSlotsCoeff,
                                                sigma = sigma,
                                                eta0 = epochNonce,
                                                poolVrfSkey = poolVrfSkey
                                        )

                                        if (isSlotLeader) {
                                            mutex.withLock {
                                                log.info("${coreNode.name}: Selected for slot $slot")
                                                val key = "${coreNode.name}|${coreNode.poolId}"
                                                val count = leadershipCount[key] ?: 0
                                                leadershipCount[key] = count + 1

                                                val existingBlock = blockRepository.findByPoolAndSlot(coreNode.name, slot)
                                                        ?: blockRepository.findBySlot(slot).firstOrNull()
                                                if (existingBlock == null) {
                                                    val (epoch, slotInEpoch) = blockUtils.getEpochAndSlot(
                                                            genesisByron,
                                                            genesisShelley,
                                                            slot
                                                    )

                                                    blockRepository.save(
                                                            Block(
                                                                    at = blockUtils.slotToTimestamp(genesisByron, genesisShelley, slot),
                                                                    pool = coreNode.name,
                                                                    host = poolIdToHostname[coreNode.poolId]
                                                                            ?: hostRepository.findByIdOrNull(coreNode.hostId)!!.hostname.also {
                                                                                poolIdToHostname[coreNode.poolId] = it
                                                                            },
                                                                    slot = slot,
                                                                    epoch = epoch,
                                                                    slotInEpoch = slotInEpoch,
                                                                    hash = "",
                                                                    status = "pending"
                                                            )
                                                    )
                                                    log.debug("Saved elected block for slot $slot")
                                                }
                                            }
                                        }

                                        if (logMutex.tryLock()) {
                                            try {
                                                val now = System.currentTimeMillis()
                                                if (now - lastLoggedTime > 10_000) {
                                                    // notify GUI every 10 seconds of progress
                                                    webSocketTemplate.convertAndSend(
                                                            "/topic/messages",
                                                            SocketResponse.Success(
                                                                    "leaderlogs",
                                                                    "Leader Logs ${
                                                                        String.format(
                                                                                "%1.2f",
                                                                                ((slot - firstSlotOfEpoch).toFloat() / slotsPerEpoch.toFloat()) * 100
                                                                        )
                                                                    }%"
                                                            )
                                                    )
                                                    lastLoggedTime = now
                                                }
                                            } finally {
                                                logMutex.unlock()
                                            }
                                        }
                                    }
                                    deferreds.add(d)
                                }

                                deferreds.awaitAll()

                                log.info("Total Slots this epoch: $leadershipCount")
                                val blocks = blockRepository.findLatestBlocks(pastEpochsToShow)
                                webSocketTemplate.convertAndSend(
                                        "/topic/messages",
                                        SocketResponse.Success("blocks", data = blocks)
                                )
                            } ?: throw IOException("Host not found for default node!")
                        } ?: throw IOException("Genesis Byron file for default node not found!")
                    } ?: throw IOException("Genesis file for default node not found!")
                } ?: throw IOException("No default node!")

                webSocketTemplate.convertAndSend(
                        "/topic/messages",
                        SocketResponse.Success("leaderlogs", "Leader Logs calculation complete!")
                )
            } catch (e: Throwable) {
                log.error("Error Fetching leader logs!", e)
                webSocketTemplate.convertAndSend(
                        "/topic/messages",
                        SocketResponse.Error(type = "leaderlogs", exception = e)
                )
                // rethrow so db transaction is rolled back
                throw RuntimeException(e)
            }
        }
    }

}