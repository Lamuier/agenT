package com.lamuier.agenT.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import com.lamuier.agenT.R
import com.lamuier.agenT.data.ThemePreferences
import com.lamuier.agenT.util.AppLocale
import kotlinx.coroutines.*

internal object WidgetPreviews {
    data class Entry(val provider: Class<*>, val title: Int, val columns: Int, val rows: Int)
    val entries = listOf(
        Entry(MiniCursorWidgetProvider::class.java, R.string.widget_mini_name, 2, 1),
        Entry(TallCursorWidgetProvider::class.java, R.string.widget_tall_name, 4, 3),
        Entry(MiniCursorStatusWidgetProvider::class.java, R.string.widget_status_mini_name, 2, 1),
        Entry(SquareCursorStatusWidgetProvider::class.java, R.string.widget_status_square_name, 2, 2),
        Entry(WideCursorStatusWidgetProvider::class.java, R.string.widget_status_wide_name, 4, 1),
        Entry(MediumCursorStatusWidgetProvider::class.java, R.string.widget_status_medium_name, 4, 2),
        Entry(TallCursorStatusWidgetProvider::class.java, R.string.widget_status_tall_name, 4, 3),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var running = false
    fun canPin(context: Context) = context.getSystemService(AppWidgetManager::class.java).isRequestPinAppWidgetSupported
    fun preview(context: Context, entry: Entry): RemoteViews = AgenTWidgetUpdater.samplePreview(AppLocale.wrap(context), entry.provider)
    fun pin(context: Context, entry: Entry): Boolean = runCatching {
        val manager = context.getSystemService(AppWidgetManager::class.java)
        manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(ComponentName(context, entry.provider),
            Bundle().apply { putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, preview(context, entry)) }, null)
    }.getOrDefault(false)
    @Synchronized fun register(context: Context) {
        if (Build.VERSION.SDK_INT < 35 || running) return
        val app = context.applicationContext
        val preferences = app.getSharedPreferences("agentt_widget_previews_v1", Context.MODE_PRIVATE)
        val key = "4:${AppLocale.stored(app)}:${WidgetThemeColors.resolve(app)}:${ThemePreferences.get(app).read()}:${app.resources.configuration.uiMode}:${app.resources.configuration.fontScale}"
        if (preferences.getString("rendered", null) == key) return
        running = true
        scope.launch {
            try {
                val manager = app.getSystemService(AppWidgetManager::class.java)
                var allAccepted = true
                entries.forEach { entry ->
                    allAccepted = runCatching { manager.setWidgetPreview(ComponentName(app, entry.provider),
                        android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, preview(app, entry)) }.getOrDefault(false) && allAccepted
                }
                if (allAccepted) preferences.edit().putString("rendered", key).apply()
            } finally { synchronized(this@WidgetPreviews) { running = false } }
        }
    }
}
