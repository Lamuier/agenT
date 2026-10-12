package com.lamuier.agenT.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

/** A batch completes once, only after every explicitly requested part has succeeded. */
internal class OperationBatch(parts: Set<String>, private val onSuccess: () -> Unit) {
    private val pending = parts.toMutableSet()
    private var successful = true
    private var completed = parts.isEmpty()

    @Synchronized fun finish(part: String, success: Boolean) {
        if (completed || !pending.remove(part)) return
        successful = successful && success
        if (pending.isEmpty()) {
            completed = true
            if (successful) onSuccess()
        }
    }
}

internal object OperationFeedback {
    private val sequence = AtomicLong()
    private val results = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    val successes = results.asSharedFlow() // No replay: background results are discarded.
    fun success() { results.tryEmit(sequence.incrementAndGet()) }
    fun batch(parts: Set<String>) = OperationBatch(parts, ::success)
}

internal interface AppFeedback {
    fun selection()
    fun toggle(enabled: Boolean)
    fun success() = OperationFeedback.success()
}

private object NoFeedback : AppFeedback {
    override fun selection() = Unit
    override fun toggle(enabled: Boolean) = Unit
    override fun success() = Unit
}

internal val LocalAppFeedback = staticCompositionLocalOf<AppFeedback> { NoFeedback }

@Composable
internal fun FeedbackProvider(enabled: Boolean, content: @Composable () -> Unit) {
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    val currentEnabled by rememberUpdatedState(enabled)
    val feedback = remember(view, owner) {
        object : AppFeedback {
            fun perform(effect: Int) {
                if (currentEnabled && com.lamuier.agenT.data.InteractionPreferences.get(view.context).hapticFeedbackEnabled.value && view.isAttachedToWindow && view.hasWindowFocus() &&
                    owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    view.performHapticFeedback(effect)
                }
            }
            override fun selection() = perform(if (Build.VERSION.SDK_INT >= 34)
                HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
            override fun toggle(enabled: Boolean) = perform(if (Build.VERSION.SDK_INT >= 34) {
                if (enabled) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
            } else HapticFeedbackConstants.CLOCK_TICK)
            fun confirm() = perform(if (Build.VERSION.SDK_INT >= 30)
                HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
    LaunchedEffect(owner, feedback) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            OperationFeedback.successes.collect {
                // Let a completed save/delete dismiss its dialog before selecting the focused window.
                withFrameNanos { }
                feedback.confirm()
            }
        }
    }
    CompositionLocalProvider(LocalAppFeedback provides feedback, content = content)
}
