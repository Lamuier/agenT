package com.lamuier.agenT.widget

import android.content.Context
import com.lamuier.agenT.model.ServiceStatus
import com.lamuier.agenT.model.StatusIndicator
import com.lamuier.agenT.providers.ProviderUsageRepository

internal data class WidgetMetric(val label: String, val value: String, val percent: Double? = null, val reset: String? = null)
internal data class WidgetSource(
    val id: String,
    val title: String,
    val metrics: List<WidgetMetric> = emptyList(),
    val note: String = "",
    val fetchedAt: String? = null,
    val cached: Boolean = true,
    val status: ServiceStatus? = null,
    val statusLine: String = "",
    val attention: String? = null,
)

/** A source anchor survives capacity changes without storing account data in widget preferences. */
internal class WidgetPages(context: Context) {
    private val preferences = context.getSharedPreferences("widget-source-pages-v1", Context.MODE_PRIVATE)
    fun anchor(widgetId: Int): String? = preferences.getString(widgetId.toString(), null)
    fun select(widgetId: Int, sourceId: String?) {
        preferences.edit().apply {
            if (sourceId == null) remove(widgetId.toString()) else putString(widgetId.toString(), sourceId)
        }.apply()
    }
    fun advance(widgetId: Int, ids: List<String>, capacity: Int) = synchronized(lock) {
        select(widgetId, WidgetSourcePolicy.next(ids, anchor(widgetId), capacity))
    }
    private companion object { val lock = Any() }
    fun clear(ids: IntArray) { preferences.edit().apply { ids.forEach { remove(it.toString()) } }.apply() }
}

internal object WidgetSourcePolicy {
    val order = listOf("cursor", "codex", "deepseek", "kimi_code", "antigravity")

    fun capacity(kind: String, width: Int, height: Int, font: Float, count: Int): Int {
        if (kind == "Mini" || kind == "StatusMini" || font > 1.3f) return 1
        if (kind in listOf("Tall", "StatusTall") && height < 220) return 1
        if (kind == "StatusSquare" && width < 160) return 1
        if (kind in listOf("Tall", "StatusTall") && count > 2 && width >= 330 && height >= 240) return 4
        return if (width >= 210 || height >= 100) 2 else 1
    }

    fun page(ids: List<String>, anchor: String?, capacity: Int): List<String> {
        if (ids.isEmpty()) return emptyList()
        val start = ids.indexOf(anchor).coerceAtLeast(0) / capacity.coerceAtLeast(1) * capacity.coerceAtLeast(1)
        return ids.drop(start).take(capacity.coerceAtLeast(1))
    }

    fun next(ids: List<String>, anchor: String?, capacity: Int): String? {
        val current = page(ids, anchor, capacity)
        if (current.isEmpty()) return null
        return ids[(ids.indexOf(current.first()) + capacity.coerceAtLeast(1)).takeIf { it < ids.size } ?: 0]
    }

    fun indicator(sources: List<WidgetSource>): StatusIndicator? {
        if (sources.isEmpty()) return null
        val states = sources.map { source ->
            val status = source.status
            if (status == null || status.partialComponents && status.indicator == StatusIndicator.None || status.indicator == StatusIndicator.Unknown ||
                ProviderUsageRepository.ageSeconds(status.fetchedAt) >= 15 * 60) StatusIndicator.Unknown else status.indicator
        }
        val order = listOf(StatusIndicator.None, StatusIndicator.Unknown, StatusIndicator.Maintenance,
            StatusIndicator.Minor, StatusIndicator.Major, StatusIndicator.Critical)
        return states.maxByOrNull(order::indexOf)
    }
}
