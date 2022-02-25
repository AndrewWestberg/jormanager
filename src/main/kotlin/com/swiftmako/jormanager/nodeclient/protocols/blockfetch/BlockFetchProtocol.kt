package com.swiftmako.jormanager.nodeclient.protocols.blockfetch

import com.firehose.controllers.nodeclient.protocol.Agency
import com.google.iot.cbor.*
import com.swiftmako.jormanager.entities.BlockFetch
import com.swiftmako.jormanager.ktx.*
import com.swiftmako.jormanager.model.CreatedUtxo
import com.swiftmako.jormanager.model.NativeAsset
import com.swiftmako.jormanager.model.NativeAssetMetadata
import com.swiftmako.jormanager.model.SpentUtxo
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import com.swiftmako.jormanager.nodeclient.protocols.chainsync.ChainSyncProtocol
import com.swiftmako.jormanager.nodeclient.protocols.mux.muxByteBufferPool
import com.swiftmako.jormanager.repositories.BlockFetchRepository
import com.swiftmako.jormanager.repositories.ChainRepository
import com.swiftmako.jormanager.repositories.LedgerDao
import com.swiftmako.jormanager.repositories.LedgerRepository
import com.swiftmako.jormanager.utils.Bech32
import com.swiftmako.jormanager.utils.Blake2b
import com.swiftmako.jormanager.utils.CardanoUtils
import com.swiftmako.jormanager.utils.Constants.BYRON_ADDRESS_PREFIX
import com.swiftmako.jormanager.utils.Constants.ENTERPRISE_ADDRESS_PREFIX_MAINNET
import com.swiftmako.jormanager.utils.Constants.ENTERPRISE_ADDRESS_PREFIX_TESTNET
import com.swiftmako.jormanager.utils.Constants.SCRIPT2_ADDRESS_PREFIX_MAINNET
import com.swiftmako.jormanager.utils.Constants.SCRIPT2_ADDRESS_PREFIX_TESTNET
import com.swiftmako.jormanager.utils.Constants.SCRIPT_ADDRESS_PREFIX_MAINNET
import com.swiftmako.jormanager.utils.Constants.SCRIPT_ADDRESS_PREFIX_TESTNET
import com.swiftmako.jormanager.utils.Constants.STAKED_SCRIPT_ADDRESS_PREFIX_MAINNET
import com.swiftmako.jormanager.utils.Constants.STAKED_SCRIPT_ADDRESS_PREFIX_TESTNET
import com.swiftmako.jormanager.utils.Constants.STAKE_ADDRESS_PREFIX_MAINNET
import com.swiftmako.jormanager.utils.Constants.STAKE_ADDRESS_PREFIX_TESTNET
import com.swiftmako.jormanager.utils.Constants.STAKE_PAYMENT_ADDRESS_PREFIX_MAINNET
import com.swiftmako.jormanager.utils.Constants.STAKE_PAYMENT_ADDRESS_PREFIX_TESTNET
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.Modifying
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import javax.transaction.Transactional
import kotlin.math.min
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis

class BlockFetchProtocol(
    private val cardanoUtils: CardanoUtils,
    private val chainRepository: ChainRepository,
    private val blockFetchRepository: BlockFetchRepository,
    private val ledgerRepository: LedgerRepository,
    private val ledgerDao: LedgerDao,
) : MiniProtocol(protocolId = 0x0003.toShort()) {

    companion object {
        private const val BLOCK_BUFFER_SIZE = 100

        private val TX_SPENT_UTXOS_INDEX = CborInteger.create(0)
        private val TX_DESTS_INDEX = CborInteger.create(1) // destination addresses are at index 1

        //private val TX_CERTS_INDEX = CborInteger.create(4)
        private val TX_MINTS_INDEX = CborInteger.create(9)

        //private val POOL_REGISTRATION = BigInteger.valueOf(3L)

        private val NFT_METADATA_KEY = CborInteger.create(721)
        private val NFT_METADATA_KEY_NAME = CborTextString.create("name")
        private val NFT_METADATA_KEY_IMAGE = CborTextString.create("image")
        private val NFT_METADATA_KEY_DESC = CborTextString.create("description")
        //private val FT_METADATA_KEY_DECIMALS = CborTextString.create("name")
        //private val FT_METADATA_KEY_DESC = CborTextString.create("desc")
    }

    private val log by lazy { LoggerFactory.getLogger(BlockFetchProtocol::class.java) }

    override val RX_BUFFER_SIZE: Int = 1_048_576 // 1mb

    private var state = State.Idle
        set(value) {
            field = value
            _agencyFlow.tryEmit(agency)
        }

    private val _agencyFlow = MutableSharedFlow<Agency>(replay = 1, extraBufferCapacity = 4).apply { tryEmit(agency) }
    override val agencyFlow: Flow<Agency> = _agencyFlow

    override val agency: Agency
        get() = when (state) {
            State.Idle -> Agency.Client
            State.Busy, State.Streaming -> Agency.Server
            State.Done -> Agency.None
        }

    enum class State {
        Idle,
        Busy,
        Streaming,
        Done
    }

    override suspend fun sendData(): ByteBuffer {
        return when (state) {
            State.Idle -> {
                val chainBlock = chainRepository.findTipBlock()
                val blockFetch = blockFetchRepository.findTipBlock()
                if (chainBlock == null) {
                    // wait until a new block has arrived
                    ChainSyncProtocol.newBlockFlow.first()
                    // return an unpopulated buffer
                    muxByteBufferPool.borrow()
                } else if (blockFetch == null) {
                    // no blocks fetched yet. Start at the start block.
                    val startChainBlock = chainRepository.findStartBlock()!!
                    val endChainBlock = chainRepository.findByBlockNumber(
                        startChainBlock.blockNumber + min(
                            chainBlock.blockNumber - startChainBlock.blockNumber,
                            100L
                        )
                    )!!
                    val payload = muxByteBufferPool.borrow()
                    //log.warn("MsgRequestRange at beginning. from ${startChainBlock.blockNumber} to ${endChainBlock.blockNumber}")
                    MsgRequestRange(
                        start = Pair(startChainBlock.slotNumber, startChainBlock.hash.hexToByteArray()),
                        end = Pair(endChainBlock.slotNumber, endChainBlock.hash.hexToByteArray()),
                    ).writeToBuffer(payload)
                    state = State.Busy
                    payload.flip()
                } else if (chainBlock.hash != blockFetch.hash) {
                    val payload = muxByteBufferPool.borrow()
                    // we have blocks to fetch
                    val difference = chainBlock.blockNumber - blockFetch.blockNumber
                    if (difference == 0L) {
                        // re-fetch the tip block. We must have rolled back
                        val point = Pair(chainBlock.slotNumber, chainBlock.hash.hexToByteArray())
                        //log.warn("MsgRequestRange at tip. from ${chainBlock.blockNumber} to ${chainBlock.blockNumber}")
                        MsgRequestRange(start = point, end = point).writeToBuffer(payload)
                    } else if (difference > 100L) {
                        // only fetch 100 blocks
                        val startBlock = chainRepository.findByBlockNumber(blockFetch.blockNumber + 1)!!
                        val endBlock = chainRepository.findByBlockNumber(blockFetch.blockNumber + 100)!!
                        val startPoint = Pair(startBlock.slotNumber, startBlock.hash.hexToByteArray())
                        val endPoint = Pair(endBlock.slotNumber, endBlock.hash.hexToByteArray())
                        //log.warn("MsgRequestRange in middle. from ${startBlock.blockNumber} to ${endBlock.blockNumber}")
                        MsgRequestRange(start = startPoint, end = endPoint).writeToBuffer(payload)
                    } else {
                        // fetch remaining blocks up to tip
                        val startBlock = chainRepository.findByBlockNumber(blockFetch.blockNumber + 1)!!
                        val startPoint = Pair(startBlock.slotNumber, startBlock.hash.hexToByteArray())
                        val endPoint = Pair(chainBlock.slotNumber, chainBlock.hash.hexToByteArray())
                        //log.warn("MsgRequestRange catchup. from ${startBlock.blockNumber} to ${chainBlock.blockNumber}")
                        MsgRequestRange(start = startPoint, end = endPoint).writeToBuffer(payload)
                    }
                    state = State.Busy
                    payload.flip()
                } else {
                    // We're already sync'd for BlockFetch so far.
                    // Wait until a new block has arrived
                    ChainSyncProtocol.newBlockFlow.first()
                    // return an unpopulated buffer
                    muxByteBufferPool.borrow()
                }
            }
            else -> throw IllegalStateException("We should not call sendData() when we're in a $state state!")
        }
    }

    override fun receiveData(payload: ByteBuffer) {
        ByteArrayInputStream(payload.array(), payload.position(), payload.remaining()).use { byteStream ->
            CborReader.createFromInputStream(byteStream).apply {
                while (byteStream.available() > 0) {
                    val cborArray = try {
                        readDataItem() as CborArray
                    } catch (e: Throwable) {
                        log.error("Error parsing cbor (position: ${payload.position()}, limit: ${payload.limit()}, remaining: ${payload.remaining()}: ${payload.array()}")
                        throw e
                    }
                    val messageId: Long = cborArray.elementToLong(0)
                    when (state) {
                        State.Busy -> {
                            when (messageId) {
                                MsgNoBlocks.MESSAGE_ID -> {
                                    state = State.Idle
                                }
                                MsgStartBatch.MESSAGE_ID -> {
                                    //log.warn("MsgStartBatch")
                                    state = State.Streaming
                                }
                            }
                        }
                        State.Streaming -> {
                            when (messageId) {
                                MsgBatchDone.MESSAGE_ID -> {
                                    //log.warn("MsgBatchDone")
                                    state = State.Idle
                                }
                                MsgBlock.MESSAGE_ID -> {
                                    //log.warn("MsgBlock: ${blockBuffer.size + 1}")
                                    processBlock(cborArray)
                                    state = State.Streaming
                                }
                            }
                        }
                        else -> throw IllegalStateException("We should not call receiveData() when we're in a $state state!")
                    }
                }
            }
        }
    }

    private fun processBlock(cborArray: CborArray) {
        try {
            val blockByteArray = cborArray.elementToByteArray(1)
            val blockCborArray = CborReader.createFromByteArray(blockByteArray).readDataItem() as CborArray
//        val someNumber = blockCborArray.elementToLong(0)
            val wholeBlockCborArray = blockCborArray.elementAt(1) as CborArray
            val blockHeaderCborArray = wholeBlockCborArray.elementAt(0) as CborArray

//            if (dumpBlockCborAndExit) {
//                log.warn("block cbor: ${wholeBlockCborArray.toCborByteArray().toHexString()}")
//                Thread.sleep(1000)
//                exitProcess(0)
//            }
            //val witnessSetsCborHex = wholeBlockCborArray.elementAt(2).toCborByteArray().toHexString()
            //val transactionMetadataCborHex = wholeBlockCborArray.elementAt(3).toCborByteArray().toHexString()
            //if (transactionMetadataCborHex.length > 10) {
            //    log.warn("block cborMetadata: $transactionMetadataCborHex")
            //}

            // calculate the block hash
            val wrappedBlockHeaderBytes = blockHeaderCborArray.toCborByteArray()
            val hash = Blake2b.hash256(wrappedBlockHeaderBytes).toHexString()

            // block header
            val blockHeaderCborArrayInner = blockHeaderCborArray.elementAt(0) as CborArray
            val blockNumber = blockHeaderCborArrayInner.elementToBigInteger(0).toLong()
            val slotNumber = blockHeaderCborArrayInner.elementToBigInteger(1).toLong()

            val prevHash = blockHeaderCborArrayInner.elementToHexString(2)
//            val nodeVkey = blockHeaderCborArrayInner.elementToHexString(3) // issuer_vkey
//            val nodeVrfVkey = blockHeaderCborArrayInner.elementToHexString(4)
//            val nonceCborArray = blockHeaderCborArrayInner.elementAt(5) as CborArray
//            val etaVrf0 = nonceCborArray.elementToHexString(0)
//            val etaVrf1 = nonceCborArray.elementToHexString(1)
//            val leaderCborArray = blockHeaderCborArrayInner.elementAt(6) as CborArray
//            val leaderVrf0 = leaderCborArray.elementToHexString(0)
//            val leaderVrf1 = leaderCborArray.elementToHexString(1)
//            val blockSize = blockHeaderCborArrayInner.elementToBigInteger(7).toInt()
//            val blockBodyHash = blockHeaderCborArrayInner.elementToHexString(8)
//            val poolOpcert = blockHeaderCborArrayInner.elementToHexString(9)
//            val sequenceNumber = blockHeaderCborArrayInner.elementToBigInteger(10).toInt()
//            val kesPeriod = blockHeaderCborArrayInner.elementToBigInteger(11).toInt()
//            val sigmaSignature = blockHeaderCborArrayInner.elementToHexString(12)
//            val protocolMajorVersion = blockHeaderCborArrayInner.elementToBigInteger(13).toInt()
//            val protocolMinorVersion = blockHeaderCborArrayInner.elementToBigInteger(14).toInt()

//            val transactionIdsInBlock = mutableListOf<String>()
            val spentUtxos = mutableSetOf<SpentUtxo>()
            val createdUtxos = mutableSetOf<CreatedUtxo>()
            val nativeAssetsToMint =
                mutableMapOf<Int, MutableSet<NativeAsset>>() // mint declarations indexed by tx index in the block
            val nativeAssetsMetadata = mutableSetOf<NativeAssetMetadata>()
            (wholeBlockCborArray.elementAt(1) as CborArray).forEachIndexed { transactionIndex, transaction ->
                val transactionId = cardanoUtils.calculateTransactionId(transaction as CborMap)

                //log.warn("transactionId calculated: $transactionId")
//                log.warn("transaction cbor: ${transaction.toCborByteArray().toHexString()}")

//                BlockController.submittedTransactionCache[transactionId]?.let {
//                    if (log.isDebugEnabled) {
//                        log.debug("Our transaction $transactionId was seen in a block!")
//                    }
//                    // Store that our submitted transaction was included in a block in case this block is rolled back
//                    // later.
//                    transactionIdsInBlock.add(transactionId)
//                }

                ((transaction)[TX_SPENT_UTXOS_INDEX] as CborArray).forEach { source ->
                    var utxoHash = ""
                    var utxoIx = 0L
                    (source as CborArray).forEach { utxoElement ->
                        when (utxoElement) {
                            is CborByteString -> utxoHash = utxoElement.byteArrayValue().toHexString()
                            is CborInteger -> utxoIx = utxoElement.longValue()
                        }
                    }
                    spentUtxos.add(SpentUtxo(utxoHash, utxoIx))
                }

                (transaction[TX_DESTS_INDEX] as CborArray).forEachIndexed { ix, destination ->
                    val addressBytes = ((destination as CborArray).elementAt(0) as CborByteString).byteArrayValue()
                    val encodedAddress: String
                    var stakeAddress: String? = null
                    if (addressBytes[0] == ENTERPRISE_ADDRESS_PREFIX_MAINNET) {
                        // this is a mainnet enterprise address we might care about
                        encodedAddress = Bech32.encode("addr", addressBytes)
                    } else if (addressBytes[0] == ENTERPRISE_ADDRESS_PREFIX_TESTNET) {
                        // this is a testnet enterprise address we might care about
                        encodedAddress = Bech32.encode("addr_test", addressBytes)
                    } else if (addressBytes[0] == STAKE_PAYMENT_ADDRESS_PREFIX_MAINNET || addressBytes[0] == STAKED_SCRIPT_ADDRESS_PREFIX_MAINNET) {
                        encodedAddress = Bech32.encode("addr", addressBytes)
                        stakeAddress = Bech32.encode("stake", ByteArray(29).apply {
                            set(0, STAKE_ADDRESS_PREFIX_MAINNET)
                            addressBytes.sliceArray(29..56).copyInto(this, 1)
                        })
                    } else if (addressBytes[0] == STAKE_PAYMENT_ADDRESS_PREFIX_TESTNET || addressBytes[0] == STAKED_SCRIPT_ADDRESS_PREFIX_TESTNET) {
                        encodedAddress = Bech32.encode("addr_test", addressBytes)
                        stakeAddress = Bech32.encode("stake_test", ByteArray(29).apply {
                            set(0, STAKE_ADDRESS_PREFIX_TESTNET)
                            addressBytes.sliceArray(29..56).copyInto(this, 1)
                        })
                    } else if (addressBytes[0] == SCRIPT_ADDRESS_PREFIX_MAINNET || addressBytes[0] == SCRIPT2_ADDRESS_PREFIX_MAINNET) {
                        encodedAddress = Bech32.encode("addr", addressBytes)
                    } else if (addressBytes[0] == SCRIPT_ADDRESS_PREFIX_TESTNET || addressBytes[0] == SCRIPT2_ADDRESS_PREFIX_TESTNET) {
                        encodedAddress = Bech32.encode("addr_test", addressBytes)
                    } else if (addressBytes[0] == BYRON_ADDRESS_PREFIX) {
                        encodedAddress = addressBytes.toHexString()
                    } else {
                        encodedAddress = addressBytes.toHexString()
                        log.trace("Unknown Address: $encodedAddress")
                    }

                    // Lovelace and native assets sent along with this UTxO output
                    val (utxoLovelace, nativeAssets) = when (val utxoItem = destination.elementAt(1)) {
                        is CborInteger -> Pair(utxoItem.bigIntegerValue(), emptyList())
                        is CborArray -> {
                            val nativeAssets = mutableListOf<NativeAsset>()
                            (utxoItem.elementAt(1) as? CborMap)?.let { nativeAssetMap ->
                                nativeAssetMap.keySet().forEach { policyIdCborObject ->
                                    val policyId = (policyIdCborObject as CborByteString).byteArrayValue().toHexString()
                                    val policyMap = nativeAssetMap[policyIdCborObject] as CborMap
                                    policyMap.entrySet().forEach { (assetNameCborObject, assetAmountCborObject) ->
                                        val assetName =
                                            (assetNameCborObject as CborByteString).byteArrayValue().toHexString()
                                        val assetAmount = (assetAmountCborObject as CborInteger).bigIntegerValue()
                                        nativeAssets.add(NativeAsset(assetName, policyId, assetAmount))
                                    }
                                }
                            }
                            Pair(utxoItem.elementToBigInteger(0), nativeAssets.toList())
                        }
                        else -> {
                            log.error("Could not convert utxo output lovelace value!")
                            Pair(BigInteger.ZERO, emptyList())
                        }
                    }

                    createdUtxos.add(
                        CreatedUtxo(
                            address = encodedAddress,
                            stakeAddress = stakeAddress,
                            hash = transactionId,
                            ix = ix.toLong(),
                            lovelace = utxoLovelace,
                            nativeAssets = nativeAssets
                        )
                    )
                }

//                (transaction[TX_CERTS_INDEX] as? CborArray)?.forEach { cert ->
//                    (cert as CborArray).apply {
//                        if (elementToBigInteger(0) == POOL_REGISTRATION) {
//                            val poolId = elementToHexString(1)
//                            val vrfVKeyHash = elementToHexString(2)
//                            val pledge = elementToBigInteger(3).toLong()
//                            val cost = elementToBigInteger(4).toLong()
//                            val (marginNumerator, marginDenominator) = (elementAt(5) as CborArray).let {
//                                Pair(it.elementToBigInteger(0).toLong(), it.elementToBigInteger(1).toLong())
//                            }
//                            val metadataArray = elementAt(9) as? CborArray
//                            val metadataUrl = metadataArray?.elementAt(0)?.toJavaObject()?.toString() ?: ""
//                            val metadataHash = metadataArray?.elementToHexString(1) ?: ""
//                            poolRegistrations.add(
//                                PoolRegistration(
//                                    poolId,
//                                    vrfVKeyHash,
//                                    metadataUrl,
//                                    metadataHash,
//                                    pledge,
//                                    cost,
//                                    marginNumerator,
//                                    marginDenominator
//                                )
//                            )
//                            //log.info("pool: $poolId, vrfHash: $vrfVKeyHash, metadataUrl: $metadataUrl, metadataHash: $metadataHash")
//                        }
//                    }
//                }

                // Figure out which tokens are minted/burned in this transaction
                (transaction[TX_MINTS_INDEX] as? CborMap)?.entrySet()?.forEach { policyEntry ->
                    val mintPolicyId = (policyEntry.key as CborByteString).byteArrayValue().toHexString()
                    (policyEntry.value as CborMap).entrySet().forEach { nameEntry ->
                        val mintName = (nameEntry.key as CborByteString).byteArrayValue().toHexString()
                        val mintAmount = (nameEntry.value as CborInteger).bigIntegerValue()
                        if (mintAmount > BigInteger.ZERO) {
                            val tokenSet = nativeAssetsToMint[transactionIndex] ?: run {
                                mutableSetOf<NativeAsset>().also { nativeAssetsToMint[transactionIndex] = it }
                            }
                            tokenSet.add(NativeAsset(mintName, mintPolicyId, mintAmount))
                        }
                    }
                }
            }

            (wholeBlockCborArray.elementAt(3) as? CborMap)?.let { blockMetadataMap ->
                nativeAssetsToMint.forEach { (transactionInBlockIndex, nativeAssets) ->
                    ((blockMetadataMap[CborInteger.create(transactionInBlockIndex)] as? CborArray)?.elementAt(0) as? CborMap)?.let { metadataMap ->
                        //log.warn("metadataMap: ${metadataMap.toJavaObject()}")
                        (metadataMap[NFT_METADATA_KEY] as? CborMap)?.let { nftMetadata ->
                            //val ftMetadata = metadataMap[CborInteger.create(20)] as? CborMap // TODO handle token decimals
                            //log.warn("nftMetadata: ${nftMetadata.toJavaObject()}")
                            nativeAssets.forEach { nativeAsset ->
                                ((nftMetadata.get(CborTextString.create(nativeAsset.policy)) as? CborMap)?.get(
                                    CborTextString.create(String(nativeAsset.name.hexToByteArray()))
                                ) as? CborMap)
                                    ?.let inner@{ tokenMetadataDetails ->
                                        //log.warn("tokenMetadataDetails: ${tokenMetadataDetails.toJavaObject()}")
                                        val tokenName =
                                            (tokenMetadataDetails[NFT_METADATA_KEY_NAME] as? CborTextString)?.stringValue()
                                                ?: return@inner
                                        val tokenImage = when (tokenMetadataDetails[NFT_METADATA_KEY_IMAGE]) {
                                            is CborTextString -> (tokenMetadataDetails[NFT_METADATA_KEY_IMAGE] as CborTextString).stringValue()
                                            is CborArray -> {
                                                (tokenMetadataDetails[NFT_METADATA_KEY_IMAGE] as CborArray).joinToString(
                                                    separator = ""
                                                ) {
                                                    it.toJavaObject().toString()
                                                }
                                            }
                                            else -> return@inner
                                        }
                                        val tokenDesc = when (tokenMetadataDetails[NFT_METADATA_KEY_DESC]) {
                                            is CborTextString -> (tokenMetadataDetails[NFT_METADATA_KEY_DESC] as CborTextString).stringValue()
                                            is CborArray -> {
                                                (tokenMetadataDetails[NFT_METADATA_KEY_DESC] as CborArray).joinToString(
                                                    separator = " "
                                                ) { it.toJsonString().toString() }
                                            }
                                            else -> null
                                        }

                                        val nativeAssetMetadata = NativeAssetMetadata(
                                            assetName = nativeAsset.name,
                                            assetPolicy = nativeAsset.policy,
                                            metadataName = tokenName,
                                            metadataImage = tokenImage,
                                            metadataDescription = tokenDesc
                                        )
                                        nativeAssetsMetadata.add(nativeAssetMetadata)
                                    }
                            }
                        }
                    }
                }
            }

            val isTip = hash == ChainSyncProtocol.tipHash
            queueSaveBlockToLedger(
                slotNumber,
                blockNumber,
                hash,
                prevHash,
                spentUtxos,
                createdUtxos,
                nativeAssetsMetadata,
                isTip
            )
        } catch (e: Throwable) {
            log.error("Error Processing Block cbor!: ${cborArray.toCborByteArray().toHexString()}")
            log.error("Exception!", e)
            runBlocking { delay(1000) }
            exitProcess(1)
        }
    }

    private val blockBuffer: MutableList<LedgerBlock> = mutableListOf()

    private fun queueSaveBlockToLedger(
        slotNumber: Long,
        blockNumber: Long,
        hash: String,
        prevHash: String,
        spentUtxos: Set<SpentUtxo>,
        createdUtxos: Set<CreatedUtxo>,
        nativeAssetsMetadata: Set<NativeAssetMetadata>,
        isTip: Boolean,
    ) {
        blockBuffer.add(
            LedgerBlock(
                slotNumber,
                blockNumber,
                hash,
                prevHash,
                spentUtxos,
                createdUtxos,
                nativeAssetsMetadata,
            )
        )

        if (blockBuffer.size == BLOCK_BUFFER_SIZE || isTip) {
            val blocksToCommit: List<LedgerBlock> = mutableListOf<LedgerBlock>().apply { addAll(blockBuffer) }
            blockBuffer.clear()
            commitBlocks(blocksToCommit, isTip)
        }
    }

    @Transactional
    @Modifying
    private fun commitBlocks(blocksToCommit: List<LedgerBlock>, isTip: Boolean) {
//        if (!isTip) {
//            log.warn("starting commitBlocks() with ${blocksToCommit.size} blocks...")
//        }
        var rollbackTime = 0L
        var nativeAssetTime = 0L
        var spendTime = 0L
        var createTime = 0L
        var pruneTime = 0L
        measureTimeMillis {
//            warnLongQueriesDuration = 200L
            blocksToCommit.forEach { ledgerBlock ->
                ledgerBlock.apply {
                    // Mark same block number as rolled back
                    rollbackTime += measureTimeMillis {
                        blockFetchRepository.doRollbackDelete(blockNumber)
                        ledgerRepository.doRollbackDelete(blockNumber)
                        ledgerRepository.doRollbackUpdate(blockNumber)
                    }

                    // Load any Native asset metadata
                    nativeAssetTime += measureTimeMillis {
                        ledgerDao.upcertNativeAssets(nativeAssetsMetadata)
                    }

                    // Insert unspent utxos
                    createTime += measureTimeMillis {
                        ledgerDao.createUtxos(slotNumber, blockNumber, createdUtxos)
                    }

                    // Mark spent utxos as spent
                    spendTime += measureTimeMillis {
                        ledgerDao.spendUtxos(slotNumber, blockNumber, spentUtxos)
                    }

                    // Mark this block as fetched
                    blockFetchRepository.save(
                        BlockFetch(
                            blockNumber = blockNumber,
                            slotNumber = slotNumber,
                            hash = hash,
                            prevHash = prevHash
                        )
                    )
                }
            }

            // Prune any old spent utxos we don't need any longer older than 30 minutes
            pruneTime = measureTimeMillis {
                ledgerRepository.pruneSpent(beforeSlot = cardanoUtils.getCurrentSlot() - 1800L)
            }
        }.also { totalTime ->
            if (isTip && totalTime > 500L) {
                log.warn("commitBlocks() total: ${totalTime}ms, rollback: ${rollbackTime}ms, nativeAsset: ${nativeAssetTime}ms, create: ${createTime}ms, spend: ${spendTime}ms, prune: ${pruneTime}ms")
            }
            log.info(
                "BlockFetch: Saved block: ${blocksToCommit.last().blockNumber} of ${ChainSyncProtocol.tipBlockNumber} - %.2f%% synced".format(
                    blocksToCommit.last().blockNumber.toDouble() / ChainSyncProtocol.tipBlockNumber * 100.0
                )
            )
        }
    }

    private class LedgerBlock(
        val slotNumber: Long,
        val blockNumber: Long,
        val hash: String,
        val prevHash: String,
        val spentUtxos: Set<SpentUtxo>,
        val createdUtxos: Set<CreatedUtxo>,
        val nativeAssetsMetadata: Set<NativeAssetMetadata>
    )

}