package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.LedgerAddress
import com.swiftmako.jormanager.entities.LedgerAsset
import com.swiftmako.jormanager.entities.LedgerUtxo
import com.swiftmako.jormanager.entities.LedgerUtxoAsset
import com.swiftmako.jormanager.model.CreatedUtxo
import com.swiftmako.jormanager.model.NativeAssetMetadata
import com.swiftmako.jormanager.model.SpentUtxo
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Scope
import org.springframework.data.jpa.repository.Modifying
import org.springframework.stereotype.Component
import javax.transaction.Transactional

@Component
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
class LedgerDao @Autowired constructor(
    private val ledgerRepository: LedgerRepository,
    private val ledgerUtxoRepository: LedgerUtxoRepository,
    private val ledgerAssetRepository: LedgerAssetRepository,
    private val ledgerUtxoAssetRepository: LedgerUtxoAssetRepository,
) {

    @Transactional
    @Modifying
    fun upcertNativeAssets(nativeAssetsMetadata: Set<NativeAssetMetadata>) {
        nativeAssetsMetadata.forEach { nativeAssetMetadata ->
            ledgerRepository.getLedgerAssetByPolicyAndName(
                policy = nativeAssetMetadata.assetPolicy,
                name = nativeAssetMetadata.assetName
            )?.let { ledgerAsset ->
                // Do update
                ledgerAssetRepository.save(
                    ledgerAsset.copy(
                        image = nativeAssetMetadata.metadataImage,
                        description = nativeAssetMetadata.metadataDescription
                    )
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

    @Transactional
    @Modifying
    fun createUtxos(slotNumber: Long, blockNumber: Long, createdUtxos: Set<CreatedUtxo>) {
        createdUtxos.forEach { createdUtxo ->
            val ledgerTableId = ledgerRepository.getByAddress(createdUtxo.address)?.id
                ?: ledgerRepository.save(
                    LedgerAddress(
                        address = createdUtxo.address,
                        stakeAddress = createdUtxo.stakeAddress
                    )
                ).id!!

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

            createdUtxo.nativeAssets.forEach { nativeAsset ->
                val ledgerAssetTableId =
                    ledgerRepository.getLedgerAssetByPolicyAndName(nativeAsset.policy, nativeAsset.name)?.id ?: run {
                        ledgerAssetRepository.save(
                            LedgerAsset(
                                policy = nativeAsset.policy,
                                name = nativeAsset.name,
                                image = "",
                                description = null,
                            )
                        ).id!!
                    }

                ledgerUtxoAssetRepository.save(
                    LedgerUtxoAsset(
                        ledgerUtxoId = ledgerUtxoTableId,
                        ledgerAssetId = ledgerAssetTableId,
                        amount = nativeAsset.amount.toString(),
                    )
                )
            }
        }
    }

    @Transactional
    @Modifying
    fun spendUtxos(slotNumber: Long, blockNumber: Long, spentUtxos: Set<SpentUtxo>) {
        spentUtxos.forEach { spentUtxo ->
            ledgerRepository.spendUtxo(spentUtxo.hash, spentUtxo.ix.toInt(), blockNumber, slotNumber)
        }
    }


}