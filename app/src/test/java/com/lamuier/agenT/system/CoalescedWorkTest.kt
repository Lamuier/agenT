package com.lamuier.agenT.system

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class CoalescedWorkTest {
    @Test fun aCacheUpdateBatchRendersLatestDataAndFutureBatchesStillRun() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val results = Channel<Int>(Channel.UNLIMITED)
        try {
            val work = CoalescedWork<Int>(scope) { results.send(it) }
            work.submit(1); work.submit(2); work.submit(3)
            assertEquals(3, withTimeout(3000) { results.receive() })
            work.submit(4)
            assertEquals(4, withTimeout(3000) { results.receive() })
            assertTrue(results.tryReceive().isFailure)
        } finally { scope.cancel() }
    }
}
