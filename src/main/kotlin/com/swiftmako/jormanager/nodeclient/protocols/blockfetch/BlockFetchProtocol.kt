package com.swiftmako.jormanager.nodeclient.protocols.blockfetch

import com.firehose.controllers.nodeclient.protocol.Agency
import com.google.iot.cbor.CborArray
import com.google.iot.cbor.CborByteString
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborMap
import com.google.iot.cbor.CborObject
import com.google.iot.cbor.CborReader
import com.google.iot.cbor.CborTextString
import com.swiftmako.jormanager.ktx.elementToBigInteger
import com.swiftmako.jormanager.ktx.elementToByteArray
import com.swiftmako.jormanager.ktx.elementToLong
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.CreatedUtxo
import com.swiftmako.jormanager.model.NativeAsset
import com.swiftmako.jormanager.model.NativeAssetMetadata
import com.swiftmako.jormanager.model.SpentUtxo
import com.swiftmako.jormanager.nodeclient.protocols.MiniProtocol
import com.swiftmako.jormanager.nodeclient.protocols.chainsync.ChainSyncProtocol
import com.swiftmako.jormanager.nodeclient.protocols.mux.muxByteBufferPool
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
import com.swiftmako.jormanager.utils.TransactionCache
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.math.min
import kotlin.system.exitProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BlockFetchProtocol(
    private val cardanoUtils: CardanoUtils,
    private val chainRepository: ChainRepository,
    private val ledgerDao: LedgerDao,
) : MiniProtocol(protocolId = 0x0003.toShort()), CoroutineScope {
    companion object {
        var isTip = false

        private const val BLOCK_BUFFER_SIZE = 100L

        private val TX_SPENT_UTXOS_INDEX = CborInteger.create(0)
        private val TX_DESTS_INDEX = CborInteger.create(1) // destination addresses are at index 1

        // private val TX_CERTS_INDEX = CborInteger.create(4)
        private val TX_MINTS_INDEX = CborInteger.create(9)

        private val UTXO_ADDRESS_INDEX = CborInteger.create(0)
        private val UTXO_AMOUNT_INDEX = CborInteger.create(1)

        // private val POOL_REGISTRATION = BigInteger.valueOf(3L)

        private val NFT_METADATA_KEY = CborInteger.create(721)
        private val NFT_METADATA_KEY_NAME = CborTextString.create("name")
        private val NFT_METADATA_KEY_IMAGE = CborTextString.create("image")
        private val NFT_METADATA_KEY_DESC = CborTextString.create("description")
        // private val FT_METADATA_KEY_DECIMALS = CborTextString.create("name")
        // private val FT_METADATA_KEY_DESC = CborTextString.create("desc")
    }

    private val log by lazy { KotlinLogging.logger("BlockFetchProtocol") }

    override val RX_BUFFER_SIZE: Int = 8_388_608 // 8mb

    private val blockBuffer = mutableListOf<LedgerBlock>()

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext =
        job + Executors.newSingleThreadScheduledExecutor().asCoroutineDispatcher() +
                CoroutineExceptionHandler { _, throwable ->
                    if (throwable !is CancellationException) {
                        log.error(throwable) { "Uncaught coroutine exception!" }
                    }
                }
    private var commitBlocksJob: Job? = null

    private var state = State.Idle
        set(value) {
            field = value
            if (!_agencyFlow.tryEmit(agency)) {
                log.error { "Failed to emit Agency!" }
            }
        }

    private val _agencyFlow =
        MutableSharedFlow<Agency>(
            replay = 1,
            extraBufferCapacity = BLOCK_BUFFER_SIZE.toInt() * 2,
        ).apply { tryEmit(agency) }
    override val agencyFlow: Flow<Agency> = _agencyFlow

    override val agency: Agency
        get() =
            when (state) {
                State.Idle -> Agency.Client
                State.Busy, State.Streaming -> Agency.Server
                State.Done -> Agency.None
            }

    enum class State {
        Idle,
        Busy,
        Streaming,
        Done,
    }

    override fun shutdown() {
        state = State.Done
        job.cancelChildren()
    }

    override suspend fun sendData(): ByteBuffer {
        // log.info("sendData(): state = $state")
        return when (state) {
            State.Idle -> {
                // Wait to finish committing any blocks before we request new ones
                commitBlocksJob?.join()
                commitBlocksJob = null

                var chainBlock = chainRepository.findTipBlock()
                var blockFetch = LedgerRepository.findTipBlock()
                if (chainBlock == null || chainBlock.hash == blockFetch?.hash) {
                    // wait until a new block has arrived
                    // log.info("Await next ChainSync block...")
                    ChainSyncProtocol.newBlockFlow.first()
                    chainBlock = chainRepository.findTipBlock()
                    blockFetch = LedgerRepository.findTipBlock()
                }

                requireNotNull(chainBlock)

                if (blockFetch == null) {
                    // no blocks fetched yet. Start at the start block.
                    val startChainBlock = chainRepository.findStartBlock()!!
                    val endChainBlock =
                        chainRepository.findByBlockNumber(
                            startChainBlock.blockNumber +
                                    min(
                                        chainBlock.blockNumber - startChainBlock.blockNumber,
                                        BLOCK_BUFFER_SIZE,
                                    ),
                        )!!
                    val payload = muxByteBufferPool.borrow()
                    // log.warn("MsgRequestRange at beginning. from ${startChainBlock.blockNumber} to ${endChainBlock.blockNumber}")
                    MsgRequestRange(
                        start = Pair(startChainBlock.slotNumber, startChainBlock.hash.hexToByteArray()),
                        end = Pair(endChainBlock.slotNumber, endChainBlock.hash.hexToByteArray()),
                    ).writeToBuffer(payload)
                    state = State.Busy
                    payload.flip()
                } else {
                    val payload = muxByteBufferPool.borrow()
                    // we have blocks to fetch
                    val difference = chainBlock.blockNumber - blockFetch.blockNumber
                    if (difference <= 0L) {
                        // re-fetch the tip block. We must have rolled back
                        val point = Pair(chainBlock.slotNumber, chainBlock.hash.hexToByteArray())
                        // log.warn("MsgRequestRange at tip. from ${chainBlock.blockNumber} to ${chainBlock.blockNumber}")
                        MsgRequestRange(start = point, end = point).writeToBuffer(payload)
                    } else if (difference > 100L) {
                        // only fetch 100 blocks
                        // log.warn("fetch blockNumber: ${blockFetch.blockNumber} to ${blockFetch.blockNumber + 100}")
                        val startBlock = chainRepository.findByBlockNumber(blockFetch.blockNumber + 1)!!
                        val endBlock = chainRepository.findByBlockNumber(blockFetch.blockNumber + BLOCK_BUFFER_SIZE)!!
                        val startPoint = Pair(startBlock.slotNumber, startBlock.hash.hexToByteArray())
                        val endPoint = Pair(endBlock.slotNumber, endBlock.hash.hexToByteArray())
                        // log.warn("MsgRequestRange in middle. from ${startBlock.blockNumber} to ${endBlock.blockNumber}")
                        MsgRequestRange(start = startPoint, end = endPoint).writeToBuffer(payload)
                    } else {
                        // fetch remaining blocks up to tip
                        val startBlock = chainRepository.findByBlockNumber(blockFetch.blockNumber + 1)!!
                        val startPoint = Pair(startBlock.slotNumber, startBlock.hash.hexToByteArray())
                        val endPoint = Pair(chainBlock.slotNumber, chainBlock.hash.hexToByteArray())
                        // log.warn("MsgRequestRange catchup. from ${startBlock.blockNumber} to ${chainBlock.blockNumber}")
                        MsgRequestRange(start = startPoint, end = endPoint).writeToBuffer(payload)
                    }
                    state = State.Busy
                    payload.flip()
                }
            }

            else -> throw IllegalStateException("We should not call sendData() when we're in a $state state!")
        }
    }

    override fun receiveData(payload: ByteBuffer) {
        ByteArrayInputStream(payload.array(), payload.position(), payload.remaining()).use { byteStream ->
            CborReader.createFromInputStream(byteStream).apply {
                while (byteStream.available() > 0) {
                    val cborArray =
                        try {
                            readDataItem() as CborArray
                        } catch (e: Throwable) {
                            log.error { "Error parsing cbor (position: ${payload.position()}, limit: ${payload.limit()}, remaining: ${payload.remaining()}: ${payload.array()}" }
                            throw e
                        }
                    val messageId: Long = cborArray.elementToLong(0)
                    when (state) {
                        State.Busy -> {
                            when (messageId) {
                                MsgNoBlocks.MESSAGE_ID -> {
                                    log.warn { "MsgNoBlocks" }
                                    state = State.Idle
                                }

                                MsgStartBatch.MESSAGE_ID -> {
                                    // log.warn("MsgStartBatch")
                                    state = State.Streaming
                                }
                            }
                        }

                        State.Streaming -> {
                            when (messageId) {
                                MsgBatchDone.MESSAGE_ID -> {
                                    // log.warn("MsgBatchDone")

                                    commitBlocksJob =
                                        launch {
                                            val isTip = blockBuffer.last().hash == ChainSyncProtocol.tipHash
                                            ledgerDao.commitBlocks(blockBuffer, isTip)
                                            blockBuffer.clear()
                                            BlockFetchProtocol.isTip = isTip
                                        }

                                    state = State.Idle
                                }

                                MsgBlock.MESSAGE_ID -> {
                                    // log.warn("MsgBlock: ${blockBuffer.size + 1}")
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
            // val witnessSetsCborHex = wholeBlockCborArray.elementAt(2).toCborByteArray().toHexString()
            // val transactionMetadataCborHex = wholeBlockCborArray.elementAt(3).toCborByteArray().toHexString()
            // if (transactionMetadataCborHex.length > 10) {
            //    log.warn("block cborMetadata: $transactionMetadataCborHex")
            // }

            // calculate the block hash
            val wrappedBlockHeaderBytes = blockHeaderCborArray.toCborByteArray()
            val hash = Blake2b.hash256(wrappedBlockHeaderBytes).toHexString()

            // block header
            val blockHeaderCborArrayInner = blockHeaderCborArray.elementAt(0) as CborArray
            val blockNumber = blockHeaderCborArrayInner.elementToBigInteger(0).toLong()
            val slotNumber = blockHeaderCborArrayInner.elementToBigInteger(1).toLong()

            val prevHash =
                when (val prevHashElement = blockHeaderCborArrayInner.elementAt(2)) {
                    is CborByteString -> prevHashElement.byteArrayValue()[0].toHexString()
                    // might be cbor null if we launched without the byron era
                    else -> ""
                }
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

            val transactionIdsInBlock = mutableListOf<String>()
            val spentUtxos = mutableSetOf<SpentUtxo>()
            val createdUtxos = mutableSetOf<CreatedUtxo>()
            val nativeAssetsToMint =
                mutableMapOf<Int, MutableSet<NativeAsset>>() // mint declarations indexed by tx index in the block
            val nativeAssetsMetadata = mutableSetOf<NativeAssetMetadata>()
            (wholeBlockCborArray.elementAt(1) as CborArray).forEachIndexed { transactionIndex, transaction ->
                val transactionId = cardanoUtils.calculateTransactionId(transaction as CborMap)

                // log.warn("transactionId calculated: $transactionId")
//                log.warn("transaction cbor: ${transaction.toCborByteArray().toHexString()}")

                TransactionCache.get(transactionId)?.let {
                    log.debug { "Our transaction $transactionId was seen in a block!" }
                    // Store that our submitted transaction was included in a block in case this block is rolled back
                    // later.
                    transactionIdsInBlock.add(transactionId)
                }

                (transaction[TX_SPENT_UTXOS_INDEX] as CborArray).forEach { source ->
                    var utxoHash = ""
                    var utxoIx = 0L
                    (source as CborArray).forEach { utxoElement ->
                        when (utxoElement) {
                            is CborByteString -> utxoHash = utxoElement.byteArrayValue()[0].toHexString()
                            is CborInteger -> utxoIx = utxoElement.longValue()
                        }
                    }
                    spentUtxos.add(SpentUtxo(utxoHash, utxoIx))
                }

                (transaction[TX_DESTS_INDEX] as CborArray).forEachIndexed { ix, destination ->
                    val addressBytes =
                        when (destination) {
                            is CborArray -> {
                                // alonzo and earlier is an array
                                (destination.elementAt(0) as CborByteString).byteArrayValue()[0]
                            }

                            is CborMap -> {
                                (destination[UTXO_ADDRESS_INDEX] as CborByteString).byteArrayValue()[0]
                            }

                            else -> {
                                throw IllegalArgumentException("Expected UTXO to be Array or Map!")
                            }
                        }

                    var encodedAddress: String? = null
                    var stakeAddress: String? = null
                    if (addressBytes[0] == ENTERPRISE_ADDRESS_PREFIX_MAINNET) {
                        // this is a mainnet enterprise address we might care about
                        encodedAddress = Bech32.encode("addr", addressBytes)
                    } else if (addressBytes[0] == ENTERPRISE_ADDRESS_PREFIX_TESTNET) {
                        // this is a testnet enterprise address we might care about
                        encodedAddress = Bech32.encode("addr_test", addressBytes)
                    } else if (
                        addressBytes[0] == STAKE_PAYMENT_ADDRESS_PREFIX_MAINNET ||
                        addressBytes[0] == STAKED_SCRIPT_ADDRESS_PREFIX_MAINNET
                    ) {
                        encodedAddress = Bech32.encode("addr", addressBytes)
                        stakeAddress =
                            Bech32.encode(
                                "stake",
                                ByteArray(29).apply {
                                    set(0, STAKE_ADDRESS_PREFIX_MAINNET)
                                    addressBytes.sliceArray(29..56).copyInto(this, 1)
                                },
                            )
                    } else if (
                        addressBytes[0] == STAKE_PAYMENT_ADDRESS_PREFIX_TESTNET ||
                        addressBytes[0] == STAKED_SCRIPT_ADDRESS_PREFIX_TESTNET
                    ) {
                        encodedAddress = Bech32.encode("addr_test", addressBytes)
                        stakeAddress =
                            Bech32.encode(
                                "stake_test",
                                ByteArray(29).apply {
                                    set(0, STAKE_ADDRESS_PREFIX_TESTNET)
                                    addressBytes.sliceArray(29..56).copyInto(this, 1)
                                },
                            )
                    } else if (
                        addressBytes[0] == SCRIPT_ADDRESS_PREFIX_MAINNET ||
                        addressBytes[0] == SCRIPT2_ADDRESS_PREFIX_MAINNET
                    ) {
                        encodedAddress = Bech32.encode("addr", addressBytes)
                    } else if (
                        addressBytes[0] == SCRIPT_ADDRESS_PREFIX_TESTNET ||
                        addressBytes[0] == SCRIPT2_ADDRESS_PREFIX_TESTNET
                    ) {
                        encodedAddress = Bech32.encode("addr_test", addressBytes)
                    } else if (addressBytes[0] == BYRON_ADDRESS_PREFIX) {
                        // do not save byron address utxos
                        // encodedAddress = addressBytes.toHexString()
                    } else {
                        // do not save unknown address utxos
                        // encodedAddress = addressBytes.toHexString()
                        log.trace { "Unknown Address: ${addressBytes.toHexString()}" }
                    }

                    // Lovelace and native assets sent along with this UTxO output
                    val utxoItem: CborObject =
                        when (destination) {
                            is CborArray -> {
                                // alonzo and earlier is an array
                                destination.elementAt(1)
                            }

                            is CborMap -> {
                                // babbage is a map
                                destination[UTXO_AMOUNT_INDEX]!!
                            }

                            else -> {
                                throw IllegalArgumentException("Expected UTXO to be Array or Map!")
                            }
                        }
                    val (utxoLovelace, nativeAssets) =
                        when (utxoItem) {
                            is CborInteger -> Pair(utxoItem.bigIntegerValue(), emptyList())
                            is CborArray -> {
                                val nativeAssets = mutableListOf<NativeAsset>()
                                (utxoItem.elementAt(1) as? CborMap)?.let { nativeAssetMap ->
                                    nativeAssetMap.keySet().forEach { policyIdCborObject ->
                                        val policyId =
                                            (policyIdCborObject as CborByteString).byteArrayValue()[0].toHexString()
                                        val policyMap = nativeAssetMap[policyIdCborObject] as CborMap
                                        policyMap.entrySet().forEach { (assetNameCborObject, assetAmountCborObject) ->
                                            val assetName =
                                                (assetNameCborObject as CborByteString).byteArrayValue()
                                                    .ifEmpty { Array(1) { ByteArray(0) } }[0].toHexString()
                                            val assetAmount = (assetAmountCborObject as CborInteger).bigIntegerValue()
                                            nativeAssets.add(NativeAsset(assetName, policyId, assetAmount))
                                        }
                                    }
                                }
                                Pair(utxoItem.elementToBigInteger(0), nativeAssets.toList())
                            }

                            else -> {
                                log.error { "Could not convert utxo output lovelace value!" }
                                Pair(BigInteger.ZERO, emptyList())
                            }
                        }

                    encodedAddress?.let {
                        createdUtxos.add(
                            CreatedUtxo(
                                address = encodedAddress,
                                stakeAddress = stakeAddress,
                                hash = transactionId,
                                ix = ix.toLong(),
                                lovelace = utxoLovelace,
                                nativeAssets = nativeAssets,
                            ),
                        )
                    }
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
                    val mintPolicyId = (policyEntry.key as CborByteString).byteArrayValue()[0].toHexString()
                    (policyEntry.value as CborMap).entrySet().forEach { nameEntry ->
                        val mintName =
                            (nameEntry.key as CborByteString).byteArrayValue()
                                .ifEmpty { Array(1) { ByteArray(0) } }[0].toHexString()
                        val mintAmount = (nameEntry.value as CborInteger).bigIntegerValue()
                        if (mintAmount > BigInteger.ZERO) {
                            val tokenSet =
                                nativeAssetsToMint[transactionIndex] ?: run {
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
                        // log.warn("metadataMap: ${metadataMap.toJavaObject()}")
                        (metadataMap[NFT_METADATA_KEY] as? CborMap)?.let { nftMetadata ->
                            // val ftMetadata = metadataMap[CborInteger.create(20)] as? CborMap // TODO handle token decimals
                            // log.warn("nftMetadata: ${nftMetadata.toJavaObject()}")
                            nativeAssets.forEach { nativeAsset ->
                                (
                                        (nftMetadata.get(CborTextString.create(nativeAsset.policy)) as? CborMap)?.get(
                                            CborTextString.create(String(nativeAsset.name.hexToByteArray())),
                                        ) as? CborMap
                                        )
                                    ?.let inner@{ tokenMetadataDetails ->
                                        // log.warn("tokenMetadataDetails: ${tokenMetadataDetails.toJavaObject()}")
                                        val tokenName =
                                            (tokenMetadataDetails[NFT_METADATA_KEY_NAME] as? CborTextString)?.stringValue()
                                                ?: return@inner
                                        val tokenImage =
                                            when (tokenMetadataDetails[NFT_METADATA_KEY_IMAGE]) {
                                                is CborTextString -> (tokenMetadataDetails[NFT_METADATA_KEY_IMAGE] as CborTextString).stringValue()
                                                is CborArray -> {
                                                    (tokenMetadataDetails[NFT_METADATA_KEY_IMAGE] as CborArray).joinToString(
                                                        separator = "",
                                                    ) {
                                                        it.toJavaObject().toString()
                                                    }
                                                }

                                                else -> return@inner
                                            }
                                        val tokenDesc =
                                            when (tokenMetadataDetails[NFT_METADATA_KEY_DESC]) {
                                                is CborTextString -> (tokenMetadataDetails[NFT_METADATA_KEY_DESC] as CborTextString).stringValue()
                                                is CborArray -> {
                                                    (tokenMetadataDetails[NFT_METADATA_KEY_DESC] as CborArray).joinToString(
                                                        separator = " ",
                                                    ) { it.toJsonString().toString() }
                                                }

                                                else -> null
                                            }

                                        val nativeAssetMetadata =
                                            NativeAssetMetadata(
                                                assetName = nativeAsset.name,
                                                assetPolicy = nativeAsset.policy,
                                                metadataName = tokenName,
                                                metadataImage = tokenImage,
                                                metadataDescription = tokenDesc,
                                            )
                                        nativeAssetsMetadata.add(nativeAssetMetadata)
                                    }
                            }
                        }
                    }
                }
            }

            blockBuffer.add(
                LedgerBlock(
                    slotNumber,
                    blockNumber,
                    hash,
                    prevHash,
                    spentUtxos,
                    createdUtxos,
                    nativeAssetsMetadata,
                    transactionIdsInBlock,
                ),
            )
        } catch (e: Throwable) {
            log.error { "Error Processing Block cbor!: ${cborArray.toCborByteArray().toHexString()}" }
            log.error(e) { "Exception!" }
            exitProcess(1)
        }
    }

    class LedgerBlock(
        val slotNumber: Long,
        val blockNumber: Long,
        val hash: String,
        val prevHash: String,
        val spentUtxos: Set<SpentUtxo>,
        val createdUtxos: Set<CreatedUtxo>,
        val nativeAssetsMetadata: Set<NativeAssetMetadata>,
        val transactionIdsInBlock: List<String>,
    )
}
