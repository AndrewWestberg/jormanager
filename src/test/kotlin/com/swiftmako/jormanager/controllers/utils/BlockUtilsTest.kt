package com.swiftmako.jormanager.controllers.utils

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.model.BlockVersionData
import com.swiftmako.jormanager.model.GenesisShelley
import com.swiftmako.jormanager.model.GenesisByron
import com.swiftmako.jormanager.model.NodeStats
import com.swiftmako.jormanager.model.ProtocolConsts
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicReference

class BlockUtilsTest {

    private val byron = GenesisByron(
            startTime = 1506203091L,
            protocolConsts = ProtocolConsts(
                    k = 2160L
            ),
            blockVersionData = BlockVersionData(
                    slotDuration = 20000L
            )
    )

    private val shelley = GenesisShelley(
            activeSlotsCoeff = 0.05,
            networkId = "Mainnet",
            networkMagic = 764824073L,
            slotLength = 1L,
            epochLength = 432000L,
            slotsPerKESPeriod = 129600L,
            systemStart = "2017-09-23T21:44:51Z",
            maxKESEvolutions = 62L
    )

    private val nodeStats = AtomicReference(NodeStats(
            isDefault = true,
            timestamp = 0L,
            nodeName = "",
            color = "",
            peers = 2,
            blockHeight = null,
            remainingKESPeriods = null,
            epoch = 215L,
            slot = 7938583L,
            slotInEpoch = 421783L,
            txsProcessed = 15L,
            incomingPeers = 15,
    ))

    private val byronTest = GenesisByron(
            startTime = 1563999616L,
            protocolConsts = ProtocolConsts(
                    k = 2160L
            ),
            blockVersionData = BlockVersionData(
                    slotDuration = 20000L
            )
    )

    private val shelleyTest = GenesisShelley(
            activeSlotsCoeff = 0.05,
            networkId = "Testnet",
            networkMagic = 1097911063L,
            slotLength = 1L,
            epochLength = 432000L,
            slotsPerKESPeriod = 129600L,
            systemStart = "2019-07-24T20:20:16Z",
            maxKESEvolutions = 62L
    )

    private val nodeStatsTest = AtomicReference(NodeStats(
            isDefault = true,
            timestamp = 0L,
            nodeName = "",
            color = "",
            peers = 2,
            blockHeight = null,
            remainingKESPeriods = null,
            epoch = 82L,
            slot = 5140657L,
            slotInEpoch = 86257L,
            txsProcessed = 15L,
            incomingPeers = 15,
    ))


    @Test
    fun `test getEpochAndSlot`() {
        val target = BlockUtils(nodeStats, "/usr/local/lib/libsodium.so")
//        val (epoch, slot) = target.getEpochAndSlot(byron, shelley, 7914957L)
//        println("Epoch: $epoch, Slot: $slot")
//        assertThat(epoch).isEqualTo(215L)
//        assertThat(slot).isEqualTo(398157L)
//
//        val (epoch1, slot1) = target.getEpochAndSlot(byron, shelley, 5792939L)
//        println("Epoch: $epoch1, Slot: $slot1")
//        assertThat(epoch1).isEqualTo(211L)
//        assertThat(slot1).isEqualTo(4139L)

        val (epoch2, slot2) = target.getEpochAndSlot(byron, shelley, 10540771)
        println("Epoch: $epoch2, Slot: $slot2")
    }

    @Test
    fun `test getShelleyTransitionEpoch`() {
        var target = BlockUtils(nodeStats, "/usr/local/lib/libsodium.so")
        val mainnetTransitionEpoch = target.getShelleyTransitionEpoch(byron, shelley)
        assertThat(mainnetTransitionEpoch).isEqualTo(208)

        target = BlockUtils(nodeStatsTest, "/usr/local/lib/libsodium.so")
        val testnetTransitionEpoch = target.getShelleyTransitionEpoch(byronTest, shelleyTest)
        assertThat(testnetTransitionEpoch).isEqualTo(74)
    }

    @Test
    fun testOverlaySlot() {
        val target = BlockUtils(nodeStats, "/usr/local/lib/libsodium.so")

        val firstSlotOfEpoch = 11404800L
        val testSlot = 11553190L
        val result = target.isOverlaySlot(firstSlotOfEpoch, testSlot, BigDecimal("0.56"))
        assertThat(result).isFalse()

        val firstSlotOfEpoch2 = 17020800L
        val testSlot2 = 17043333L
        val result2 = target.isOverlaySlot(firstSlotOfEpoch2, testSlot2, BigDecimal("0.32"))
        assertThat(result2).isFalse()
    }

}