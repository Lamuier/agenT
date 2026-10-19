package com.lamuier.agenT.widget

import android.content.Context
import com.lamuier.agenT.model.ServiceStatus
import com.lamuier.agenT.model.StatusIndicator
import com.lamuier.agenT.providers.ProviderUsageRepository

/** [caption] is the short secondary text under a ring gauge, such as a reset clock or remaining days. */
internal data class WidgetMetric(
    val label: String,
    val value: String,
    val percent: Double? = null,
    val reset: String? = null,
    val caption: String? = null,
)
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
    /** Granted and topped-up amounts when [metrics] is `[total, granted, toppedUp]` for one currency. */
    val split: List<Double>? = null,
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

internal data class WidgetPlacement(
    val columns: Int,
    val spanLast: Boolean,
    val stackCompact: Boolean,
)

internal object WidgetSourcePolicy {
    val order = listOf("cursor", "codex", "deepseek", "kimi_code")

    /** Clock time for a reset later today, otherwise the month and day. */
    fun shortReset(epochSecond: Long, zone: java.time.ZoneId, now: java.time.Instant = java.time.Instant.now()): String {
        val moment = java.time.Instant.ofEpochSecond(epochSecond).atZone(zone)
        val pattern = if (moment.toLocalDate() == now.atZone(zone).toLocalDate()) "HH:mm" else "MM/dd"
        return moment.format(java.time.format.DateTimeFormatter.ofPattern(pattern))
    }

    fun capacity(kind: String, width: Int, height: Int, font: Float, count: Int): Int {
        if (kind == "Mini" || kind == "StatusMini" || font > 1.3f) return 1
        if (kind in listOf("Tall", "StatusTall") && height < 220) return 1
        if (kind == "StatusSquare" && width < 160) return 1
        if (kind in listOf("Tall", "StatusTall") && count > 2 && width >= 330 && height >= 240) return 4
        return if (width >= 210 || height >= 100) 2 else 1
    }

    /** Place the sources already chosen for this page. A lone card never keeps a weighted half-column. */
    fun placement(visible: Int, capacity: Int, width: Int, tiny: Boolean, status: Boolean): WidgetPlacement {
        if (visible <= 1) return WidgetPlacement(1, spanLast = false, stackCompact = false)
        val wideSummary = capacity >= 4 && width >= 300 && !tiny
        val stackCompact = wideSummary && status && visible == 3
        val columns = when {
            stackCompact -> 1
            wideSummary && !status && visible == 3 -> 3
            wideSummary && visible >= 3 -> 2
            visible == 2 && (tiny || status && width >= 160) -> 2
            else -> 1
        }
        val spanLast = columns > 1 && visible % columns != 0
        return WidgetPlacement(columns, spanLast, stackCompact)
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
