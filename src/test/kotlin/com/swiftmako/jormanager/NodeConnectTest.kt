package com.swiftmako.jormanager

import com.google.common.truth.Truth.assertThat
import com.muquit.libsodiumjna.SodiumLibrary
import com.squareup.jnagmp.Gmp
import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.ktx.sumByLong
import com.swiftmako.jormanager.ktx.toHexString
import com.swiftmako.jormanager.model.ledger.Ledger
import com.swiftmako.jormanager.nodeclient.protocols.mux.MuxProtocol
import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.source
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.nio.ByteBuffer
import kotlin.experimental.xor
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.system.measureTimeMillis

class NodeConnectTest {

    @Test
    fun test() = runBlocking {
        MuxProtocol("localhost", 6000, 764824073).start().join()
    }

    @Test
    fun libsodiumTest() {
        // Linux
        val libraryPath = "/usr/local/lib/libsodium.so"
        println("Library path: $libraryPath")

        println("loading libsodium...")
        SodiumLibrary.setLibraryPath(libraryPath)

        val v = SodiumLibrary.libsodiumVersionString()
        println("libsodium version: $v")
    }

    @Test
    fun testLedgerState() {
        val duration = measureTimeMillis {
            val moshi = Moshi.Builder().build()
            val ledgerAdapter = moshi.adapter(Ledger::class.java)

//            val source = File("/tmp/ledger-state-219.json").source().buffer()
            val source = File("/tmp/ledger-state-87-testnet.json").source().buffer()
            val ledger = ledgerAdapter.fromJson(source)

            val computeTime = measureTimeMillis {
                val stakeMap = ledger!!.esSnapshots.pstakeSet.stake.map { stakeItem ->
                    (stakeItem[0] as Map<String, String>)["key hash"] to (stakeItem[1] as Double).toLong()
                }.toMap()
                val activeStake = ledger.esSnapshots.pstakeSet.delegations.filter {
//                    it[1] == "00beef0a9be2f6d897ed24a613cf547bb20cd282a04edfc53d477114"
                    it[1] == "3299895e62b13de5a5a52f4bb5726db5fb38928c8d2c21ff8d78517d"
                }.mapNotNull { delegation ->
                    val keyHash = (delegation[0] as Map<String, String>)["key hash"]
                    stakeMap[keyHash]
                }.sumByLong { it }

                val totalStake = stakeMap.map { entry -> entry.value }.sumByLong { it }
                val percentOfTotalStake = BigDecimal(activeStake).divide(BigDecimal(totalStake), 12, RoundingMode.HALF_UP).times(BigDecimal(100L))

                println("Active Stake: $activeStake lovelace")
                println("Total Stake: $totalStake lovelace")
                println("Stake Percentage: ${percentOfTotalStake}%")
            }
            println("Compute Time: ${computeTime}ms")
        }
        println("Total Duration: ${duration}ms")
    }

    @Test
    fun testMkSeed() {
        val libraryPath = "/usr/local/lib/libsodium.so"
        println("Library path: $libraryPath")
        println("loading libsodium...")
        SodiumLibrary.setLibraryPath(libraryPath)

        val slot = 7397153L

        val seedLInputBytes = ByteArray(8)
        val seedLInputBuffer = ByteBuffer.wrap(seedLInputBytes)
        seedLInputBuffer.putLong(1)
        val seedL = SodiumLibrary.cryptoBlake2bHash(seedLInputBytes, null)
        println("blake2b seedL/ucNonce hash of 1L ${seedL.toHexString()}")
        assertThat(seedL.toHexString()).isEqualTo("12dd0a6a7d0e222a97926da03adb5a7768d31cc7c5c2bd6828e14a7d25fa3a60")

        // The epoch nonce for 87 on testnet (figure out how to calculate it ourselves later instead of trace from the node)
        val eta0 = "70c0f591099a8de944e02585841d602479493f2d7e360f04c8b8cf990988eda3".hexToByteArray()

        val concatByteArray = ByteArray(8 + 32)
        val concatByteBuffer = ByteBuffer.wrap(concatByteArray)
        concatByteBuffer.putLong(slot)
        concatByteBuffer.put(eta0)
//        assertThat(concatByteArray.toHexString()).isEqualTo("00000000007088d770c0f591099a8de944e02585841d602479493f2d7e360f04c8b8cf990988eda3")

        val slotToSeedByteArray = SodiumLibrary.cryptoBlake2bHash(concatByteArray, null)

        println("blake2b slotToSeed hash ${slotToSeedByteArray.toHexString()}")
//        assertThat(slotToSeedByteArray.toHexString()).isEqualTo("224bfe97c5f202e9cb9f9fe8793802c67436f182a70c20cd9b6dd015b73476be")

        val seed = seedL.mapIndexed { index, byte -> byte xor slotToSeedByteArray[index] }.toByteArray()
        assertThat(seed.toHexString()).isEqualTo("7e2a4eff14e47fa5d8df184a1d6c3e8906837224a7f9392d1994293a4bc6d709")


    }

    @Test
    fun testVrfEvalCertified() {
        val libraryPath = "/usr/local/lib/libsodium.so"
        println("Library path: $libraryPath")
        println("loading libsodium...")
        SodiumLibrary.setLibraryPath(libraryPath)

        // output of mkseed for slot 7397153L
        val mkSeed = "7e2a4eff14e47fa5d8df184a1d6c3e8906837224a7f9392d1994293a4bc6d709".hexToByteArray()
        // prfit.vrf.skey
        val tpraosCanBeLeaderSignKeyVRF = "30780cc944d4c32d5774d1b1c102f69ca9xxxxxxxxxxxxxxxxxxxxxxdec9532f09162a3ec9fa00531afea9c26bddbb663fe95904e9076fd86b94f9c075b83fd30".hexToByteArray()

        val certifiedProof = SodiumLibrary.cryptoVrfProve(tpraosCanBeLeaderSignKeyVRF, mkSeed)
        println("certifiedProof ${certifiedProof.size} bytes")
        println(certifiedProof.toHexString())
        assertThat(certifiedProof.toHexString()).isEqualTo("1db3358d54b28ae8ba73acf1bf6f1b7bf9cb57a90528c8cfc0bd591c19f918f69e2f70449227294d91c559900c0cf60b6a1c4acbbc7d598a290674cd0a1d270369ca2a1148549c685abf14c7c1b97303")

        val certVRF = SodiumLibrary.cryptoVrfProofToHash(certifiedProof)
        println("certVRF ${certVRF.size} bytes")
        println(certVRF.toHexString())
        assertThat(certVRF.toHexString()).isEqualTo("ba4d3bd56de3a92a0c8d14189b54267c1c935a7103057c9e50de6ba0276199d2910186635730ac5a6ebbd750d45a389357431a8e611c0ad6bd9357d607eaa609")
    }

    @Test
    fun testCheckLeaderValue() {
        val libraryPath = "/usr/local/lib/libsodium.so"
        println("Library path: $libraryPath")
        println("loading libsodium...")
        SodiumLibrary.setLibraryPath(libraryPath)

        val certNatByteArray = ("00" + "ba4d3bd56de3a92a0c8d14189b54267c1c935a7103057c9e50de6ba0276199d2910186635730ac5a6ebbd750d45a389357431a8e611c0ad6bd9357d607eaa609").hexToByteArray()

        val certNat = BigInteger(certNatByteArray)
        println("certNat = $certNat")
        assertThat(certNat.toString()).isEqualTo("9757411458561989995712603894203605624393955171919373107709061692269876269697540286127341295721533061569164287549389169055757422105258041789720804142523913")

        val certNatMax = BigInteger("2").pow(8 * 64) // 8 * vrfoutput bytes
        println("certNatMax = $certNatMax")
        assertThat(certNatMax.toString()).isEqualTo("13407807929942597099574024998205846127479365820592393377723561443721764030073546976801874298166903427690031858186486050853753882811946569946433649006084096")

        val denominator = certNatMax.minus(certNat)
        println("denominator = $denominator")

        val q = certNatMax.toBigDecimal().divide(denominator.toBigDecimal(), 34, RoundingMode.CEILING)
        println("q = ${q.toPlainString()}")

        val f = 0.05 // active slot coefficient
        val c = ln(1.0 - f)
        val sigma = 0.0240358801098558038935507965109825
        val sigmaOfF = exp(-sigma * c)
        println("sigmaOfF = $sigmaOfF")
    }

    @Test
    fun testIsOverlaySlot() {
        val d = BigDecimal("0.72").setScale(34)
        val firstSlotOfEpoch = 1000L
        var currentSlot = 1000L
        var communitySlots = 0
        repeat(100) {
            if (!isOverlaySlot(firstSlotOfEpoch, currentSlot, d)) {
                communitySlots++
            }
            currentSlot++
        }
        assertThat(communitySlots).isEqualTo(28)
    }

    private fun isOverlaySlot(firstSlotOfEpoch: Long, currentSlot: Long, d: BigDecimal): Boolean {
        val diffSlot = abs(currentSlot - firstSlotOfEpoch)
        return d.times(diffSlot.toBigDecimal()).setScale(0, RoundingMode.CEILING) < d.times((diffSlot + 1L).toBigDecimal()).setScale(0, RoundingMode.CEILING)
    }

    @Test
    fun testMakeNonceFromNumber() {
        val libraryPath = "/usr/local/lib/libsodium.so"
        println("Library path: $libraryPath")
        println("loading libsodium...")
        SodiumLibrary.setLibraryPath(libraryPath)

        val inputBytes = ByteArray(8)
        val inputBuffer = ByteBuffer.wrap(inputBytes)
        inputBuffer.putLong(0)

        val outputBytes = SodiumLibrary.cryptoBlake2bHash(inputBytes, null)

        println("blake2b seedEta hash of 0L ${outputBytes.toHexString()}")
        assertThat(outputBytes.toHexString()).isEqualTo("81e47a19e6b29b0a65b9591762ce5143ed30d0261e5d24a3201752506b20f15c")

        inputBuffer.clear()
        inputBuffer.putLong(1L)
        val outputBytes2 = SodiumLibrary.cryptoBlake2bHash(inputBytes, null)

        println("blake2b seedL hash of 1L ${outputBytes2.toHexString()}")
        assertThat(outputBytes2.toHexString()).isEqualTo("12dd0a6a7d0e222a97926da03adb5a7768d31cc7c5c2bd6828e14a7d25fa3a60")
    }

    @Test
    fun testLeaderCheck() {
        // BCSH
        //val poolId = "00beef0a9be2f6d897ed24a613cf547bb20cd282a04edfc53d477114"
        // BCSH0
        //val poolId = "00beef8710427e328a29555283c74b202b40bec9a62630a9f03b1e18"
        // BCSH1
        //val poolId = "00beef9385526062d41cd7293746048c6a9a13ab8b591920cf40c706"
        // BCSH2
        //val poolId = "00beef9385526062d41cd7293746048c6a9a13ab8b591920cf40c706"
        // PRFIT
        val poolId = "3299895e62b13de5a5a52f4bb5726db5fb38928c8d2c21ff8d78517d"
        // Calculate Stake proportion
        val sigma = getSigma(poolId)
        println("sigma: $sigma")
    }

    private fun getSigma(poolId: String): BigDecimal {
        Gmp.checkLoaded()
        val moshi = Moshi.Builder().build()
        val ledgerAdapter = moshi.adapter(Ledger::class.java)

        //val source = File("/tmp/ledger-state-219.json").source().buffer()
        val source = File("/tmp/ledger-state-87-testnet.json").source().buffer()

        val ledger = ledgerAdapter.fromJson(source)

        val stakeMap = ledger!!.esSnapshots.pstakeSet.stake.map { stakeItem ->
            (stakeItem[0] as Map<String, String>)["key hash"] to (stakeItem[1] as Double).toLong()
        }.toMap()
        val activeStake = ledger.esSnapshots.pstakeSet.delegations.filter {
            it[1] == poolId
        }.mapNotNull { delegation ->
            val keyHash = (delegation[0] as Map<String, String>)["key hash"]
            stakeMap[keyHash]
        }.sumByLong { it }

        val totalStake = stakeMap.map { entry -> entry.value }.sumByLong { it }
        return BigDecimal(activeStake).divide(BigDecimal(totalStake), 34, RoundingMode.HALF_UP)
    }
}

