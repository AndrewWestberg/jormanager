package com.swiftmako.jormanager.services

import com.swiftmako.jormanager.controllers.utils.BlockUtils
import com.swiftmako.jormanager.utils.CardanoLeaderElection
import java.math.BigDecimal
import java.math.BigInteger
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

/** Shared CPU budget; ordered chunk delivery keeps persistence and retained results bounded. */
@Service
class LeaderLogCalculator internal constructor(
    private val blockUtils: BlockUtils,
    visibleProcessors: Int,
) {
    init {
        require(visibleProcessors > 0)
    }

    @Autowired
    constructor(blockUtils: BlockUtils) : this(blockUtils, Runtime.getRuntime().availableProcessors())

    internal val parallelism: Int = maxOf(1, visibleProcessors / 2)
    private val cpuDispatcher = Dispatchers.Default.limitedParallelism(parallelism)

    internal enum class Mode { TPRAOS, PRAOS }

    internal data class PoolInput(
        val threshold: CardanoLeaderElection.LeaderThreshold,
        val vrfSkey: ByteArray,
    )

    internal data class ChunkResult(
        val firstSlot: Long,
        val slotCount: Int,
        val electedBySlotAndPool: BooleanArray,
    )

    internal suspend fun calculate(
        firstSlot: Long,
        slotCount: Int,
        mode: Mode,
        decentralization: BigDecimal,
        epochNonce: ByteArray,
        pools: List<PoolInput>,
        onChunk: suspend (ChunkResult) -> Unit,
    ) {
        val width = if (mode == Mode.TPRAOS) 64 else 32
        pools.forEach {
            require(it.threshold.vrfSizeBytes == width) {
                if (mode == Mode.TPRAOS) "TPraos requires a 64-byte threshold" else "Praos requires a 32-byte threshold"
            }
        }
        if (slotCount <= 0 || pools.isEmpty()) return

        coroutineScope {
            val pending = ArrayDeque<Deferred<ChunkResult>>()
            var submittedSlots = 0
            fun submitChunk() {
                val chunkFirstSlot = firstSlot + submittedSlots
                val count = minOf(1024, slotCount - submittedSlots)
                submittedSlots += count
                pending.addLast(async(cpuDispatcher) {
                    val context = currentCoroutineContext()
                    val elected = BooleanArray(Math.multiplyExact(count, pools.size))
                    repeat(count) { offset ->
                        context.ensureActive()
                        val slot = chunkFirstSlot + offset
                        if (!blockUtils.isOverlaySlot(firstSlot, slot, decentralization)) {
                            val seed = if (mode == Mode.TPRAOS) blockUtils.mkSeed(slot, epochNonce) else blockUtils.mkInputVRF(slot, epochNonce)
                            pools.forEachIndexed { poolIndex, pool ->
                                context.ensureActive()
                                val raw = blockUtils.vrfEvalCertified(seed, pool.vrfSkey)
                                val value = if (mode == Mode.PRAOS) blockUtils.vrfLeaderValue(raw) else raw
                                elected[offset * pools.size + poolIndex] = pool.threshold.isLeader(BigInteger(1, value))
                            }
                        }
                    }
                    ChunkResult(chunkFirstSlot, count, elected)
                })
            }
            repeat(parallelism) { if (submittedSlots < slotCount) submitChunk() }
            while (pending.isNotEmpty()) {
                val result = pending.removeFirst().await()
                ensureActive()
                if (submittedSlots < slotCount) submitChunk()
                ensureActive()
                onChunk(result)
            }
        }
    }
}
