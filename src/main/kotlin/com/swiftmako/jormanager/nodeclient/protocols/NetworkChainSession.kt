package com.swiftmako.jormanager.nodeclient.protocols

import com.swiftmako.jormanager.model.ChainPoint
import com.swiftmako.jormanager.services.ChainIngestionCoordinator
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore

/** Connection-local work, fenced by the persisted network delivery generation. */
class NetworkChainSession(
    val networkKey: String,
    val coordinator: ChainIngestionCoordinator,
    val defaultNetwork: Boolean,
    generation: Long,
    val byronEpochSlots: Long,
) {
    data class Request(val point: ChainPoint, val generation: Long)
    private val permits = Semaphore(128)
    private val requests = Channel<Request>(128)
    @Volatile var generation: Long = generation
        private set

    suspend fun reserve() = permits.acquire()
    fun release() = permits.release()
    fun enqueue(point: ChainPoint) {
        check(requests.trySend(Request(point, generation)).isSuccess) { "Chain fetch queue closed or full" }
    }
    suspend fun next(): Request {
        while (true) {
            val request = requests.receive()
            if (request.generation == generation) return request
            permits.release()
        }
    }
    fun rollback(point: ChainPoint?) {
        generation = coordinator.rollback(networkKey, point, generation)
    }
    fun close() { requests.close() }
}
