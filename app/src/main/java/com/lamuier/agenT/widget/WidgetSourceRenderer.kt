package com.lamuier.agenT.widget

import android.app.PendingIntent
import android.content.Context
import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.lamuier.agenT.R
import com.lamuier.agenT.data.DashboardPreferences
import com.lamuier.agenT.model.StatusIndicator
import com.lamuier.agenT.util.AppLocale
import com.lamuier.agenT.util.AssistantPresentation
import com.lamuier.agenT.util.DisplayTimeZones
import com.lamuier.agenT.util.StatusPresentation

internal object WidgetSourceRenderer {
    fun render(context: Context, sources: List<WidgetSource>, status: Boolean, width: Int, height: Int,
        capacity: Int, anchor: String?, open: PendingIntent? = null, next: PendingIntent? = null,
        refresh: PendingIntent? = null, notice: String? = null, openSource: (String) -> PendingIntent? = { open }): RemoteViews {
        val strings = AppLocale.wrap(context)
        val colors = WidgetThemeColors.resolve(context)
        val font = context.resources.configuration.fontScale
        val tiny = height < 95 || font > 1.3f && height < 150
        val views = RemoteViews(context.packageName, if (tiny) R.layout.widget_sources_mini else R.layout.widget_sources)
        views.setInt(R.id.widget_surface, "setColorFilter", WidgetAppearance.blend(colors.surface, colors.primary, 0.025f))
        views.setOnClickPendingIntent(R.id.widget_root, open)
        val ids = sources.map { it.id }
        val page = WidgetSourcePolicy.page(ids, anchor, capacity)
        val visible = page.map { id -> sources.first { it.id == id } }
        val indicator = WidgetSourcePolicy.indicator(sources)
        val summary = indicator?.let { StatusPresentation.compactIndicatorLabel(it, strings.resources) }
        val pageCount = (sources.size + capacity - 1) / capacity
        val pageIndex = if (page.isEmpty()) 0 else ids.indexOf(page.first()) / capacity
        val alert = status && indicator !in listOf(null, StatusIndicator.None, StatusIndicator.Unknown)
        views.setViewVisibility(R.id.widget_next_page, if (pageCount > 1) View.VISIBLE else View.GONE)
        views.setTextViewText(R.id.widget_next_page, if (tiny) (if (alert) "!" else "") + if (pageCount > 1) "›" else ""
            else "${pageIndex + 1}/$pageCount ›")
        views.setTextColor(R.id.widget_next_page, if (alert) WidgetCalculations.indicatorColor(requireNotNull(indicator)) else colors.onSurfaceVariant)
        views.setContentDescription(R.id.widget_next_page, listOfNotNull(
            if (status) summary else null, strings.getString(R.string.widget_page_next, pageIndex + 1, pageCount)).joinToString(" · "))
        views.setOnClickPendingIntent(R.id.widget_next_page, if (pageCount > 1) next else open)
        if (!tiny) {
            views.setTextViewText(R.id.widget_multi_title, if (status) summary ?: strings.getString(R.string.status_unavailable_title) else strings.getString(R.string.app_name))
            views.setTextColor(R.id.widget_multi_title, if (status && indicator != null) WidgetCalculations.indicatorColor(indicator) else colors.onSurfaceVariant)
            views.setViewVisibility(R.id.widget_refresh, if (refresh != null) View.VISIBLE else View.GONE)
            views.setImageViewBitmap(R.id.widget_refresh, WidgetVisuals.refreshIconBitmap(context, colors.onSurfaceVariant))
            views.setOnClickPendingIntent(R.id.widget_refresh, refresh)
            views.setContentDescription(R.id.widget_refresh, strings.getString(if (status) R.string.widget_status_refresh_description else R.string.widget_refresh_description))
        }
        views.setViewVisibility(R.id.widget_multi_empty, if (sources.isEmpty()) View.VISIBLE else View.GONE)
        views.setTextColor(R.id.widget_multi_empty, colors.onSurface)
        fit(context, views, R.id.widget_multi_empty, strings.getString(R.string.widget_configure_short), width - 24, 11f)
        views.setViewVisibility(R.id.widget_source_rows, if (sources.isEmpty()) View.GONE else View.VISIBLE)
        views.removeAllViews(R.id.widget_source_rows)
        val columns = when {
            capacity == 4 -> 2
            visible.size == 2 && (tiny || status && width >= 160) -> 2
            else -> 1
        }
        val rowCount = (visible.size + columns - 1) / columns
        val cardHeight = ((height - if (tiny) 4 else 52) / rowCount.coerceAtLeast(1)).coerceAtLeast(20)
        val cardWidth = (width - (if (tiny) 12 + if (pageCount > 1) 30 else 0 else 20)) / columns
        visible.chunked(columns).forEach { rowSources ->
            val row = RemoteViews(context.packageName, R.layout.widget_sources_row)
            rowSources.forEach { source ->
                val card = card(context, source, status, cardWidth, cardHeight, tiny, capacity == 4, notice, colors)
                card.setOnClickPendingIntent(R.id.widget_source_card, openSource(source.id))
                row.addView(R.id.widget_sources_row, card)
            }
            views.addView(R.id.widget_source_rows, row)
        }
        views.setContentDescription(R.id.widget_root, listOfNotNull(if (status) summary else null,
            visible.joinToString(" · ") { source -> source.title + " · " + if (status) {
                source.status?.let { StatusPresentation.indicatorLabel(it.indicator, strings.resources) }.orEmpty() + " · " + source.statusLine
            } else source.metrics.joinToString { "${it.label} ${it.value}" } + " · " + source.note }).joinToString(" · "))
        return views
    }

    private fun card(context: Context, source: WidgetSource, status: Boolean, width: Int, height: Int,
        tiny: Boolean, grid: Boolean, notice: String?, colors: WidgetThemeColors): RemoteViews {
        val strings = AppLocale.wrap(context)
        val views = RemoteViews(context.packageName, R.layout.widget_source_card)
        val font = context.resources.configuration.fontScale
        val zone = DisplayTimeZones.resolve(DashboardPreferences.get(context).readTimeZoneId())
        val textWidth = (width - if (tiny) 2 else 10).coerceAtLeast(10)
        if (grid) views.setInt(R.id.widget_source_card, "setGravity", android.view.Gravity.TOP)
        val padding = if (tiny) 0 else if (height < 100) 1 else 3
        val density = context.resources.displayMetrics.density
        views.setViewPadding(R.id.widget_source_card, if (tiny) 0 else (5 * density).toInt(), (padding * density).toInt(),
            if (tiny) 0 else (5 * density).toInt(), (padding * density).toInt())
        val tinyScale = if (tiny) minOf(1f, (height - 1f) / (lineHeight(context, 9f) + lineHeight(context, 13f))) else 1f
        fit(context, views, R.id.widget_source_name, source.title, textWidth, if (tiny) 9f * tinyScale else 12f)
        views.setTextColor(R.id.widget_source_name, colors.onSurface)
        views.setTextColor(R.id.widget_source_note, colors.onSurfaceVariant)
        views.setTextColor(R.id.widget_source_clock, colors.onSurfaceVariant)
        views.removeAllViews(R.id.widget_source_metrics)
        val currentStatus = if (status) WidgetSourcePolicy.indicator(listOf(source)) else null
        var metrics = if ((tiny || height < 100) && !status && source.attention != null) listOf(WidgetMetric("", source.attention)) else if (status) listOf(WidgetMetric("", currentStatus?.let {
            StatusPresentation.compactIndicatorLabel(it, strings.resources)
        } ?: strings.getString(R.string.status_unavailable_title))) else source.metrics.ifEmpty { listOf(WidgetMetric("", "—")) }
        val incident = if (status && source.status?.let { it.activeIncidents.isNotEmpty() || it.scheduledMaintenances.isNotEmpty() || it.recentIncidents.isNotEmpty() } == true)
            WidgetStatusPresentation.incident(source.status, strings.resources, zone, true) else null
        val note = listOfNotNull(notice, if (status) source.statusLine.takeIf { source.status == null }
            else (source.attention ?: source.note).takeIf(String::isNotBlank), incident).joinToString(" · ")
        val valueSize = if (tiny) 13f * tinyScale else if (!grid && height >= 220 && font <= 1.3f) 18f else 14f
        val baseHeight = if (tiny) lineHeight(context, 9f * tinyScale) else lineHeight(context, 12f) + lineHeight(context, 9f) + padding * 2
        val firstMetricHeight = lineHeight(context, valueSize) + if (!tiny && metrics.first().percent != null) 7 else 0
        val metadataBudget = (height - baseHeight - firstMetricHeight).coerceAtLeast(0)
        val showNote = !tiny && note.isNotEmpty() && metadataBudget >= lineHeight(context, 9f)
        if (!showNote && !status && source.attention != null) metrics = listOf(WidgetMetric("", source.attention))
        val showResets = !tiny && !grid && metadataBudget - (if (showNote) lineHeight(context, 9f) else 0) >= lineHeight(context, 9f)
        val titleAndFoot = baseHeight + if (showNote) lineHeight(context, 9f) else 0
        fun metricHeight(metric: WidgetMetric) = lineHeight(context, valueSize) +
            (if (!tiny && metric.percent != null) 7 else 0) + (if (showResets && metric.reset != null) lineHeight(context, 9f) else 0)
        var used = titleAndFoot
        val shown = metrics.take(if (tiny) 1 else if (grid) 2 else 4).takeWhile { metric ->
            val fits = used + metricHeight(metric) <= height
            if (fits) used += metricHeight(metric)
            fits
        }.ifEmpty { metrics.take(1) }
        val limit = shown.size
        shown.forEach { metric ->
            val row = RemoteViews(context.packageName, R.layout.widget_source_metric)
            val text = listOf(metric.label.takeUnless { tiny && source.id == "cursor" }, metric.value).filterNotNull().filter(String::isNotEmpty).joinToString(" ")
            fit(context, row, R.id.widget_metric_value, text, textWidth,
                valueSize)
            val accent = if (status) currentStatus?.let(WidgetCalculations::indicatorColor) ?: colors.onSurfaceVariant
                else if (source.id == "cursor" || source.id == "deepseek") colors.primary else colors.tertiary
            row.setTextColor(R.id.widget_metric_value, accent)
            val showBar = !tiny && metric.percent != null
            row.setViewVisibility(R.id.widget_metric_bar, if (showBar) View.VISIBLE else View.GONE)
            if (showBar) row.setImageViewBitmap(R.id.widget_metric_bar, WidgetVisuals.quotaBarBitmap(metric.percent,
                WidgetAppearance.accent(metric.percent, accent, colors.onSurfaceVariant, (colors.surface ushr 16 and 255) < 128),
                WidgetAppearance.blend(colors.surface, accent, 0.10f)))
            row.setViewVisibility(R.id.widget_metric_reset, if (showResets && metric.reset != null) View.VISIBLE else View.GONE)
            row.setTextViewText(R.id.widget_metric_reset, metric.reset.orEmpty())
            row.setTextColor(R.id.widget_metric_reset, colors.onSurfaceVariant)
            views.addView(R.id.widget_source_metrics, row)
        }
        val spare = (height - titleAndFoot - shown.sumOf(::metricHeight)).coerceAtLeast(0)
        val components = if (status && !tiny) WidgetStatusPresentation.components(source.status,
            (spare / lineHeight(context, 10f)).coerceIn(0, if (grid) 2 else 8)) else emptyList()
        components.forEach { component ->
            val row = RemoteViews(context.packageName, R.layout.widget_source_metric)
            val text = WidgetStatusPresentation.componentName(source.title, component.name)
            fit(context, row, R.id.widget_metric_value, text, textWidth, 10f)
            row.setTextColor(R.id.widget_metric_value, WidgetCalculations.componentColor(component.status))
            row.setViewVisibility(R.id.widget_metric_bar, View.GONE)
            row.setViewVisibility(R.id.widget_metric_reset, View.GONE)
            row.setContentDescription(R.id.widget_metric, "$text · ${StatusPresentation.componentLabel(component.status, strings.resources)}")
            views.addView(R.id.widget_source_metrics, row)
        }
        val extra = if (status) (source.status?.components?.size ?: 0) - components.size else metrics.size - minOf(limit, metrics.size)
        if (tiny && extra > 0) {
            fit(context, views, R.id.widget_source_name, "${source.title} +$extra", textWidth, 9f * tinyScale)
            views.setContentDescription(R.id.widget_source_name, source.title + " · " + strings.getString(R.string.widget_more_items, extra))
        }
        views.setViewVisibility(R.id.widget_source_note, if (showNote) View.VISIBLE else View.GONE)
        views.setInt(R.id.widget_source_note, "setMaxLines", 1)
        fit(context, views, R.id.widget_source_note, note.replace('\n', ' '), textWidth, 9f, truncate = true)
        val stamp = if (status) source.status?.fetchedAt else source.fetchedAt
        val clock = AssistantPresentation.updatedClock(stamp, zone) ?: "—"
        val cached = if (status) source.status?.fromCache != false else source.cached
        views.setViewVisibility(R.id.widget_source_clock, if (tiny) View.GONE else View.VISIBLE)
        val time = strings.getString(if (cached) R.string.widget_status_cached_clock else R.string.widget_updated_time, clock)
        fit(context, views, R.id.widget_source_clock, time + if (extra > 0) " · +$extra" else "", textWidth, 9f)
        views.setContentDescription(R.id.widget_source_clock, time + if (extra > 0) " · " + strings.getString(R.string.widget_more_items, extra) else "")
        return views
    }

    private val lineHeightCache = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val measurePaint = object : ThreadLocal<Paint>() {
        override fun initialValue(): Paint = Paint()
    }

    private fun lineHeight(context: Context, sizeSp: Float): Int {
        val metrics = context.resources.displayMetrics
        val key = (metrics.density.toRawBits() * 31 + context.resources.configuration.fontScale.toRawBits()) * 31 + sizeSp.toRawBits()
        lineHeightCache[key]?.let { return it }
        val text = "用量 Ag"
        val paint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, metrics)
        }
        val builder = android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, 10_000)
            .setIncludePad(false).setMaxLines(1)
        if (android.os.Build.VERSION.SDK_INT >= 28) builder.setUseLineSpacingFromFallbacks(true)
        return kotlin.math.ceil(builder.build().height / metrics.density.toDouble()).toInt().coerceAtLeast(1).also {
            if (lineHeightCache.size > 128) lineHeightCache.clear()
            lineHeightCache[key] = it
        }
    }

    /** Keep amounts and source names complete rather than relying on ellipsis or host clipping. */
    private fun fit(context: Context, views: RemoteViews, id: Int, text: String, widthDp: Int, sizeSp: Float, truncate: Boolean = false) {
        val metrics = context.resources.displayMetrics
        val wanted = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, metrics)
        val paint = measurePaint.get()!!.apply { textSize = wanted }
        val available = widthDp * metrics.density
        val full = paint.measureText(text)
        val display = if (truncate && full > available) text.take(paint.breakText(text, true, (available - paint.measureText("…")).coerceAtLeast(0f), null)) + "…" else text
        val measured = paint.measureText(display).coerceAtLeast(1f)
        val size = minOf(wanted, wanted * widthDp * metrics.density / measured)
        views.setTextViewText(id, display)
        views.setContentDescription(id, text)
        views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_PX, size)
    }
}
