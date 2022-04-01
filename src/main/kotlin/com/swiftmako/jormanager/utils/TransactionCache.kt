package com.swiftmako.jormanager.utils

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import com.github.benmanes.caffeine.cache.RemovalListener
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration


object TransactionCache {

    val cacheMutex = Mutex()

    /**
     * Ordered list of submitted transactions
     */
    private val orderedSubmittedTransactionMap = LinkedHashMap<String, String>()

    /**
     * Hold transactions in case we need to re-submit due to rollbacks
     */
    private val submittedTransactionCache: Cache<String, String> = Caffeine.newBuilder()
        .removalListener(
            RemovalListener<String, String> { key, _, cause ->
                if (cause == RemovalCause.REPLACED) {
                    return@RemovalListener
                }
                synchronized(orderedSubmittedTransactionMap) {
                    orderedSubmittedTransactionMap.remove(key)
                }
            }
        )
        .expireAfterWrite(Duration.ofHours(24))
        .maximumSize(100)
        .build()

    fun put(txId: String, txSigned: String) {
        synchronized(orderedSubmittedTransactionMap) {
            orderedSubmittedTransactionMap.put(txId, txSigned)
        }
        submittedTransactionCache.put(txId, txSigned)
    }

    fun get(txId: String): String? = submittedTransactionCache.getIfPresent(txId)

    fun forEach(action: (Map.Entry<String, String>) -> Unit) {
        synchronized(orderedSubmittedTransactionMap) {
            orderedSubmittedTransactionMap.forEach(action)
        }
    }

    val keys: Set<String>
        get() = orderedSubmittedTransactionMap.keys

    suspend inline fun <T> withLock(action: () -> T): T {
        return cacheMutex.withLock(null, action)
    }
}