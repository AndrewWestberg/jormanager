package com.swiftmako.jormanager.controllers.utils

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.model.BlockVersionData
import com.swiftmako.jormanager.model.Genesis
import com.swiftmako.jormanager.model.GenesisByron
import com.swiftmako.jormanager.model.NodeStats
import com.swiftmako.jormanager.model.ProtocolConsts
import org.junit.jupiter.api.Test
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

    private val shelley = Genesis(
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

    private val shelleyTest = Genesis(
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
    ))


    @Test
    fun `test getEpochAndSlot`() {
        val target = BlockUtils(nodeStats, "/usr/local/lib/libsodium.so")
        val (epoch, slot) = target.getEpochAndSlot(byron, shelley, 7914957L)
        println("Epoch: $epoch, Slot: $slot")
        assertThat(epoch).isEqualTo(215L)
        assertThat(slot).isEqualTo(398157L)

        val (epoch1, slot1) = target.getEpochAndSlot(byron, shelley, 5792939L)
        println("Epoch: $epoch1, Slot: $slot1")
        assertThat(epoch1).isEqualTo(211L)
        assertThat(slot1).isEqualTo(4139L)
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
}