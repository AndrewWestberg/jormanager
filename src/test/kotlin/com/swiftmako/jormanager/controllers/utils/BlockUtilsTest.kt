package com.swiftmako.jormanager.controllers.utils

import com.google.common.truth.Truth.assertThat
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.*
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

    private val nodeStats = AtomicReference(
        NodeStats(
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
            epochLength = 432000,
        )
    )

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

    private val nodeStatsTest = AtomicReference(
        NodeStats(
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
            epochLength = 432000,
        )
    )


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

    @Test
    fun mkInputVRFTest() {
        // {"slot": SlotNo 852974, "praosEpochNonce": Nonce "7427c5045f75518be00b21c76e44660d3cc7616ee34e91f284bb19468d3f45c2"}
        //Jun 16 12:50:14 brainy vpool-node[2955488]: {"rho'": InputVRF {unInputVRF = "0ab5f206b2a97c3c328d8600dab04c2a2a13c670114d07742e808ee4778307bf"}}
        val target = BlockUtils(nodeStats, "/usr/local/lib/libsodium.so")

        val result = target.mkInputVRF(
            852974L,
            "7427c5045f75518be00b21c76e44660d3cc7616ee34e91f284bb19468d3f45c2".hexToByteArray()
        ).toHexString()
        assertThat(result).isEqualTo("0ab5f206b2a97c3c328d8600dab04c2a2a13c670114d07742e808ee4778307bf")
    }

    @Test
    fun vrfLeaderValueTest() {
        //Jun 17 13:18:53 brainy vpool-node[176000]: {"slot": SlotNo 941093, "praosEpochNonce": Nonce "6161ef68d404ab64f8f606a76a233d1868994a802ffe06dec32d6f13bbf2bde1"}
        //Jun 17 13:18:53 brainy vpool-node[176000]: {"rho'": InputVRF {unInputVRF = "52027a5b60de0f727093d0b370e8706f371991ab956a8a4ac6bef1917bc2e312"}}
        //Jun 17 13:18:53 brainy vpool-node[176000]: rho "46b9270749c6c5a8242e1e017e0c18cb137a5f2ba32263106000e6adea6cfa0f9d83c234a91dbd03d573e19bf24fd8faa74261a855feab64f45f52781f24ccba"
        //Jun 17 13:18:53 brainy vpool-node[176000]: foo 104851203780143120793757430757232003657789403622137095777340581482187202740116

        // vpool vrf skey
        val poolVrfSkey =
            "fd8b57ee76d8d9938a0fb5b61e63626e1604d900e84d0970d24bfd69b60fea98e7b493d575baa41450f7c8c82a462d6c7a5179d143536cdd1a50e8200e6db5d5".hexToByteArray()

        val target = BlockUtils(nodeStats, "/usr/local/lib/libsodium.so")


        val result = target.mkInputVRF(
            941093L,
            "6161ef68d404ab64f8f606a76a233d1868994a802ffe06dec32d6f13bbf2bde1".hexToByteArray()
        ).toHexString()
        assertThat(result).isEqualTo("52027a5b60de0f727093d0b370e8706f371991ab956a8a4ac6bef1917bc2e312")

        val rho = target.vrfEvalCertified(result.hexToByteArray(), poolVrfSkey).toHexString()
        assertThat(rho).isEqualTo("46b9270749c6c5a8242e1e017e0c18cb137a5f2ba32263106000e6adea6cfa0f9d83c234a91dbd03d573e19bf24fd8faa74261a855feab64f45f52781f24ccba")

        val foo = target.vrfLeaderValue(rho.hexToByteArray()).toHexString()

//        val expected =
//            BigInteger("104851203780143120793757430757232003657789403622137095777340581482187202740116").toByteArray()
//                .toHexString()
        assertThat(foo).isEqualTo("e7cfada3aed09d74df7cbbfa633389da58943e3fbe76da3a4eabde9f56dfbf94")
    }
}