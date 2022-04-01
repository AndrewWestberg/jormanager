package com.swiftmako.jormanager.repositories

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.iot.cbor.*
import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.controllers.utils.HostConnection
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.*
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.BlockFetchProtocol
import com.swiftmako.jormanager.nodeclient.protocols.chainsync.ChainSyncProtocol
import com.swiftmako.jormanager.utils.Bech32
import com.swiftmako.jormanager.utils.CardanoUtils
import com.swiftmako.jormanager.utils.TransactionCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Scope
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.io.IOException
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import kotlin.experimental.and
import kotlin.system.measureTimeMillis

@Component
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
class LedgerDao @Autowired constructor(
    private val nodeRepository: NodeRepository,
    private val hostRepository: HostRepository,
    private val fileRepository: FileRepository,
    private val shelleyGenesisAdapter: JsonAdapter<GenesisShelley>,
    private val cardanoUtils: CardanoUtils,
    @Qualifier("refreshWalletChannel") private val refreshWalletChannel: MutableStateFlow<Long?>,
) {
    private val log by lazy { LoggerFactory.getLogger("LedgerDao") }

    private val utxoMutex = Mutex()

    /**
     * Set of the Utxos that are "spent", but not yet in a block. These should be removed once observed to be
     * used up in a block.
     */
    private val spentUtxoSet = mutableSetOf<SpentUtxo>()

    /**
     * Map the address to a list of utxos that have been created, but not yet made it into a block. These should be
     * removed once they are observed to be created in a block.
     */
    private val liveUtxoMap = mutableMapOf<String, Set<Utxo>>()

    /**
     * Store the blocknumber mapped to a list of transactionIds so we can re-submit to the mempool
     * in the event of a rollback.
     */
    private val blockRollbackCache: Cache<Long, List<String>> = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofHours(1))
        .build()

    fun queryUtxos(address: String): List<Utxo> = LedgerRepository.queryUtxos(address)

    private suspend fun chainUtxosToLiveUtxos(address: String, chainUtxos: List<Utxo>): List<Utxo> {
        utxoMutex.withLock {
            return chainUtxos.toMutableSet().apply {
                // add in any liveUtxos from pending transactions
                liveUtxoMap[address]?.let {
                    addAll(it)
                }
            }.filterNot { chainUtxo ->
                // remove any chain utxos that are already "spent" in a pending transaction
                spentUtxoSet.contains(SpentUtxo(chainUtxo.hash, chainUtxo.ix))
            }
        }
    }

    suspend fun queryLiveUtxos(address: String): List<Utxo> {
        val chainUtxos = queryUtxos(address)
        return chainUtxosToLiveUtxos(address, chainUtxos)
    }

    suspend fun updateLiveLedgerState(transactionId: String, cborByteArray: ByteArray) {
        utxoMutex.withLock {
            val tx = CborReader.createFromByteArray(cborByteArray).readDataItem() as CborArray
            val txBody = tx.elementAt(0) as CborMap
            val utxoInArray = txBody.get(CborInteger.create(0L)) as CborArray
            utxoInArray.forEach { utxo ->
                var hash = ""
                var ix = 0L
                (utxo as CborArray).forEach { utxoElement ->
                    when (utxoElement) {
                        is CborByteString -> hash = utxoElement.byteArrayValue().toHexString()
                        else -> ix = (utxoElement as CborInteger).longValue()
                    }
                }
                // Mark this utxo as spent even though it's not in a block yet.
                processSpentUtxoFromSubmitTx(
                    SpentUtxo(hash = hash, ix = ix).also {
                        if (log.isDebugEnabled) {
                            log.debug("SpentUtxo: $it")
                        }
                    }
                )
            }
            val addressOutArray = txBody.get(CborInteger.create(1L)) as CborArray
            addressOutArray.forEachIndexed { ix, txOutput ->
                var address = ""
                var lovelace = BigInteger.ZERO
                val nativeAssets = mutableListOf<NativeAsset>()
                (txOutput as CborArray).forEach { item ->
                    when (item) {
                        is CborByteString -> {
                            val addressBytes = item.byteArrayValue()
                            val prefix = if (addressBytes[0] and 0x01.toByte() == 0x01.toByte()) {
                                "addr"
                            } else {
                                "addr_test"
                            }
                            address = Bech32.encode(prefix, addressBytes)
                        }
                        is CborInteger -> lovelace = item.bigIntegerValue()
                        is CborArray -> {
                            item.forEach { subItem ->
                                when (subItem) {
                                    is CborInteger -> lovelace = subItem.bigIntegerValue()
                                    is CborMap -> {
                                        subItem.keySet().forEach { policyId ->
                                            val policy = (policyId as CborByteString).byteArrayValue().toHexString()
                                            val token = subItem[policyId] as CborMap
                                            token.keySet().forEach { tokenName ->
                                                val name = (tokenName as CborByteString).byteArrayValue().toHexString()
                                                val amount = (token[tokenName] as CborInteger).bigIntegerValue()
                                                nativeAssets.add(
                                                    NativeAsset(
                                                        name = name,
                                                        policy = policy,
                                                        amount = amount
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                processLiveUtxoFromSubmitTx(address,
                    Utxo(
                        hash = transactionId,
                        ix = ix.toLong(),
                        lovelace = lovelace,
                        nativeAssets = nativeAssets
                    ).also {
                        if (log.isDebugEnabled) {
                            log.debug("LiveUtxo: address: $address, $it")
                        }
                    }
                )
            }
        }
        refreshWalletChannel.emit(transactionId.hashCode().toLong())
    }

    private fun processSpentUtxoFromSubmitTx(spentUtxo: SpentUtxo) {
        if (log.isDebugEnabled) {
            log.debug("processSpentUtxoFromSubmitTx: adding: $spentUtxo")
        }
        spentUtxoSet.add(spentUtxo)

        //remove any spent utxos from the list of live utxos
        val newEntries: MutableList<Pair<String, Set<Utxo>>> = mutableListOf()
        liveUtxoMap.forEach { (address, utxoSet) ->
            val newUtxoList = utxoSet.filterNot { utxo -> utxo.hash == spentUtxo.hash && utxo.ix == spentUtxo.ix }
            if (newUtxoList.size < utxoSet.size) {
                newEntries.add(Pair(address, newUtxoList.toSet()))
            }
        }
        newEntries.forEach { entry -> liveUtxoMap[entry.first] = entry.second }
    }

    suspend fun processSpentUtxoFromBlock(spentUtxos: Set<SpentUtxo>) {
        utxoMutex.withLock {
            if (spentUtxoSet.removeAll(spentUtxos)) {
                if (log.isDebugEnabled) {
                    log.debug("processSpentUtxoFromBlock: removing: $spentUtxos")
                }
            }
        }
    }

    // Wait until 10 blocks have passed to make sure the blocks are immutable before removing them from live utxo map
    private val blockQueue = LinkedHashMap<Long, Set<CreatedUtxo>>(11)
    suspend fun processLiveUtxoFromBlock(blockNumber: Long, createdUtxos: Set<CreatedUtxo>) {

        blockQueue[blockNumber] = createdUtxos

        if (blockQueue.size > 10) {
            val oldestBlockNumber = blockQueue.keys.minOf { it }
            blockQueue.remove(oldestBlockNumber)?.let { immutableUtxos ->
                // now that these utxos are locked on the chain, we can remove them from our "live" list.
                utxoMutex.withLock {
                    if (log.isDebugEnabled) {
                        log.debug("processLiveUtxoFromBlock: $oldestBlockNumber, liveUtxoMap.size: ${liveUtxoMap.size}")
                    }
                    immutableUtxos.forEach { immutableUtxo ->
                        if (liveUtxoMap.containsKey(immutableUtxo.address)) {
                            val utxoSet = liveUtxoMap[immutableUtxo.address]!!
                            val newUtxoSet =
                                utxoSet.filterNot { utxo -> utxo.hash == immutableUtxo.hash && utxo.ix == immutableUtxo.ix }
                                    .toSet()
                            if (newUtxoSet.isEmpty()) {
                                liveUtxoMap.remove(immutableUtxo.address)?.let {
                                    if (log.isDebugEnabled) {
                                        log.debug("processLiveUtxoFromBlock: address: ${immutableUtxo.address}, removing: $it")
                                    }
                                }
                            } else {
                                liveUtxoMap.put(immutableUtxo.address, newUtxoSet)?.let {
                                    if (log.isDebugEnabled) {
                                        log.debug("processLiveUtxoFromBlock: address: ${immutableUtxo.address}, removing: $it, saving: $newUtxoSet")
                                    }
                                }
                            }
                        }
                    }
                    if (log.isDebugEnabled) {
                        log.debug("processLiveUtxoFromBlock: done, liveUtxoMap.size: ${liveUtxoMap.size}")
                    }
                }
            }
        }
    }

    private fun processLiveUtxoFromSubmitTx(address: String, liveUtxo: Utxo) {
        val newUtxoSet = liveUtxoMap[address]?.toMutableSet()?.apply { add(liveUtxo) } ?: setOf(liveUtxo)
        if (log.isDebugEnabled) {
            log.debug("processLiveUtxoFromSubmitTx: address: $address, adding: $newUtxoSet")
        }
        liveUtxoMap[address] = newUtxoSet
    }

    private var lastPruneTime = Instant.now()
    private var lastWalletRefreshTime = Instant.now()

    fun commitBlocks(blocksToCommit: List<BlockFetchProtocol.LedgerBlock>, isTip: Boolean) = transaction {
//        if (!isTip) {
        //log.warn("starting commitBlocks() with ${blocksToCommit.size} blocks...")
//        }
        var rollbackTime = 0L
        var nativeAssetTime = 0L
        var nativeAssetCount = 0L
        var spendTime = 0L
        var createTime = 0L
        var blockFetchCreateTime = 0L
        var pruneTime = 0L
        measureTimeMillis {
            blocksToCommit.forEachIndexed { index, ledgerBlock ->
                ledgerBlock.apply {
                    if (index == 0) {
                        // Mark same block number as rolled back
                        rollbackTime += measureTimeMillis {
                            LedgerRepository.doRollback(blockNumber)
                        }
                    }

                    // Load any Native asset metadata
                    nativeAssetTime += measureTimeMillis {
                        LedgerRepository.upcertNativeAssets(nativeAssetsMetadata)
                    }
                    nativeAssetCount += nativeAssetsMetadata.size

                    // Insert unspent utxos
                    createTime += measureTimeMillis {
                        LedgerRepository.createUtxos(slotNumber, blockNumber, createdUtxos)
                    }

                    // Mark spent utxos as spent
                    spendTime += measureTimeMillis {
                        LedgerRepository.spendUtxos(slotNumber, blockNumber, spentUtxos)
                    }

                    blockFetchCreateTime += measureTimeMillis {
                        LedgerRepository.insertBlockFetch(
                            blockNumber = blockNumber,
                            slotNumber = slotNumber,
                            hash = hash,
                            prevHash = prevHash
                        )
                    }

                    runBlocking {
                        processLiveUtxoFromBlock(blockNumber, createdUtxos)
                        processSpentUtxoFromBlock(spentUtxos)
                    }
                }
            }

            // Prune any old spent utxos we don't need any longer older than 30 minutes
            pruneTime = measureTimeMillis {
                val now = Instant.now()
                if (lastPruneTime.isBefore(now.minus(Duration.ofMinutes(1)))) {
                    LedgerRepository.pruneSpent(beforeSlot = cardanoUtils.getCurrentSlot() - 1800L)
                    lastPruneTime = now
                }
            }
        }.also { totalTime ->
            if (totalTime > 1000L) {
                log.warn("commitBlocks() total: ${totalTime}ms, rollback: ${rollbackTime}ms, nativeAsset: ${nativeAssetTime}ms, create: ${createTime}ms, blockFetchCreate: ${blockFetchCreateTime}ms, spend: ${spendTime}ms, prune: ${pruneTime}ms")
            }
            val blockNumberFirst = blocksToCommit.first().blockNumber
            val blockNumberLast = blocksToCommit.last().blockNumber
            if (blockNumberFirst == blockNumberLast) {
                log.info(
                    "BlckFetch: Saved block: $blockNumberLast of ${ChainSyncProtocol.tipBlockNumber}, %.2f%% synced".format(
                        blockNumberLast.toDouble() / ChainSyncProtocol.tipBlockNumber * 100.0
                    )
                )
            } else {
                log.info(
                    "BlckFetch: Saved block(s): $blockNumberFirst-$blockNumberLast of ${ChainSyncProtocol.tipBlockNumber}, %.2f%% synced".format(
                        blockNumberLast.toDouble() / ChainSyncProtocol.tipBlockNumber * 100.0
                    )
                )
            }

            // check for block rollbacks
            // If we rolled back, re-submit any transactions that aren't in this block
            if (isTip || blockRollbackCache.getIfPresent(blockNumberLast) != null) {
                checkBlockRollbacks(blocksToCommit.last())
            }

            val now = Instant.now()
            if (isTip || lastWalletRefreshTime.isBefore(now.minusSeconds(300))) {
                runBlocking {
                    refreshWalletChannel.emit(blockNumberLast)
                }
                lastWalletRefreshTime = now
            }
        }
    }

    private fun checkBlockRollbacks(latestBlock: BlockFetchProtocol.LedgerBlock) {
        // See if we're overwriting an existing block due to a rollback
        blockRollbackCache.getIfPresent(latestBlock.blockNumber)?.let { rolledBackBlockTransactionList ->
            runBlocking {
                handleBlockRollback(rolledBackBlockTransactionList, latestBlock)
            }
        }
        blockRollbackCache.put(latestBlock.blockNumber, latestBlock.transactionIdsInBlock)

        // Remove any blocks that were pruned due to this rollback.
        var blockNumber = latestBlock.blockNumber + 1
        var rolledBackBlockTransactionIds = blockRollbackCache.getIfPresent(blockNumber)
        while (rolledBackBlockTransactionIds != null) {
            blockRollbackCache.invalidate(blockNumber)
            blockNumber++
            rolledBackBlockTransactionIds = blockRollbackCache.getIfPresent(blockNumber)
        }
    }

    private suspend fun handleBlockRollback(
        rolledBackBlockTransactionList: List<String>,
        latestBlock: BlockFetchProtocol.LedgerBlock
    ) {
        TransactionCache.withLock {
            // get the first transactionId in the rolled-back block that isn't in the new block
            rolledBackBlockTransactionList.find { transactionId -> latestBlock.transactionIdsInBlock.find { it == transactionId } == null }
                ?.let { firstTransactionIdNotInBlock ->
                    val keys = TransactionCache.keys
                    val startIndex = keys.indexOfFirst { it == firstTransactionIdNotInBlock }.let { index ->
                        // try to start 20 transactions before we need to
                        if (index < 0) {
                            index
                        } else if (index - 20 < 0) {
                            0
                        } else {
                            index - 20
                        }
                    }
                    val lastIndex = keys.size - 1
                    if (startIndex > -1) {
                        val defaultNode = nodeRepository.findDefault() ?: throw IOException("Default node not found!")
                        val defaultHost = hostRepository.findByIdOrNull(defaultNode.hostId)
                            ?: throw IOException("Default Host not found!")
                        val defaultHostConnection = HostConnection(defaultHost, defaultNode)
                        val genesisFile = fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)
                            ?: throw IOException("Shelley genesis filenot found!")
                        val genesis = shelleyGenesisAdapter.fromJson(genesisFile.content)!!
                        val magicString = if (genesis.networkId.equals("testnet", ignoreCase = true)) {
                            "--testnet-magic ${genesis.networkMagic}"
                        } else {
                            "--mainnet"
                        }

                        keys.forEachIndexed { index, transactionId ->
                            if (index >= startIndex) {
                                TransactionCache.get(transactionId)?.let { txSigned ->
                                    try {
                                        // write the transaction to file
                                        defaultHostConnection.commandWriteFile("/tmp/transaction.txsigned", txSigned)
                                        // 2. Submit the transaction
                                        defaultHostConnection.command("${defaultHost.cardanoCliPath} transaction submit --tx-file /tmp/transaction.txsigned $magicString")
                                        log.warn("Re-Submit txid to mempool due to rollback: $transactionId, $index/$lastIndex")
                                    } catch (e: Throwable) {
                                        if (index % 10 == 0 || index == lastIndex) {
                                            log.info("Re-Submit txid to mempool exists already: $transactionId, $index/$lastIndex")
                                        }
                                        Unit
                                    }
                                }
                            }
                        }
                    }
                } ?: run {
                log.info("No transactions found to re-submit due to rollback.")
            }
        }
    }
}