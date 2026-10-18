package com.lamuier.agenT.ui

import com.lamuier.agenT.codex.CodexQuotaUiState
import com.lamuier.agenT.model.AppUiState
import com.lamuier.agenT.model.RefreshResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine

internal fun dashboardRefreshBusy(state: AppUiState, codex: CodexQuotaUiState): Boolean =
    state.loadingUsage || state.loadingTasks || state.loadingStatus ||
        state.refreshing || state.refreshingStatus || state.refreshingTasks ||
        codex.booting || codex.loadingStatus || codex.refreshing || codex.saving || codex.refreshingStatus

internal typealias RefreshRequest = ((RefreshResult) -> Unit) -> Unit

/** Cancellation on leaving the foreground discards callbacks from surviving ViewModel requests. */
internal suspend fun refreshOnForeground(requests: List<RefreshRequest>): Boolean = coroutineScope {
    val results = requests.map { request ->
        async {
            suspendCancellableCoroutine<RefreshResult> { continuation ->
                val completed = AtomicBoolean()
                val finish: (RefreshResult) -> Unit = { result ->
                    if (completed.compareAndSet(false, true)) continuation.resume(result)
                }
                continuation.invokeOnCancellation { completed.set(true) }
                try {
                    request(finish)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    finish(RefreshResult.Failed)
                }
            }
        }
    }.awaitAll()
    results.any { it == RefreshResult.Updated } && results.none { it == RefreshResult.Failed }
}
