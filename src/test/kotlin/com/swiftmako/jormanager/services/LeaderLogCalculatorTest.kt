package com.swiftmako.jormanager.services

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.swiftmako.jormanager.controllers.utils.BlockUtils
import com.swiftmako.jormanager.ktx.hexToByteArray
import com.swiftmako.jormanager.model.StakeFraction
import com.swiftmako.jormanager.services.LeaderLogCalculator.Mode
import com.swiftmako.jormanager.services.LeaderLogCalculator.PoolInput
import com.swiftmako.jormanager.utils.CardanoLeaderElection
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LeaderLogCalculatorTest {
    private val blockUtils = BlockUtils(AtomicReference(), "/usr/local/lib/libsodium.so")
    private val schedules: JsonNode =
        requireNotNull(javaClass.getResourceAsStream("/leaderlog/node-schedules.json")).use {
            ObjectMapper().readTree(it)["cases"]
        }

    @Test
    fun `all node schedules match complete native results with one and two workers`() = runBlocking {
        assertThat(schedules.size()).isEqualTo(21)
        for (visibleProcessors in listOf(2, 4)) {
            val calculator = LeaderLogCalculator(blockUtils, visibleProcessors)
            schedules.forEachIndexed { index, oracle ->
                val result = collect(
                    calculator = calculator,
                    firstSlot = oracle["firstSlot"].asLong(),
                    slotCount = oracle["slotCount"].asInt(),
                    mode = modeOf(oracle),
                    decentralization = ratio(oracle, "d"),
                    epochNonce = oracle["nonceHex"].asText().hexToByteArray(),
                    pools = listOf(pool(oracle)),
                )
                assertWithMessage("node case %s, visible processors %s", index, visibleProcessors)
                    .that(result.electedByPool.single())
                    .containsExactlyElementsIn(assignedSlots(oracle)).inOrder()
            }
        }
    }

    @Test
    fun `both stake pools independently match their node schedules in each mode`() = runBlocking {
        for (visibleProcessors in listOf(2, 4)) {
            val calculator = LeaderLogCalculator(blockUtils, visibleProcessors)
            for (mode in Mode.entries) {
                val oracles = schedules.filter {
                    modeOf(it) == mode && it["slotCount"].asInt() == 120 &&
                        it["fNumerator"].asText() == "1" && it["fDenominator"].asText() == "20" &&
                        it["activeStake"].asText() == "3"
                }.sortedBy { it["poolStake"].asText().toBigInteger() }
                assertThat(oracles.map { it["poolStake"].asText() }).containsExactly("1", "2").inOrder()
                val first = oracles.first()
                val result = collect(
                    calculator = calculator,
                    firstSlot = first["firstSlot"].asLong(),
                    slotCount = first["slotCount"].asInt(),
                    mode = mode,
                    decentralization = ratio(first, "d"),
                    epochNonce = first["nonceHex"].asText().hexToByteArray(),
                    pools = oracles.map { pool(it) },
                )
                oracles.forEachIndexed { poolIndex, oracle ->
                    assertWithMessage("%s pool %s, visible processors %s", mode, poolIndex, visibleProcessors)
                        .that(result.electedByPool[poolIndex])
                        .containsExactlyElementsIn(assignedSlots(oracle)).inOrder()
                }
            }
        }
    }

    @Test
    fun `chunk boundaries cover every nonzero starting slot and pool exactly once`() = runBlocking {
        val firstSlot = 10_000_123L
        for (visibleProcessors in listOf(2, 4)) {
            val calculator = LeaderLogCalculator(blockUtils, visibleProcessors)
            for (mode in Mode.entries) {
                val oracle = alwaysLeaderOracle(mode)
                // f=1 elects both a nonzero stake and a zero stake, through the real native path.
                val pools = listOf(pool(oracle), pool(oracle, BigInteger.ZERO))
                for (slotCount in listOf(0, 1, 1023, 1024, 1025, 2049)) {
                    val result = collect(
                        calculator, firstSlot, slotCount, mode, BigDecimal.ZERO,
                        oracle["nonceHex"].asText().hexToByteArray(), pools,
                    )
                    val expected = (firstSlot until firstSlot + slotCount).toList()
                    result.electedByPool.forEachIndexed { poolIndex, elected ->
                        assertWithMessage("%s count %s pool %s, visible processors %s", mode, slotCount, poolIndex, visibleProcessors)
                            .that(elected).containsExactlyElementsIn(expected).inOrder()
                    }
                    val expectedSizes = when (slotCount) {
                        0 -> emptyList()
                        1025 -> listOf(1024, 1)
                        2049 -> listOf(1024, 1024, 1)
                        else -> listOf(slotCount)
                    }
                    assertThat(result.chunkSizes).containsExactlyElementsIn(expectedSizes).inOrder()
                    assertThat(result.chunkFirstSlots)
                        .containsExactlyElementsIn(expectedSizes.indices.map { firstSlot + it * 1024L }).inOrder()
                }
                val empty = collect(
                    calculator, firstSlot, 2049, mode, BigDecimal.ZERO,
                    oracle["nonceHex"].asText().hexToByteArray(), emptyList(),
                )
                assertThat(empty.chunkSizes).isEmpty()
                assertThat(empty.chunkFirstSlots).isEmpty()
                assertThat(empty.electedByPool).isEmpty()
            }
        }
    }

    @Test
    fun `all overlay slots remain covered and unelected in both modes`() = runBlocking {
        for (visibleProcessors in listOf(2, 4)) {
            val calculator = LeaderLogCalculator(blockUtils, visibleProcessors)
            for (mode in Mode.entries) {
                val oracle = alwaysLeaderOracle(mode)
                val result = collect(
                    calculator, 10_000_123L, 2049, mode, BigDecimal.ONE,
                    oracle["nonceHex"].asText().hexToByteArray(), listOf(pool(oracle), pool(oracle, BigInteger.ZERO)),
                )
                assertThat(result.chunkSizes).containsExactly(1024, 1024, 1).inOrder()
                result.electedByPool.forEach { assertThat(it).isEmpty() }
            }
        }
    }

    @Test
    fun `exact small decimal overlay matches node boundary even in modern mode`() = runBlocking {
        val oracle = schedules.single {
            it["dNumerator"].asText() == "1" && it["dDenominator"].asText() == "2500" &&
                it["fDenominator"].asText() == "1"
        }
        val firstSlot = oracle["firstSlot"].asLong()
        val expected = assignedSlots(oracle)
        assertThat(expected).doesNotContain(firstSlot)
        assertThat(expected).contains(firstSlot + 2499)
        assertThat(expected).doesNotContain(firstSlot + 2500)
        assertThat(expected).contains(firstSlot + 2501)
        for (visibleProcessors in listOf(2, 4)) {
            val calculator = LeaderLogCalculator(blockUtils, visibleProcessors)
            for (mode in Mode.entries) {
                // f=1 makes this node overlay oracle independent of the mode's VRF width.
                val result = collect(
                    calculator, firstSlot, oracle["slotCount"].asInt(), mode, ratio(oracle, "d"),
                    oracle["nonceHex"].asText().hexToByteArray(), listOf(pool(oracle, mode = mode)),
                )
                assertWithMessage("overlay mode %s, visible processors %s", mode, visibleProcessors)
                    .that(result.electedByPool.single()).containsExactlyElementsIn(expected).inOrder()
            }
        }
    }

    @Test
    fun `calculator rejects the other mode threshold width before emitting any chunk`() {
        for (mode in Mode.entries) {
            val oracle = alwaysLeaderOracle(mode)
            val otherMode = if (mode == Mode.TPRAOS) Mode.PRAOS else Mode.TPRAOS
            var callbacks = 0
            assertThrows<IllegalArgumentException> {
                runBlocking {
                    LeaderLogCalculator(blockUtils, 4).calculate(
                        firstSlot = oracle["firstSlot"].asLong(),
                        slotCount = 1,
                        mode = mode,
                        decentralization = BigDecimal.ZERO,
                        epochNonce = oracle["nonceHex"].asText().hexToByteArray(),
                        pools = listOf(pool(oracle), pool(oracle, mode = otherMode)),
                    ) { callbacks++ }
                }
            }
            assertThat(callbacks).isEqualTo(0)
        }
    }

    private fun modeOf(oracle: JsonNode): Mode = when (oracle["mode"].asText()) {
        "tpraos" -> Mode.TPRAOS
        "praos" -> Mode.PRAOS
        else -> error("Unknown node schedule mode: ${oracle["mode"]}")
    }

    private fun ratio(oracle: JsonNode, prefix: String): BigDecimal =
        BigDecimal(oracle["${prefix}Numerator"].asText()).divide(BigDecimal(oracle["${prefix}Denominator"].asText()))

    private fun pool(
        oracle: JsonNode,
        poolStake: BigInteger = oracle["poolStake"].asText().toBigInteger(),
        mode: Mode = modeOf(oracle),
    ): PoolInput = PoolInput(
        threshold = CardanoLeaderElection.prepare(
            CardanoLeaderElection.prepareActiveSlotCoefficient(ratio(oracle, "f")),
            StakeFraction(poolStake, oracle["activeStake"].asText().toBigInteger()),
            if (mode == Mode.TPRAOS) 64 else 32,
        ),
        vrfSkey = oracle["vrfSkeyHex"].asText().hexToByteArray(),
    )

    private fun alwaysLeaderOracle(mode: Mode): JsonNode = schedules.first {
        modeOf(it) == mode && it["fDenominator"].asText() == "1" && it["poolStake"].asText() == "1"
    }

    private fun assignedSlots(oracle: JsonNode): List<Long> = oracle["assignedSlots"].map { it.asLong() }

    private data class Collected(
        val electedByPool: List<List<Long>>,
        val chunkFirstSlots: List<Long>,
        val chunkSizes: List<Int>,
    )

    private suspend fun collect(
        calculator: LeaderLogCalculator,
        firstSlot: Long,
        slotCount: Int,
        mode: Mode,
        decentralization: BigDecimal,
        epochNonce: ByteArray,
        pools: List<PoolInput>,
    ): Collected {
        val elected = List(pools.size) { mutableListOf<Long>() }
        val chunkFirstSlots = mutableListOf<Long>()
        val chunkSizes = mutableListOf<Int>()
        var completed = 0
        calculator.calculate(firstSlot, slotCount, mode, decentralization, epochNonce, pools) { chunk ->
            assertThat(chunk.firstSlot).isEqualTo(firstSlot + completed)
            assertThat(chunk.slotCount).isEqualTo(minOf(1024, slotCount - completed))
            assertThat(chunk.electedBySlotAndPool.size).isEqualTo(chunk.slotCount * pools.size)
            chunkFirstSlots += chunk.firstSlot
            chunkSizes += chunk.slotCount
            for (offset in 0 until chunk.slotCount) {
                for (poolIndex in pools.indices) {
                    if (chunk.electedBySlotAndPool[offset * pools.size + poolIndex]) {
                        elected[poolIndex] += chunk.firstSlot + offset
                    }
                }
            }
            completed += chunk.slotCount
        }
        assertThat(completed).isEqualTo(if (pools.isEmpty()) 0 else slotCount)
        return Collected(elected, chunkFirstSlots, chunkSizes)
    }
}
