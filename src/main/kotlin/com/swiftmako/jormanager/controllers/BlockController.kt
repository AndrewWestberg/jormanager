package com.swiftmako.jormanager.controllers

import com.google.iot.cbor.CborByteString
import com.google.iot.cbor.CborReader
import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.controllers.utils.BlockUtils
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.controllers.utils.WalletUtils
import com.swiftmako.jormanager.entities.Block
import com.swiftmako.jormanager.entities.SocketResponse
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.model.Genesis
import com.swiftmako.jormanager.model.GenesisByron
import com.swiftmako.jormanager.model.LeaderLogsRequest
import com.swiftmako.jormanager.model.ProtocolParameters
import com.swiftmako.jormanager.model.QueryTip
import com.swiftmako.jormanager.model.key.Key
import com.swiftmako.jormanager.model.ledger.Ledger
import com.swiftmako.jormanager.repositories.BlockRepository
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.HostRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.SendTo
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.io.IOException
import java.math.BigDecimal

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
        moshi: Moshi,
) {

    private val log = LoggerFactory.getLogger(BlockController::class.java)

    private val shelleyGenesisAdapter by lazy { moshi.adapter(Genesis::class.java) }
    private val byronGenesisAdapter by lazy { moshi.adapter(GenesisByron::class.java) }
    private val protocolParamsAdapter by lazy { moshi.adapter(ProtocolParameters::class.java) }
    private val queryTipAdapter by lazy { moshi.adapter(QueryTip::class.java) }
    private val ledgerAdapter by lazy { moshi.adapter(Ledger::class.java) }
    private val keyAdapter by lazy { moshi.adapter(Key::class.java) }

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
                                var leadershipCount = mutableMapOf<String, Int>()
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
                                            log.error("${coreNode.name}: Selected for slot $slot")
                                            val key = "${coreNode.name}|${coreNode.poolId}"
                                            val count = leadershipCount[key] ?: 0
                                            leadershipCount[key] = count + 1
                                        }
                                    }
                                }

                                log.error("Total Slots this epoch: $leadershipCount")
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