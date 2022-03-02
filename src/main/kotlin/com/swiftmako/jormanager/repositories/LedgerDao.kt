package com.swiftmako.jormanager.repositories

import com.github.benmanes.caffeine.cache.Caffeine
import com.swiftmako.jormanager.entities.*
import com.swiftmako.jormanager.model.*
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.BlockFetchProtocol
import com.swiftmako.jormanager.nodeclient.protocols.chainsync.ChainSyncProtocol
import com.swiftmako.jormanager.utils.CardanoUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.math.BigInteger
import java.time.Duration
import javax.transaction.Transactional
import kotlin.system.measureTimeMillis

@Component
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
class LedgerDao @Autowired constructor(
    private val cardanoUtils: CardanoUtils,
    private val blockFetchRepository: BlockFetchRepository,
    private val ledgerRepository: LedgerRepository,
    private val ledgerUtxoRepository: LedgerUtxoRepository,
    private val ledgerAssetRepository: LedgerAssetRepository,
    private val ledgerUtxoAssetRepository: LedgerUtxoAssetRepository,
) {
    private val log by lazy { LoggerFactory.getLogger("LedgerDao") }

    @Transactional
    fun queryUtxos(address: String): List<Utxo> {
        return ledgerRepository.getByAddress(address)?.let { ledgerAddress ->
            ledgerAddress.ledgerUtxos.map { ledgerUtxo ->
                Utxo(
                    hash = ledgerUtxo.txId,
                    ix = ledgerUtxo.txIx.toLong(),
                    lovelace = BigInteger(ledgerUtxo.lovelace),
                    nativeAssets = ledgerUtxo.ledgerUtxoAssets.map { ledgerUtxoAsset ->
                        NativeAsset(
                            name = ledgerUtxoAsset.ledgerAsset.name,
                            policy = ledgerUtxoAsset.ledgerAsset.policy,
                            amount = BigInteger(ledgerUtxoAsset.amount)
                        )
                    }
                )
            }
        } ?: emptyList()
    }

    @Transactional
    fun commitBlocks(blocksToCommit: List<BlockFetchProtocol.LedgerBlock>, isTip: Boolean) {
//        if (!isTip) {
        //log.warn("starting commitBlocks() with ${blocksToCommit.size} blocks...")
//        }
        var rollbackTime = 0L
        var nativeAssetTime = 0L
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
                            ledgerRepository.doRollbackDelete(blockNumber)
                            ledgerRepository.doRollbackUpdate(blockNumber)
                        }
                    }

                    // Load any Native asset metadata
                    nativeAssetTime += measureTimeMillis {
                        upcertNativeAssets(nativeAssetsMetadata)
                    }

                    // Insert unspent utxos
                    createTime += measureTimeMillis {
                        createUtxos(slotNumber, blockNumber, createdUtxos)
                    }

                    // Mark spent utxos as spent
                    spendTime += measureTimeMillis {
                        spendUtxos(slotNumber, blockNumber, spentUtxos)
                    }

                    // Mark this block as fetched
                    if (index == 0) {
                        rollbackTime += measureTimeMillis {
                            blockFetchRepository.doRollbackDelete(blockNumber)
                        }
                    }
                    blockFetchCreateTime += measureTimeMillis {
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
            }

            // Prune any old spent utxos we don't need any longer older than 30 minutes
            pruneTime = measureTimeMillis {
                ledgerRepository.pruneSpent(beforeSlot = cardanoUtils.getCurrentSlot() - 1800L)
            }
        }.also { totalTime ->
            //if (isTip && totalTime > 500L) {
            log.warn("commitBlocks() total: ${totalTime}ms, rollback: ${rollbackTime}ms, nativeAsset: ${nativeAssetTime}ms, create: ${createTime}ms, blockFetchCreate: ${blockFetchCreateTime}ms, spend: ${spendTime}ms, prune: ${pruneTime}ms")
            //}
            log.info(
                "BlockFetch: Saved block: ${blocksToCommit.first().blockNumber}..${blocksToCommit.last().blockNumber} of ${ChainSyncProtocol.tipBlockNumber} - %.2f%% synced".format(
                    blocksToCommit.last().blockNumber.toDouble() / ChainSyncProtocol.tipBlockNumber * 100.0
                )
            )
        }
    }

    fun upcertNativeAssets(nativeAssetsMetadata: Set<NativeAssetMetadata>) {
        nativeAssetsMetadata.forEach { nativeAssetMetadata ->
            ledgerRepository.getLedgerAssetByPolicyAndName(
                policy = nativeAssetMetadata.assetPolicy,
                name = nativeAssetMetadata.assetName
            )?.let { ledgerAsset ->
                // Do update
                ledgerAssetRepository.updateImageAndDescription(
                    id = ledgerAsset.id!!,
                    image = nativeAssetMetadata.metadataImage,
                    description = nativeAssetMetadata.metadataDescription,
                )
            } ?: run {
                // Do insert
                ledgerAssetRepository.save(
                    LedgerAsset(
                        policy = nativeAssetMetadata.assetPolicy,
                        name = nativeAssetMetadata.assetName,
                        image = nativeAssetMetadata.metadataImage,
                        description = nativeAssetMetadata.metadataDescription,
                    )
                )
            }
        }
    }

    private val ledgerIdCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(10))
        .maximumSize(30_000L)
        .build<String, Long?> { address ->
            ledgerRepository.getIdByAddress(address)
        }

    private val ledgerAssetIdCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(10))
        .maximumSize(30_000L)
        .build<Pair<String, String>, Long?> { pair ->
            ledgerRepository.getLedgerAssetByPolicyAndName(pair.first, pair.second)?.id
        }

    fun createUtxos(slotNumber: Long, blockNumber: Long, createdUtxos: Set<CreatedUtxo>) {
        var ledgerTime = 0L
        var ledgerQueryTime = 0L
        var ledgerInsertTime = 0L
        var ledgerUtxoTime = 0L
        var ledgerAssetTime = 0L
        var hit = 0L
        var miss = 0L
        createdUtxos.forEach { createdUtxo ->
            val start = System.currentTimeMillis()
            var ledgerTableId = ledgerIdCache[createdUtxo.address]
            ledgerQueryTime += (System.currentTimeMillis() - start)
            if (ledgerTableId == null) {
                miss++
                val start3 = System.currentTimeMillis()
                ledgerTableId = ledgerRepository.save(
                    LedgerAddress(
                        address = createdUtxo.address,
                        stakeAddress = createdUtxo.stakeAddress
                    )
                ).id!!
                ledgerIdCache.put(createdUtxo.address, ledgerTableId)
                ledgerInsertTime += (System.currentTimeMillis() - start3)
            } else {
                hit++
            }
            ledgerTime += (System.currentTimeMillis() - start)

            val start1 = System.currentTimeMillis()
            val ledgerUtxoTableId = ledgerUtxoRepository.save(
                LedgerUtxo(
                    ledgerId = ledgerTableId,
                    txId = createdUtxo.hash,
                    txIx = createdUtxo.ix.toInt(),
                    lovelace = createdUtxo.lovelace.toString(),
                    blockCreated = blockNumber,
                    slotCreated = slotNumber,
                    blockSpent = null,
                    slotSpent = null,
                )
            ).id!!
            ledgerUtxoTime += (System.currentTimeMillis() - start1)

            val start2 = System.currentTimeMillis()
            createdUtxo.nativeAssets.forEach { nativeAsset ->
                val ledgerAssetTableId = ledgerAssetIdCache[Pair(nativeAsset.policy, nativeAsset.name)]
                    ?: run {
                        val id = ledgerAssetRepository.save(
                            LedgerAsset(
                                policy = nativeAsset.policy,
                                name = nativeAsset.name,
                                image = "",
                                description = null,
                            )
                        ).id!!
                        ledgerAssetIdCache.put(Pair(nativeAsset.policy, nativeAsset.name), id)
                        id
                    }
                ledgerUtxoAssetRepository.save(
                    LedgerUtxoAsset(
                        ledgerUtxoId = ledgerUtxoTableId,
                        ledgerAssetId = ledgerAssetTableId,
                        amount = nativeAsset.amount.toString()
                    )
                )
            }
            ledgerAssetTime += (System.currentTimeMillis() - start2)
        }
        if (ledgerTime > 1000L || ledgerUtxoTime > 1000L || ledgerAssetTime > 1000L) {
            log.warn("slowBlock: $blockNumber: ledgerTime: ${ledgerTime}ms, query: ${ledgerQueryTime}ms, hit/miss: ${hit}/${miss}, insert: ${ledgerInsertTime}ms, ledgerUtxoTime: ${ledgerUtxoTime}ms, ledgerAssetTime: ${ledgerAssetTime}ms")
        }
    }

    fun spendUtxos(slotNumber: Long, blockNumber: Long, spentUtxos: Set<SpentUtxo>) {
        spentUtxos.forEach { spentUtxo ->
            ledgerRepository.spendUtxo(spentUtxo.hash, spentUtxo.ix.toInt(), blockNumber, slotNumber)
        }
    }
}