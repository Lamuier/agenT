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

    /** 页脚的一个来源：名称、数据时间与这一轮是否读自缓存。 */
    data class Stamp(val name: String, val at: String?, val cached: Boolean)

    /**
     * 页脚给出的时间：[stamp] 与是否整块标“缓存”；[stale] 是过了新鲜期仍在用缓存的来源名（按传入顺序），
     * [staleStamp] 是其中最旧的时间。
     */
    data class Footer(val stamp: String?, val cached: Boolean, val stale: List<String> = emptyList(), val staleStamp: String? = null)

    /**
     * 页脚时间。没有数据的来源（如需要更新连接）不参与判定，否则它会让整块组件永远显示“缓存”；
     * 新鲜期内读出的缓存视同刚更新，否则刚拉完、由下一轮从缓存读出的数据也会标成“缓存”，看起来像没刷新。
     * 只要还有来源在新鲜期内，就显示其中最近的更新时间，过期的来源单独列出：一个来源一直失败，
     * 不能让其他刚更新的来源跟着显示成“缓存”。全部过期时整块标“缓存”，取其中最旧的时间。
     */
    fun footer(stamps: List<Stamp>, now: java.time.Instant = java.time.Instant.now(),
        freshSeconds: Long = CACHE_FRESH_SECONDS.toLong()): Footer {
        val known = stamps.mapNotNull { stamp -> com.lamuier.agenT.util.DisplayTime.parseStoredLocal(stamp.at)?.let { stamp to it } }
        if (known.isEmpty()) return Footer(null, stamps.any { it.cached })
        val (stale, fresh) = known.partition { (stamp, at) -> stamp.cached && java.time.Duration.between(at, now).seconds >= freshSeconds }
        val oldest = stale.minByOrNull { it.second }?.first?.at
        if (fresh.isEmpty()) return Footer(oldest, cached = true)
        return Footer(fresh.maxBy { it.second }.first.at, cached = false, stale.map { it.first.name }, oldest)
    }

    /** 单个来源的时间是否标“缓存”：读自缓存且过了新鲜期；时间读不出来时按 [cached]。 */
    fun isStale(stamp: String?, cached: Boolean, now: java.time.Instant = java.time.Instant.now(),
        freshSeconds: Long = CACHE_FRESH_SECONDS.toLong()): Boolean = footer(listOf(Stamp("", stamp, cached)), now, freshSeconds).cached

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
