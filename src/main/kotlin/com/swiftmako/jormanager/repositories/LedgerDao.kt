package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.model.CreatedUtxo
import com.swiftmako.jormanager.model.NativeAssetMetadata
import com.swiftmako.jormanager.model.SpentUtxo
import com.swiftmako.jormanager.nodeclient.protocols.blockfetch.BlockFetchProtocol
import com.swiftmako.jormanager.nodeclient.protocols.chainsync.ChainSyncProtocol
import com.swiftmako.jormanager.utils.CardanoUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
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
                        blockFetchRepository.insertBlockFetch(
                            id = ledgerRepository.nextHibernateSeqVal(),
                            blockNumber = blockNumber,
                            slotNumber = slotNumber,
                            hash = hash,
                            prevHash = prevHash
                        )
                    }
                }
            }

            // Prune any old spent utxos we don't need any longer older than 30 minutes
            pruneTime = measureTimeMillis {
                ledgerRepository.pruneSpent(beforeSlot = cardanoUtils.getCurrentSlot() - 1800L)
            }
        }.also { totalTime ->
            if (isTip && totalTime > 500L) {
                log.warn("commitBlocks() total: ${totalTime}ms, rollback: ${rollbackTime}ms, nativeAsset: ${nativeAssetTime}ms, create: ${createTime}ms, blockFetchCreate: ${blockFetchCreateTime}ms, spend: ${spendTime}ms, prune: ${pruneTime}ms")
            }
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
                    description = nativeAssetMetadata.metadataDescription
                )
            } ?: run {
                // Do insert
                ledgerAssetRepository.insertLedgerAsset(
                    id = ledgerRepository.nextHibernateSeqVal(),
                    policy = nativeAssetMetadata.assetPolicy,
                    name = nativeAssetMetadata.assetName,
                    image = nativeAssetMetadata.metadataImage,
                    description = nativeAssetMetadata.metadataDescription,
                )
            }
        }
    }

    fun createUtxos(slotNumber: Long, blockNumber: Long, createdUtxos: Set<CreatedUtxo>) {
        createdUtxos.forEach { createdUtxo ->
            val ledgerTableId = ledgerRepository.getByAddress(createdUtxo.address)?.id
                ?: run {
                    val id = ledgerRepository.nextHibernateSeqVal()
                    ledgerRepository.insertLedgerAddress(
                        id = id,
                        address = createdUtxo.address,
                        stakeAddress = createdUtxo.stakeAddress
                    )
                    id
                }

            val ledgerUtxoTableId = ledgerRepository.nextHibernateSeqVal()
            ledgerUtxoRepository.insertLedgerUtxo(
                id = ledgerUtxoTableId,
                ledgerId = ledgerTableId,
                txId = createdUtxo.hash,
                txIx = createdUtxo.ix.toInt(),
                lovelace = createdUtxo.lovelace.toString(),
                blockCreated = blockNumber,
                slotCreated = slotNumber,
                blockSpent = null,
                slotSpent = null,
            )

            createdUtxo.nativeAssets.forEach { nativeAsset ->
                val ledgerAssetTableId =
                    ledgerRepository.getLedgerAssetByPolicyAndName(nativeAsset.policy, nativeAsset.name)?.id ?: run {
                        val id = ledgerRepository.nextHibernateSeqVal()
                        ledgerAssetRepository.insertLedgerAsset(
                            id = id,
                            policy = nativeAsset.policy,
                            name = nativeAsset.name,
                            image = "",
                            description = null,
                        )
                        id
                    }

                ledgerUtxoAssetRepository.insertLedgerUtxoAsset(
                    id = ledgerRepository.nextHibernateSeqVal(),
                    ledgerUtxoId = ledgerUtxoTableId,
                    ledgerAssetId = ledgerAssetTableId,
                    amount = nativeAsset.amount.toString(),
                )
            }
        }
    }

    fun spendUtxos(slotNumber: Long, blockNumber: Long, spentUtxos: Set<SpentUtxo>) {
        spentUtxos.forEach { spentUtxo ->
            ledgerRepository.spendUtxo(spentUtxo.hash, spentUtxo.ix.toInt(), blockNumber, slotNumber)
        }
    }
}