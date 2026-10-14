package com.lamuier.agenT.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.lamuier.agenT.R

/** Retain failure state for freshness and retry; show one brief, generic notice per failure episode. */
@Composable
internal fun LoadFailureNotice(failures: Set<String>, suppressed: Boolean, host: SnackbarHostState) {
    val tracker = remember { LoadFailureTracker() }
    val message = stringResource(R.string.load_failure_notice)
    LaunchedEffect(failures, suppressed) {
        if (tracker.shouldNotify(failures, suppressed)) host.showSnackbar(message)
    }
}

internal class LoadFailureTracker {
    private val notified = mutableSetOf<String>()
    fun shouldNotify(failures: Set<String>, suppressed: Boolean): Boolean {
        notified.retainAll(failures)
        if (suppressed || failures.all { it in notified }) return false
        notified.addAll(failures)
        return true
    }
}
