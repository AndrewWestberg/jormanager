package com.swiftmako.jormanager

import com.google.common.truth.Truth.assertThat
import com.muquit.libsodiumjna.SodiumLibrary
import com.swiftmako.jormanager.controllers.utils.BlockUtils
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import io.mockk.mockk
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PoolIdTest {

    @Test
    fun `test coreVrfVkey to poolId`() {
        val libraryPath = "/usr/local/lib/libsodium.so"
        SodiumLibrary.setLibraryPath(libraryPath)

        val coreVrfVkey = "cad3c900ca6baee9e65bf61073d900bfbca458eeca6d0b9f9931f5b1017a8cd6".hexToByteArray()
        val blake2b224 = Blake2bDigest(224)
        blake2b224.update(coreVrfVkey, 0, coreVrfVkey.size)
        val output = ByteArray(28)
        blake2b224.doFinal(output, 0)
        val poolId = output.toHexString()
        assertThat(poolId).isEqualTo("00beef0a9be2f6d897ed24a613cf547bb20cd282a04edfc53d477114")
    }

    @Test
    fun `test blake2b`() {
        val libraryPath = "/usr/local/lib/libsodium.so"
        SodiumLibrary.setLibraryPath(libraryPath)

        val nc = "cbb060def94ec6067440922e111b3aa218de77e462859f58fbb87f7620585a44"
        val nh = "4c92900dc9fa2f1909cbf974528818f7f111dc2c04060ae4dc056b072359b549"
        val extraEntropy = "581fabcf234729e2fc05172b1b9bfd7fe10324c87ed7c408a5b41ffcd6be45e2"
        var inputString = nc + nh
        var input = inputString.hexToByteArray()
        var output = SodiumLibrary.cryptoBlake2bHash(input, null)
        inputString = output.toHexString() + extraEntropy
        input = inputString.hexToByteArray()
        output = SodiumLibrary.cryptoBlake2bHash(input, null)
        val hashed = output.toHexString()
        println("blake2b: $hashed")
        assertThat(hashed).isEqualTo("7782760461e672e51b35ba332d4bd36df2733bf5ed01d83beef28ad9a17e36f5")
    }

    @Test
    fun `test vkey to address`() {
        val libraryPath = "/usr/local/lib/libsodium.so"
        SodiumLibrary.setLibraryPath(libraryPath)

        val vkey = "c9350cb75990424963a47586ffbc0d6f0cc441c9ab00071cf01e3e808799fe7d1c6626b08fea16a902f1247f727c58a74e1315c3fc7c5d0cf2fe0f3c8b43f0e7".hexToByteArray()
        val blake2b224 = Blake2bDigest(224)
        blake2b224.update(vkey, 0, vkey.size)
        val output = ByteArray(28)
        blake2b224.doFinal(output, 0)
        val hashed = "61${output.toHexString()}"

        // addr1vy5l62qysq3j6u4jsw0u73e8teus5x36ghd04lv0vsvqvys770xjw
        assertThat(hashed).isEqualTo("6129fd280480232d72b2839fcf47275e790a1a3a45dafafd8f64180612")
    }

    @Test
    fun `test verify block`() {
        val libraryPath = "/usr/local/lib/libsodium.so"
        SodiumLibrary.setLibraryPath(libraryPath)

        // These values will be included in the payload to pooltool for any given block
        val slot = 14398146L
        val vrfVkey = "9162a3ec9fa00531afea9c26bddbb663fe95904e9076fd86b94f9c075b83fd30".hexToByteArray()
        val leaderVrf = "000c512b89d1ad540ce15253ddee1a99af07f92932e8c99505f8e671a11a33571315dd40594ab438c53fde4d5165872f6bfe420edc2c4af4f82b0479e4fbe69e"
        val leaderVrfSig = "679c22bc702da88eb628255f5b264cf23acebd9ff5f427e62120c0a795cb8c5a867c8fcd36e053819ec15183fc56c74ba2851d43a3eb8f18c5696361833d164805fa85deb86d21502585287a1c3a6505".hexToByteArray()

        // Values calculated or pre-existing on the pooltool side
        // epoch nonce
        val eta0 = "9a3238d1ab981cfd6958f43de0b4df1220328e2bccd982d88ec169fe31832a7e".hexToByteArray()
        // sigma value for the pool_id who created this block
        val sigma = BigDecimal(0.0083826308985987)
        // The active slots coefficient (f)
        val f = 0.05

        // Quick verification that the block's leaderVrf is not malformed so we can fail-fast for people trying to
        // game the system.
        val leaderVrfHash = SodiumLibrary.cryptoVrfProofToHash(leaderVrfSig).toHexString()
        assertThat(leaderVrfHash).isEqualTo(leaderVrf)

        val blockUtils = BlockUtils(mockk {}, libraryPath)
        val seed = blockUtils.mkSeed(slot, eta0)
        println("seed for slot $slot: ${seed.toHexString()}")

        // This PROVES that the block was signed by the node's VRF SKey. We only need their public VKey to verify that.
        val leaderVrfVerify = SodiumLibrary.cryptoVrfVerify(vrfVkey, leaderVrfSig, seed).toHexString()
        assertThat(leaderVrfVerify).isEqualTo(leaderVrf)

        // This PROVES that the block won the lottery and is allowed to mint in this slot
        val isLeader = blockUtils.isLeaderVrfAllowedToLead(slot, leaderVrf, f, sigma)
        assertThat(isLeader).isTrue()
    }
}
