package com.swiftmako.jormanager.repositories

import com.google.iot.cbor.*
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.CreatedUtxo
import com.swiftmako.jormanager.model.NativeAsset
import com.swiftmako.jormanager.model.SpentUtxo
import com.swiftmako.jormanager.model.Utxo
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.BlockFetchProtocol
import com.swiftmako.jormanager.nodeclient.protocols.chainsync.ChainSyncProtocol
import com.swiftmako.jormanager.utils.Bech32
import com.swiftmako.jormanager.utils.CardanoUtils
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
import org.springframework.stereotype.Component
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import kotlin.experimental.and
import kotlin.system.measureTimeMillis

@Component
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
class LedgerDao @Autowired constructor(
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

                    // Mark this block as fetched
                    if (index == 0) {
                        rollbackTime += measureTimeMillis {
                            LedgerRepository.doBlockFetchRollbackDelete(blockNumber)
                        }
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
            val blockNumber = blocksToCommit.last().blockNumber
            log.info(
                "BlckFetch: Saved block: $blockNumber of ${ChainSyncProtocol.tipBlockNumber}, %.2f%% synced".format(
                    blockNumber.toDouble() / ChainSyncProtocol.tipBlockNumber * 100.0
                )
            )
            val now = Instant.now()
            if (isTip || lastWalletRefreshTime.isBefore(now.minusSeconds(60))) {
                runBlocking {
                    refreshWalletChannel.emit(blockNumber)
                }
                lastWalletRefreshTime = now
            }
        }
    }
//
//    fun upcertNativeAssets(nativeAssetsMetadata: Set<NativeAssetMetadata>) {
//        nativeAssetsMetadata.forEach { nativeAssetMetadata ->
//            ledgerAssetIdCache[Pair(
//                nativeAssetMetadata.assetPolicy,
//                nativeAssetMetadata.assetName
//            )]?.let { ledgerAssetId ->
//                // Do update
//                ledgerAssetRepository.updateImageAndDescription(
//                    id = ledgerAssetId,
//                    image = nativeAssetMetadata.metadataImage,
//                    description = nativeAssetMetadata.metadataDescription,
//                )
//            } ?: run {
//                // Do insert
//                val id = ledgerAssetRepository.save(
//                    LedgerAsset(
//                        policy = nativeAssetMetadata.assetPolicy,
//                        name = nativeAssetMetadata.assetName,
//                        image = nativeAssetMetadata.metadataImage,
//                        description = nativeAssetMetadata.metadataDescription,
//                    )
//                ).id!!
//                ledgerAssetIdCache.put(Pair(nativeAssetMetadata.assetPolicy, nativeAssetMetadata.assetName), id)
//            }
//        }
//    }
//
//    private val ledgerIdCache = Caffeine.newBuilder()
//        .expireAfterWrite(Duration.ofMinutes(10))
//        .maximumSize(30_000L)
//        .build<String, Long?> { address ->
//            ledgerRepository.getIdByAddress(address)
//        }
//
//    private val ledgerAssetIdCache = Caffeine.newBuilder()
//        .expireAfterWrite(Duration.ofMinutes(10))
//        .maximumSize(30_000L)
//        .build<Pair<String, String>, Long?> { pair ->
//            ledgerRepository.getLedgerAssetByPolicyAndName(pair.first, pair.second)?.id
//        }
//
//    fun createUtxos(slotNumber: Long, blockNumber: Long, createdUtxos: Set<CreatedUtxo>) {
//        var ledgerTime = 0L
//        var ledgerQueryTime = 0L
//        var ledgerInsertTime = 0L
//        var ledgerUtxoTime = 0L
//        var ledgerAssetTime = 0L
//        var ledgerAssetCount = 0L
//        var hit = 0L
//        var miss = 0L
//        createdUtxos.forEach { createdUtxo ->
//            val start = System.currentTimeMillis()
//            var ledgerTableId = ledgerIdCache[createdUtxo.address]
//            ledgerQueryTime += (System.currentTimeMillis() - start)
//            if (ledgerTableId == null) {
//                miss++
//                val start3 = System.currentTimeMillis()
//                ledgerTableId = ledgerRepository.save(
//                    LedgerAddress(
//                        address = createdUtxo.address,
//                        stakeAddress = createdUtxo.stakeAddress
//                    )
//                ).id!!
//                ledgerIdCache.put(createdUtxo.address, ledgerTableId)
//                ledgerInsertTime += (System.currentTimeMillis() - start3)
//            } else {
//                hit++
//            }
//            ledgerTime += (System.currentTimeMillis() - start)
//
//            val start1 = System.currentTimeMillis()
//            val ledgerUtxoTableId = ledgerUtxoRepository.save(
//                LedgerUtxo(
//                    ledgerId = ledgerTableId,
//                    txId = createdUtxo.hash,
//                    txIx = createdUtxo.ix.toInt(),
//                    lovelace = createdUtxo.lovelace.toString(),
//                    blockCreated = blockNumber,
//                    slotCreated = slotNumber,
//                    blockSpent = null,
//                    slotSpent = null,
//                )
//            ).id!!
//            ledgerUtxoTime += (System.currentTimeMillis() - start1)
//
//            val start2 = System.currentTimeMillis()
//            createdUtxo.nativeAssets.forEach { nativeAsset ->
//                val ledgerAssetTableId = ledgerAssetIdCache[Pair(nativeAsset.policy, nativeAsset.name)]
//                    ?: run {
//                        val id = ledgerAssetRepository.save(
//                            LedgerAsset(
//                                policy = nativeAsset.policy,
//                                name = nativeAsset.name,
//                                image = "",
//                                description = null,
//                            )
//                        ).id!!
//                        ledgerAssetIdCache.put(Pair(nativeAsset.policy, nativeAsset.name), id)
//                        id
//                    }
//                ledgerUtxoAssetRepository.save(
//                    LedgerUtxoAsset(
//                        ledgerUtxoId = ledgerUtxoTableId,
//                        ledgerAssetId = ledgerAssetTableId,
//                        amount = nativeAsset.amount.toString()
//                    )
//                )
//            }
//            ledgerAssetTime += (System.currentTimeMillis() - start2)
//            ledgerAssetCount += createdUtxo.nativeAssets.size
//        }
//        if (ledgerTime > 1000L || ledgerUtxoTime > 1000L || ledgerAssetTime > 1000L) {
//            log.warn("complexBlock: $blockNumber: ledgerTime: ${ledgerTime}ms, query: ${ledgerQueryTime}ms, hit/miss: ${hit}/${miss}, insert: ${ledgerInsertTime}ms, ledgerUtxoTime: ${ledgerUtxoTime}ms, ledgerAssetTime: ${ledgerAssetTime}ms, assetCount: $ledgerAssetCount")
//        }
//    }
//
//    fun spendUtxos(slotNumber: Long, blockNumber: Long, spentUtxos: Set<SpentUtxo>) {
//        spentUtxos.forEach { spentUtxo ->
//            ledgerRepository.spendUtxo(spentUtxo.hash, spentUtxo.ix.toInt(), blockNumber, slotNumber)
//        }
//    }
}