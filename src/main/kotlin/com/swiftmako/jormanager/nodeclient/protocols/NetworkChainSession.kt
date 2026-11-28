package com.swiftmako.jormanager.nodeclient.protocols

import com.swiftmako.jormanager.model.ChainPoint
import com.swiftmako.jormanager.services.ChainIngestionCoordinator
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore

/** Connection-local work, fenced by the persisted network delivery generation. */
class NetworkChainSession(
    val networkKey: String,
    val coordinator: ChainIngestionCoordinator,
    val defaultNetwork: Boolean,
    generation: Long,
    val byronEpochSlots: Long,
) {
    companion object {
        const val MAX_PERMITS = 128
        const val MAX_BATCH_SIZE = 64
    }

    data class Request(
        val point: ChainPoint,
        val generation: Long
    )

    private val permits = Semaphore(MAX_PERMITS)
    private val queueLock = Any()
    private val requests = ArrayDeque<Request>(MAX_PERMITS)
    private var closed = false
    private val wakeup = MutableStateFlow(0L)

    @Volatile var generation: Long = generation
        private set

    @Volatile var tipBlockNumber: Long = 0

    suspend fun reserve() = permits.acquire()

    fun release() = permits.release()

    fun enqueue(point: ChainPoint) {
        synchronized(queueLock) {
            check(!closed && requests.size < MAX_PERMITS) { "Chain fetch queue closed or full" }
            requests.addLast(Request(point, generation))
            wakeup.value++
        }
    }

    suspend fun next(): Request = nextBatch(1).single()

    suspend fun nextBatch(maxSize: Int = MAX_BATCH_SIZE): List<Request> {
        require(maxSize in 1..MAX_BATCH_SIZE) { "Invalid batch size $maxSize" }
        while (true) {
            var staleCount = 0
            var batch: List<Request>? = null
            var isClosed = false
            synchronized(queueLock) {
                while (requests.isNotEmpty() && requests.first().generation != generation) {
                    requests.removeFirst()
                    staleCount++
                }
                if (requests.isNotEmpty()) {
                    val batchGeneration = requests.first().generation
                    val drained = ArrayList<Request>(minOf(maxSize, requests.size))
                    while (drained.size < maxSize && requests.isNotEmpty() && requests.first().generation == batchGeneration) {
                        drained.add(requests.removeFirst())
                    }
                    batch = drained
                } else if (closed) {
                    isClosed = true
                }
            }
            repeat(staleCount) { permits.release() }
            if (batch != null) return batch
            if (isClosed) throw ClosedReceiveChannelException("Channel was closed")
            wakeup.first {
                synchronized(queueLock) { closed || requests.isNotEmpty() }
            }
        }
    }

    @Synchronized
    fun rollback(point: ChainPoint?) {
        var staleCount = 0
        try {
            val nextGeneration = coordinator.rollback(networkKey, point, generation)
            synchronized(queueLock) {
                generation = nextGeneration
                while (requests.isNotEmpty() && requests.first().generation != generation) {
                    requests.removeFirst()
                    staleCount++
                }
                wakeup.value++
            }
        } catch (e: ChainIngestionCoordinator.ReplayRequiredException) {
            synchronized(queueLock) {
                generation = Math.addExact(generation, 1L)
                while (requests.isNotEmpty() && requests.first().generation != generation) {
                    requests.removeFirst()
                    staleCount++
                }
                wakeup.value++
            }
            throw e
        } finally {
            repeat(staleCount) { permits.release() }
        }
    }

    @Synchronized
    fun close() {
        var remainingCount = 0
        synchronized(queueLock) {
            if (!closed) {
                closed = true
                remainingCount = requests.size
                requests.clear()
                wakeup.value++
            }
        }
        try {
            coordinator.endSession(networkKey, generation)
        } finally {
            repeat(remainingCount) { permits.release() }
        }
    }
}
