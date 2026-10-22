package com.lamuier.agenT.system

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Latest cache-render request wins within a short batch. Never used for one-shot alerts. */
internal class CoalescedWork<T>(scope: CoroutineScope, delayMillis: Long = 200L, work: suspend (T) -> Unit) {
    private val requests = Channel<T>(Channel.CONFLATED)
    init {
        scope.launch {
            for (request in requests) {
                delay(delayMillis)
                var latest = request
                while (true) {
                    val next = requests.tryReceive().getOrNull() ?: break
                    latest = next
                }
                try { work(latest) } catch (error: CancellationException) { throw error } catch (error: Exception) {
                    android.util.Log.w("AgenTWork", "coalesced request dropped", error)
                }
            }
        }
    }
    fun submit(value: T) { requests.trySend(value) }
}
