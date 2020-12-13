package com.swiftmako.jormanager

import com.google.common.truth.Truth.assertThat
import com.muquit.libsodiumjna.SodiumLibrary
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.toHexString
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.jcajce.provider.digest.BCMessageDigest
import org.bouncycastle.jcajce.provider.digest.Blake2b
import org.bouncycastle.jcajce.provider.digest.Blake2s
import org.junit.jupiter.api.Test

class PoolIdTest {

    @Test
    fun `test coreVrfVkey to poolId`() {
        val libraryPath = "/usr/local/lib/libsodium.so"
        SodiumLibrary.setLibraryPath(libraryPath)

        val coreVrfVkey = "cad3c900ca6baee9e65bf61073d900bfbca458eeca6d0b9f9931f5b1017a8cd6".hexToByteArray()
        // bfd44b4bd6f02440ed892848398bf2822d9b9977a6e277a4e6ce722215598d34
//        val poolId = SodiumLibrary.cryptoBlake2bHash(coreVrfVkey, null).toHexString()
//        assertThat(poolId).isEqualTo("00beef0a9be2f6d897ed24a613cf547bb20cd282a04edfc53d477114")

//        val poolId = SodiumLibrary.cryptoVrfProofToHash(coreVrfVkey).toHexString()
//        assertThat(poolId).isEqualTo("00beef0a9be2f6d897ed24a613cf547bb20cd282a04edfc53d477114")

//        val blake2b160 = Blake2b.Blake2b160().digest(coreVrfVkey).toHexString()
//        assertThat(blake2b160).isEqualTo("00beef0a9be2f6d897ed24a613cf547bb20cd282a04edfc53d477114")

//        val blake2b256 = Blake2b.Blake2b256().digest(coreVrfVkey).toHexString()
        // bfd44b4bd6f02440ed892848398bf2822d9b9977a6e277a4e6ce722215598d34
//        assertThat(blake2b256).isEqualTo("00beef0a9be2f6d897ed24a613cf547bb20cd282a04edfc53d477114")

        val blake2b224 = Blake2bDigest(224)
        blake2b224.update(coreVrfVkey, 0, coreVrfVkey.size)
        val output = ByteArray(28)
        blake2b224.doFinal(output, 0)
        val poolId = output.toHexString()
        assertThat(poolId).isEqualTo("00beef0a9be2f6d897ed24a613cf547bb20cd282a04edfc53d477114")

    }
}
