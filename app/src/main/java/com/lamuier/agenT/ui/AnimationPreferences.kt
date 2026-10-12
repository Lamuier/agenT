package com.lamuier.agenT.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun rememberAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    fun read() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var enabled by remember { mutableStateOf(read()) }
    DisposableEffect(context, owner) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = read() }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) enabled = read() }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        owner.lifecycle.addObserver(lifecycleObserver)
        onDispose { context.contentResolver.unregisterContentObserver(observer); owner.lifecycle.removeObserver(lifecycleObserver) }
    }
    return enabled
}
