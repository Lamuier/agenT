package com.lamuier.agenT.ui

import com.lamuier.agenT.model.RefreshResult
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ForegroundRefreshTest {
    @Test fun freshDataCanBeSkippedWhileWaitingForEveryNetworkResult() = runBlocking {
        val callbacks = mutableListOf<(RefreshResult) -> Unit>()
        val result = async { refreshOnForeground(List(3) { { finish -> callbacks.add(finish); Unit } }) }
        while (callbacks.size < 3) yield()
        callbacks[0](RefreshResult.Skipped)
        callbacks[1](RefreshResult.Updated)
        yield()
        assertFalse(result.isCompleted)
        callbacks[2](RefreshResult.Updated)
        assertTrue(result.await())
    }

    @Test fun emptyOrEntirelyCachedRefreshDoesNotConfirm() = runBlocking {
        assertFalse(refreshOnForeground(emptyList()))
        assertFalse(refreshOnForeground(List(3) { { finish -> finish(RefreshResult.Skipped) } }))
    }

    @Test fun failureOrIncompleteDataPreventsConfirmationEvenWithOtherUpdates() = runBlocking {
        assertFalse(refreshOnForeground(listOf(
            { finish -> finish(RefreshResult.Updated) },
            { finish -> finish(RefreshResult.Failed) },
        )))
        assertEquals(RefreshResult.Failed, RefreshResult.fromData(fromCache = true, incomplete = true))
        assertEquals(RefreshResult.Skipped, RefreshResult.fromData(fromCache = true))
        assertEquals(RefreshResult.Updated, RefreshResult.fromData(fromCache = false))
    }

    @Test fun leavingForegroundDiscardsLateResultsWithoutReplayingConfirmation() = runBlocking {
        var finish: ((RefreshResult) -> Unit)? = null
        var confirmations = 0
        val session = launch {
            if (refreshOnForeground(listOf({ callback -> finish = callback }))) confirmations++
        }
        while (finish == null) yield()
        session.cancelAndJoin()
        finish!!(RefreshResult.Updated)
        finish!!(RefreshResult.Updated)
        assertEquals(0, confirmations)
        // A new session only reading the fresh result remains silent.
        if (refreshOnForeground(listOf({ callback -> callback(RefreshResult.Skipped) }))) confirmations++
        assertEquals(0, confirmations)
    }

    @Test fun duplicateCallbacksCannotCompleteAnotherPendingRequest() = runBlocking {
        val callbacks = mutableListOf<(RefreshResult) -> Unit>()
        val result = async { refreshOnForeground(List(2) { { finish -> callbacks.add(finish); Unit } }) }
        while (callbacks.size < 2) yield()
        callbacks[0](RefreshResult.Updated)
        callbacks[0](RefreshResult.Updated)
        yield()
        assertFalse(result.isCompleted)
        callbacks[1](RefreshResult.Failed)
        assertFalse(result.await())
    }
}
