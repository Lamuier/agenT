package com.lamuier.agenT.data

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

/** Shares only live work. Results/errors are discarded at completion; freshness stays with repositories. */
internal class InFlightRequests<K : Any, V> {
    private val requests = ConcurrentHashMap<K, CompletableFuture<V>>()

    fun run(key: K, block: () -> V): V {
        val candidate = CompletableFuture<V>()
        val existing = requests.putIfAbsent(key, candidate)
        if (existing != null) return try { existing.join() } catch (error: CompletionException) { throw error.cause ?: error }
        return try {
            block().also { candidate.complete(it) }
        } catch (error: Throwable) {
            candidate.completeExceptionally(error)
            throw error
        } finally { requests.remove(key, candidate) }
    }
}
