package com.swiftmako.jormanager.controllers

import com.google.iot.cbor.CborByteString
import com.google.iot.cbor.CborReader
import com.muquit.libsodiumjna.SodiumLibrary
import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.controllers.utils.BlockUtils
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.Block
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.Genesis
import com.swiftmako.jormanager.model.GenesisByron
import com.swiftmako.jormanager.model.LeaderLogsRequest
import com.swiftmako.jormanager.model.ProtocolParameters
import com.swiftmako.jormanager.model.QueryTip
import com.swiftmako.jormanager.model.key.Key
import com.swiftmako.jormanager.model.ledger.Ledger
import com.swiftmako.jormanager.repositories.BlockRepository
import com.swiftmako.jormanager.repositories.ChainRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import okio.buffer
import okio.source
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.MediaType
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import kotlin.math.ceil

@Controller
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
        private val shelleyGenesisAdapter: JsonAdapter<Genesis>,
        private val protocolParamsAdapter: JsonAdapter<ProtocolParameters>,
        private val queryTipAdapter: JsonAdapter<QueryTip>,
        private val ledgerAdapter: JsonAdapter<Ledger>,
        private val keyAdapter: JsonAdapter<Key>,

        private val chainRepository: ChainRepository,
) {

    private val log = LoggerFactory.getLogger(BlockController::class.java)

    @GetMapping("/test", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun testEpochNonce(): Any {
        val byron = byronGenesisAdapter.fromJson(File("/home/westbam/haskell/test/byron-genesis.json").source().buffer())!!
        val shelley = shelleyGenesisAdapter.fromJson(File("/home/westbam/haskell/test/shelley-genesis.json").source().buffer())!!

        val firstSlotOfEightyNine = blockUtils.getFirstSlotOfEpoch(byron, shelley, 8356306L) // pick any random slot during the 89 epoch
        val firstSlotOfEightyEight = firstSlotOfEightyNine - (shelley.epochLength * shelley.slotLength)
        val stabilityWindow = ceil(3 * byron.protocolConsts.k / shelley.activeSlotsCoeff).toLong()

        // We need to figure out how to end up with
        val expectedNc = "f531f1f76f656e1537f10c877aabbb3c4fdaa73b0522d142a2841571e5a57eb5"
        val expectedNh = "d48a017f3a6f09b21ba8601595e53858a8b91dcfefde340075b0ef85bed41313"
        val expectedEightyNineEpochNonce = "2a912c7bd52e6703c1585379cdde6e44b1908e474d278223232fd18e41a903e9"

        val stabilityWindowStart = firstSlotOfEightyNine - stabilityWindow
        val nc = chainRepository.findFirstBeforeSlot(stabilityWindowStart).firstOrNull()?.etaV
        val nh = chainRepository.findFirstBeforeSlot(firstSlotOfEightyEight).firstOrNull()?.prevHash

        val eightyNineEpochNonce = SodiumLibrary.cryptoBlake2bHash((nc + nh).hexToByteArray(), null).toHexString()

        return object {
            val firstSlotOfEightyEight = firstSlotOfEightyEight
            val firstSlotOfEightyNine = firstSlotOfEightyNine
            val stabilityWindow = stabilityWindow
            val expectedNc = expectedNc
            val calculatedNc = nc
            val expectedNh = expectedNh
            val calculatedNh = nh
            val expectedEightyNineEpochNonce = expectedEightyNineEpochNonce
            val eightyNineEpochNonce = eightyNineEpochNonce
        }
    }

    @MessageMapping("/version")
    @SendTo("/topic/messages")
    fun getVersion(): SocketResponse<String> {
        return SocketResponse.Success(type = "version", data = "JorManager ${buildProperties.version.split('-')[0]}")
    }

    @MessageMapping("/blocks")
    @SendTo("/topic/messages")
    fun getBlocks(): SocketResponse<List<Block>> {
        val blocks = blockRepository.findAll(Sort.by(Sort.Direction.ASC, "slot"))
        blocks.map { block ->
            if (block.epoch < 0L) {
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
            } else {
                block
            }
        }
        return SocketResponse.Success(type = "blocks", data = blocks)
    }

    @MessageMapping("/leaderlogs")
    @Transactional
    fun calculateLeaderLogs(request: LeaderLogsRequest) {
        try {
            if (!walletUtils.isValidSpendingPassword(request.spendingPassword)) {
                throw IllegalArgumentException("Invalid spending password!")
            }

            nodeRepository.findDefault()?.let { defaultNode ->
                val coreNodes = nodeRepository.findAll().filter { it.type == "core" }
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
                            webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success("leaderlogs", "Leader Logs Started... please be patient."))

                            val defaultHostConnection = HostConnection(defaultHost, defaultNode)
                            try {
                                val protocolParamsJson = defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley query protocol-parameters --cardano-mode $magicString").trim()
                                defaultHostConnection.commandWriteFile("/tmp/protocol-parameters.json", protocolParamsJson)
                                val protocolParameters = protocolParamsAdapter.fromJson(protocolParamsJson)
                                        ?: throw IOException("Invalid protocol params!")
                                val tipJson = defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley query tip $magicString").trim()
                                val tipSlotNumber = queryTipAdapter.fromJson(tipJson)?.slotNo
                                        ?: throw IOException("Unable to query tip!")
                                val ledgerStateJson = defaultHostConnection.command("${defaultHost.cardanoCliPath} shelley query ledger-state --cardano-mode $magicString").trim()
                                val ledger = ledgerAdapter.fromJson(ledgerStateJson)
                                        ?: throw IOException("Error dumping ledger state!")

                                val firstSlotOfEpoch = blockUtils.getFirstSlotOfEpoch(genesisByron, genesisShelley, tipSlotNumber)
                                val slotsPerEpoch = (genesisShelley.epochLength / genesisShelley.slotLength).toInt()
                                val poolIdToSigma = mutableMapOf<String, BigDecimal>()
                                val poolIdToVrfSkey = mutableMapOf<String, ByteArray>()
                                val poolIdToHostname = mutableMapOf<String, String>()
                                val leadershipCount = mutableMapOf<String, Int>()
                                var lastLoggedTime = System.currentTimeMillis()
                                repeat(slotsPerEpoch) { index ->
                                    val slot = firstSlotOfEpoch + index
                                    if (blockUtils.isOverlaySlot(firstSlotOfEpoch, slot, protocolParameters.decentralisationParam.toBigDecimal())) {
                                        // Nobody is allowed to make a block in this slot except maybe BFT nodes.
                                        return@repeat
                                    }
                                    coreNodes.forEach { coreNode ->
                                        val sigma = poolIdToSigma[coreNode.poolId!!]
                                                ?: blockUtils.getSigma(coreNode.poolId, ledger).also {
                                                    poolIdToSigma[coreNode.poolId] = it
                                                }
                                        val poolVrfSkey = poolIdToVrfSkey[coreNode.poolId] ?: run {
                                            val vrfSkeyFile = fileRepository.findByIdOrNull(coreNode.vrfSKeyId)
                                                    ?: throw IOException("No VRF Skey for ${coreNode.name}")
                                            val vrfJsonString = walletUtils.getSKeyContent(vrfSkeyFile, request.spendingPassword)
                                            val vrfSkey = keyAdapter.fromJson(vrfJsonString)
                                                    ?: throw IOException("Unable to parse VRF Skey!")
                                            val reader = CborReader.createFromByteArray(vrfSkey.cborHex.hexToByteArray())
                                            (reader.readDataItem() as CborByteString).byteArrayValue().also {
                                                poolIdToVrfSkey[coreNode.poolId] = it
                                            }
                                        }
                                        val isSlotLeader = blockUtils.isSlotLeader(
                                                slot = slot,
                                                f = genesisShelley.activeSlotsCoeff,
                                                sigma = sigma,
                                                eta0 = request.epochNonce.hexToByteArray(),
                                                poolVrfSkey = poolVrfSkey
                                        )

                                        if (isSlotLeader) {
                                            log.info("${coreNode.name}: Selected for slot $slot")
                                            val key = "${coreNode.name}|${coreNode.poolId}"
                                            val count = leadershipCount[key] ?: 0
                                            leadershipCount[key] = count + 1

                                            val existingBlock = blockRepository.findByPoolAndSlot(coreNode.name, slot)
                                            if (existingBlock == null) {
                                                val (epoch, slotInEpoch) = blockUtils.getEpochAndSlot(genesisByron, genesisShelley, slot)

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
                                            }
                                        }
                                    }
                                    val now = System.currentTimeMillis()
                                    if (now - lastLoggedTime > 10_000) {
                                        // notify GUI every 10 seconds of progress
                                        webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success("leaderlogs", "Leader Logs ${String.format("%1.2f", (index.toFloat() / slotsPerEpoch.toFloat()) * 100)}%"))
                                        lastLoggedTime = now
                                    }
                                }

                                log.info("Total Slots this epoch: $leadershipCount")
                                val blocks = blockRepository.findAll(Sort.by(Sort.Direction.ASC, "slot"))
                                webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success("blocks", data = blocks))
                            } finally {
                                // Cleanup
                                defaultHostConnection.command("rm -f /tmp/protocol-parameters.json")
                            }
                        } ?: throw IOException("Host not found for default node!")
                    } ?: throw IOException("Genesis Byron file for default node not found!")
                } ?: throw IOException("Genesis file for default node not found!")
            } ?: throw IOException("No default node!")

            webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Success("leaderlogs", "Leader Logs calculation complete!"))
        } catch (e: Throwable) {
            log.error("Error Fetching leader logs!", e)
            webSocketTemplate.convertAndSend("/topic/messages", SocketResponse.Error(type = "leaderlogs", exception = e))
            // rethrow so db transaction is rolled back
            throw RuntimeException(e)
        }
    }

}