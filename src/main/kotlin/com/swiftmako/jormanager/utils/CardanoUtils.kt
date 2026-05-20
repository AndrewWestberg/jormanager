package com.swiftmako.jormanager.utils

import com.google.iot.cbor.CborMap
import com.squareup.moshi.JsonAdapter
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.GenesisByron
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.repositories.FileRepository
import com.swiftmako.jormanager.repositories.NodeRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.io.IOException
import java.time.Instant

@Component("cardanoUtils")
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
@Lazy(false)
class CardanoUtils
    @Autowired
    constructor(
        private val fileRepository: FileRepository,
        private val nodeRepository: NodeRepository,
        private val byronGenesisAdapter: JsonAdapter<GenesisByron>,
        private val shelleyGenesisAdapter: JsonAdapter<GenesisShelley>,
    ) {
        private val log by lazy { LoggerFactory.getLogger("CardanoUtils") }

        fun calculateTransactionId(txBody: CborMap): String {
            val txBodyCborBytes = txBody.toCborByteArray()
//    println("txBodyCborBytes: ${txBodyCborBytes.toHexString()}")
            return Blake2b.hash256(txBodyCborBytes).toHexString()
        }

        private val genesisStartTimeSec by lazy {
            Instant.parse(getShelleyGenesis().systemStart).epochSecond
        }

        private lateinit var shelleyGenesis: GenesisShelley

        private fun getShelleyGenesis(): GenesisShelley {
            if (!::shelleyGenesis.isInitialized) {
                val defaultNode = nodeRepository.findDefault()!!

                val genesisFile =
                    fileRepository.findByIdOrNull(defaultNode.genesisShelleyFileId)
                        ?: throw IOException("ShelleyGenesis file for default node not found!")
                shelleyGenesis = shelleyGenesisAdapter.fromJson(genesisFile.content)!!
            }

            return shelleyGenesis
        }

        private lateinit var byronGenesis: GenesisByron

        private fun getByronGenesis(): GenesisByron {
            if (!::byronGenesis.isInitialized) {
                val defaultNode = nodeRepository.findDefault()!!

                val genesisFile =
                    fileRepository.findByIdOrNull((defaultNode.genesisByronFileId))
                        ?: throw IOException("ByronGenesis file for default node not found!")
                byronGenesis = byronGenesisAdapter.fromJson(genesisFile.content)!!
            }

            return byronGenesis
        }

        fun getCurrentSlot(): Long = getSlotAtInstant(Instant.now())

        private fun getSlotAtInstant(now: Instant): Long {
            val byronToShelleyEpochs = CardanoNetworkEpochs.byronToShelleyEpochs(getShelleyGenesis())
            val transTimeEnd = genesisStartTimeSec + (byronToShelleyEpochs * getShelleyGenesis().epochLength)
            val byronSlots = (genesisStartTimeSec - getByronGenesis().startTime) / 20L
            val transSlots = (byronToShelleyEpochs * getShelleyGenesis().epochLength) / 20L
            val currentTimeSec = now.epochSecond
            return byronSlots + transSlots + ((currentTimeSec - transTimeEnd) / getShelleyGenesis().slotLength)
        }
    }
