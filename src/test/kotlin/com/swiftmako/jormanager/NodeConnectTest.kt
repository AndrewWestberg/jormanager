package com.swiftmako.jormanager

import com.muquit.libsodiumjna.SodiumLibrary
import com.squareup.jnagmp.Gmp
import com.squareup.moshi.Moshi
import com.swiftmako.jormanager.ktx.sumByLong
import com.swiftmako.jormanager.model.ledger.Ledger
import com.swiftmako.jormanager.nodeclient.protocols.mux.MuxProtocol
import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.source
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
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
        SodiumLibrary.setLibraryPath(libraryPath);

        val v = SodiumLibrary.libsodiumVersionString()
        println("libsodium version: $v")

        println("nonceBytes: $nonceBytes")
    }

    @Test
    fun testLedgerState() {
        val duration = measureTimeMillis {
            val moshi = Moshi.Builder().build()
            val ledgerAdapter = moshi.adapter(Ledger::class.java)

            val source = File("/tmp/ledger-state-219.json").source().buffer()

            val ledger = ledgerAdapter.fromJson(source)

            val computeTime = measureTimeMillis {
                val stakeMap = ledger!!.esSnapshots.pstakeSet.stake.map { stakeItem ->
                    (stakeItem[0] as Map<String, String>)["key hash"] to (stakeItem[1] as Double).toLong()
                }.toMap()
                val activeStake = ledger.esSnapshots.pstakeSet.delegations.filter {
                    it[1] == "00beef0a9be2f6d897ed24a613cf547bb20cd282a04edfc53d477114"
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
    fun testLeaderCheck() {
        // BCSH
        //val poolId = "00beef0a9be2f6d897ed24a613cf547bb20cd282a04edfc53d477114"
        // BCSH0
        val poolId = "00beef8710427e328a29555283c74b202b40bec9a62630a9f03b1e18"
        // BCSH1
        //val poolId = "00beef9385526062d41cd7293746048c6a9a13ab8b591920cf40c706"
        // BCSH2
        //val poolId = "00beef9385526062d41cd7293746048c6a9a13ab8b591920cf40c706"
        // Calculate Stake proportion
        val sigma = getSigma(poolId)
        println("sigma: $sigma")
    }

    private fun getSigma(poolId: String): BigDecimal {
        Gmp.checkLoaded()
        val moshi = Moshi.Builder().build()
        val ledgerAdapter = moshi.adapter(Ledger::class.java)

        val source = File("/tmp/ledger-state-219.json").source().buffer()

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

