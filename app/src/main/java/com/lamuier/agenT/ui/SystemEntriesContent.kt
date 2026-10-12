package com.lamuier.agenT.ui

import android.app.NotificationManager
import android.app.StatusBarManager
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.viewinterop.AndroidView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lamuier.agenT.R
import com.lamuier.agenT.data.NotificationSettings
import com.lamuier.agenT.system.OverviewTileService
import com.lamuier.agenT.widget.WidgetPreviews

internal fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.takeUnless { it === this }?.hostActivity()
    else -> null
}

@Composable
internal fun SystemEntriesContent() {
    val context = LocalContext.current
    val activity = context.hostActivity()
    var result by remember { mutableStateOf<String?>(null) }
    val resources = androidx.compose.ui.platform.LocalResources.current
    Text(stringResource(R.string.settings_system_entries_summary), style = MaterialTheme.typography.bodyMedium)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.tile_overview), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(if (Build.VERSION.SDK_INT >= 33) R.string.tile_add_description else R.string.tile_add_legacy))
            if (Build.VERSION.SDK_INT >= 33) Button(enabled = activity != null, onClick = {
                activity?.getSystemService(StatusBarManager::class.java)?.requestAddTileService(
                    ComponentName(context, OverviewTileService::class.java), resources.getString(R.string.tile_overview),
                    Icon.createWithResource(context, R.drawable.symbol_dashboard), ContextCompat.getMainExecutor(context),
                ) { code ->
                    result = resources.getString(when (code) {
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.tile_added
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.tile_already_added
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> R.string.tile_not_added
                        else -> R.string.system_entry_failed
                    })
                }
            }) { Text(stringResource(R.string.tile_add)) }
        }
    }
    Text(stringResource(R.string.widget_picker_title), style = MaterialTheme.typography.titleMedium)
    WidgetPreviews.entries.forEach { entry ->
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(entry.title), style = MaterialTheme.typography.titleSmall)
                Text("${entry.columns} × ${entry.rows}", style = MaterialTheme.typography.bodySmall)
                val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
                AndroidView(modifier = Modifier.fillMaxWidth().height((entry.rows * 84 * fontScale).dp),
                    factory = { FrameLayout(it) }, update = { frame ->
                        frame.removeAllViews()
                        val preview = WidgetPreviews.preview(frame.context, entry).apply(frame.context, frame)
                        fun inert(view: View) {
                            view.isClickable = false; view.isLongClickable = false
                            if (view is ViewGroup) for (i in 0 until view.childCount) inert(view.getChildAt(i))
                        }
                        inert(preview)
                        frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
                    })
                Button(enabled = WidgetPreviews.canPin(context), onClick = {
                    result = resources.getString(if (WidgetPreviews.pin(context, entry)) R.string.widget_pin_requested else R.string.widget_pin_unsupported)
                }) { Text(stringResource(R.string.widget_pin)) }
            }
        }
    }
    if (!WidgetPreviews.canPin(context)) Text(stringResource(R.string.widget_pin_unsupported))
    result?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
}

@Composable
internal fun NotificationSystemSettings(settings: NotificationSettings) {
    val context = LocalContext.current
    val manager = context.getSystemService(NotificationManager::class.java)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val enabled = remember(revision, settings) { manager.areNotificationsEnabled() }
    Text(stringResource(if (enabled) R.string.notification_system_enabled else R.string.notification_system_disabled),
        style = MaterialTheme.typography.bodyMedium, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
    OutlinedButton(onClick = {
        runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
    }) { Text(stringResource(R.string.notification_system_settings)) }
    if (Build.VERSION.SDK_INT >= 36) {
        val promoted = remember(revision, settings) { manager.canPostPromotedNotifications() }
        Text(stringResource(if (promoted) R.string.notification_promotion_allowed else R.string.notification_promotion_disabled))
        OutlinedButton(onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
        }) { Text(stringResource(R.string.notification_promotion_settings)) }
    }
    listOf("usage_live_updates_v2", "usage_reminders_v2", "codex_reset_notices_v1").forEach { id ->
        val channel = remember(revision, settings) { manager.getNotificationChannel(id) }
        if (channel != null) TextButton(onClick = {
            runCatching { context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, id)) }
        }) { Text("${channel.name} · ${stringResource(if (channel.importance == NotificationManager.IMPORTANCE_NONE) R.string.notification_system_disabled else R.string.notification_system_enabled)}") }
    }
}
