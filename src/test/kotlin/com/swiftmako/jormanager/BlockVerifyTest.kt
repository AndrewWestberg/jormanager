package com.swiftmako.jormanager

import com.muquit.libsodiumjna.SodiumLibrary
import com.swiftmako.jormanager.controllers.utils.BlockUtils
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.utils.Blake2b
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BlockVerifyTest {
    companion object {
        private val LEADER_VRF_HEADER =
            ByteArray(1) {
                0x4C.toByte() // 'L'
            }
    }

    @Test
    fun testBlockVerify() {
        val libraryPath = "/usr/local/lib/libsodium.so"
        SodiumLibrary.setLibraryPath(libraryPath)

        // preprod block
        // https://preprod.cardanoscan.io/block/1864129
        val slot = 50670495L
//        // epoch nonce
        val eta0 = "f80b9ba7ea6efd2e2d6ec4bb6d83aed5caa2326bd02ab792755bcec46dc00b25".hexToByteArray()

        val vrfVkey = "e9fff58586a4a588f15f371b4d7bfc04344b6a281bce39a7ab81fe5b6751cbac".hexToByteArray()
        val blockVrf =
            "bdc34cb0cd1b05a66f571bae655e5ded79e05363d75474873ea293ef1c0929f3d80236fd6d88137ec601953c3c430b6c72f1593570c3bd94bba4f3ac788e2846".hexToByteArray()
        val blockVrfSignature =
            "e5cfc760a2c6fb8cbc73c9cfab3492a19aafc8e6a103cbfb3c9e72cd737a6aac5248fda1a49e61a1ee128e02265ad56a7fcaa75a0c7c9260d8dc29e434b9a646ba8f8a8dd613eedf0acc3e8a4d91c605".hexToByteArray()
        val blockBodyHash = "c4a1ce54bf726c66ea5a1384d5952f8c14491b9a36e22d3635fa448ab5e77a4f".hexToByteArray()

        // calculate the leader vrf from the block vrf
        val expectedLeaderVrf = "00000d9118cdc93f8640c71693ffe8f80796d07423691f00611e66f988672d24"
        val leaderVrf = Blake2b.hash256(LEADER_VRF_HEADER + blockVrf).toHexString()
        assertThat(leaderVrf).isEqualTo(expectedLeaderVrf)

        // Quick verification that the block's Vrf is not malformed so we can fail-fast for people trying to
        // game the system.
        val message = SodiumLibrary.cryptoVrfProofToHash_ietfdraft03(blockVrfSignature)
        assertThat(message.toHexString()).isEqualTo(blockVrf.toHexString())

        val blockUtils = BlockUtils(mockk {}, libraryPath)
        val inputVrf = blockUtils.mkInputVRF(slot, eta0)
        println("inputVrf: ${inputVrf.toHexString()}")

        // This PROVES that the block was signed by the node's VRF SKey. We only need their public VKey to verify that.
        val blockVrfVerify = SodiumLibrary.cryptoVrfVerify_ietfdraft03(vrfVkey, blockVrfSignature, inputVrf)
        assertThat(blockVrfVerify.toHexString()).isEqualTo(blockVrf.toHexString())
    }
}
