package com.swiftmako.jormanager.controllers.utils

import com.swiftmako.jormanager.model.Genesis
import com.swiftmako.jormanager.model.GenesisByron
import com.swiftmako.jormanager.model.NodeStats
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

@Component
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
class BlockUtils @Autowired constructor(
        @Qualifier("latestNodeStats") private val latestNodeStats: AtomicReference<NodeStats>,
) {

    fun getEpoch(byron: GenesisByron, shelley: Genesis): Long {
        val currentTimeSec = System.currentTimeMillis() / 1000L
        val byronEpochLength = 10L * byron.protocolConsts.k
        val byronSlotLength = byron.blockVersionData.slotDuration / 1000L
        val shelleyTransitionEpoch = getShelleyTransitionEpoch(byron, shelley)
        val byronEndTime = byron.startTime + (shelleyTransitionEpoch * byronEpochLength * byronSlotLength)
        return shelleyTransitionEpoch + ((currentTimeSec - byronEndTime) / shelley.slotLength / shelley.epochLength)
    }

    fun getEpochAndSlot(byron: GenesisByron, shelley: Genesis, absoluteSlot: Long): Pair<Long, Long> {
        val shelleyTransitionEpoch = getShelleyTransitionEpoch(byron, shelley)
        if (shelleyTransitionEpoch == -1L) {
            return Pair(-1L, -1L)
        }
        val byronEpochLength = 10L * byron.protocolConsts.k
        val byronSlots = byronEpochLength * shelleyTransitionEpoch
        val shelleySlots = absoluteSlot - byronSlots
        val shelleyEpoch = shelleyTransitionEpoch + (shelleySlots / shelley.epochLength)
        val shelleySlotInEpoch = shelleySlots % shelley.epochLength
        return Pair(shelleyEpoch, shelleySlotInEpoch)
    }

    fun getShelleyTransitionEpoch(byron: GenesisByron, shelley: Genesis): Long {
        latestNodeStats.get()?.let { nodeStats ->
            if (nodeStats.epoch == null || nodeStats.slot == null || nodeStats.slotInEpoch == null) {
                return -1L
            }
            val byronEpochLength = 10L * byron.protocolConsts.k
            var calcSlot = 0L
            var byronEpochs = nodeStats.epoch
            val slotInEpoch = nodeStats.slotInEpoch
            val slot = nodeStats.slot
            var shelleyEpochs = 0L
            while (byronEpochs >= 0L) {
                calcSlot = (byronEpochs * byronEpochLength) + (shelleyEpochs * shelley.epochLength) + slotInEpoch
                if (calcSlot == slot) {
                    break
                }
                byronEpochs--
                shelleyEpochs++
            }

            if (calcSlot != slot || shelleyEpochs == 0L) {
                return -1L
            }

            return byronEpochs
        }
        return -1L
    }
}