package com.swiftmako.jormanager.repositories

import com.swiftmako.jormanager.entities.LedgerAddress
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
class LedgerDao @Autowired constructor(private val ledgerRepository: LedgerRepository) {

    @Transactional
    @Modifying
    fun upcertNativeAssets(nativeAssetsMetadata: Set<NativeAssetMetadata>) {
        nativeAssetsMetadata.forEach { nativeAssetMetadata ->
            ledgerRepository.getLedgerAssetByPolicyAndName(
                policy = nativeAssetMetadata.assetPolicy,
                name = nativeAssetMetadata.assetName
            )?.let { ledgerAsset ->
                // Do update
                ledgerRepository.updateLedgerAsset(
                    ledgerAsset.id!!,
                    nativeAssetMetadata.assetPolicy,
                    nativeAssetMetadata.assetName,
                    nativeAssetMetadata.metadataImage,
                    nativeAssetMetadata.metadataDescription,
                )
            } ?: run {
                // Do insert
                ledgerRepository.insertLedgerAsset(
                    nativeAssetMetadata.assetPolicy,
                    nativeAssetMetadata.assetName,
                    nativeAssetMetadata.metadataImage,
                    nativeAssetMetadata.metadataDescription,
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

            val ledgerUtxoTableId = ledgerRepository.insertLedgerUtxo(
                ledgerTableId,
                createdUtxo.hash,
                createdUtxo.ix.toInt(),
                createdUtxo.lovelace.toString(),
                blockNumber,
                slotNumber,
                null,
                null,
            )

            createdUtxo.nativeAssets.forEach { nativeAsset ->
                val ledgerAssetTableId =
                    ledgerRepository.getLedgerAssetByPolicyAndName(nativeAsset.policy, nativeAsset.name)?.id ?: run {
                        ledgerRepository.insertLedgerAsset(
                            nativeAsset.policy,
                            nativeAsset.name,
                            "",
                            null,
                        )
                    }

                ledgerRepository.insertLedgerUtxoAsset(
                    ledgerUtxoTableId,
                    ledgerAssetTableId,
                    nativeAsset.amount.toString()
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