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
        val placement = WidgetSourcePolicy.placement(visible.size, capacity, width, tiny, status)
        val chunks = visible.chunked(placement.columns)
        val contentHeight = (height - if (tiny) 4 else 52).coerceAtLeast(20)
        val rowHeight = (contentHeight / chunks.size.coerceAtLeast(1)).coerceAtLeast(20)
        val horizontalInset = if (tiny) 12 + if (pageCount > 1) 30 else 0 else 20
        val compactGrid = visible.size >= 4
        chunks.forEach { rowSources ->
            val row = RemoteViews(context.packageName, R.layout.widget_sources_row)
            val span = placement.stackCompact || rowSources.size == 1 && placement.spanLast
            val mode = when {
                span -> CardMode.Span
                rowSources.size == 1 -> CardMode.Fill
                else -> CardMode.Cell
            }
            val cardWidth = if (rowSources.size == 1) width - horizontalInset else (width - horizontalInset) / placement.columns
            rowSources.forEach { source ->
                val card = card(context, source, status, cardWidth, rowHeight, tiny, compactGrid && mode == CardMode.Cell,
                    mode, notice, colors)
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

    private enum class CardMode { Cell, Fill, Span }

    private fun card(context: Context, source: WidgetSource, status: Boolean, width: Int, height: Int,
        tiny: Boolean, grid: Boolean, mode: CardMode, notice: String?, colors: WidgetThemeColors): RemoteViews {
        val strings = AppLocale.wrap(context)
        val span = mode == CardMode.Span
        val views = RemoteViews(context.packageName, when (mode) {
            CardMode.Span -> R.layout.widget_source_card_span
            CardMode.Fill -> R.layout.widget_source_card_fill
            CardMode.Cell -> R.layout.widget_source_card
        })
        val font = context.resources.configuration.fontScale
        val zone = DisplayTimeZones.resolve(DashboardPreferences.get(context).readTimeZoneId())
        val textWidth = (width - if (tiny) 2 else 10).coerceAtLeast(10)
        val padding = if (tiny) 0 else if (span || height < 100) 1 else 3
        val density = context.resources.displayMetrics.density
        views.setViewPadding(R.id.widget_source_card, if (tiny) 0 else (5 * density).toInt(), (padding * density).toInt(),
            if (tiny) 0 else (5 * density).toInt(), (padding * density).toInt())
        val tinyScale = if (tiny) minOf(1f, (height - 1f) / (lineHeight(context, 9f) + lineHeight(context, 13f))) else 1f
        val nameSize = if (tiny) 9f * tinyScale else 12f
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
        val inlineStatus = span && status
        val inlineBalances = span && !status && metrics.all { it.percent == null }
        val aside = when {
            inlineStatus -> metrics.firstOrNull()?.let { metric -> metric.value.ifBlank { metric.label } }.orEmpty()
            inlineBalances -> metrics.joinToString(" · ") { listOf(it.label, it.value).filter(String::isNotEmpty).joinToString(" ") }
            else -> ""
        }
        val stacked = !tiny && !status && !grid && !span && height >= 140 && font <= 1.3f
        val splitParts = source.split?.takeIf { stacked && it.size == 2 && source.metrics.size == 3 }
        val gauged = stacked && (splitParts != null || source.metrics.any { it.percent != null })
        // Rings carry the numbers, so the column note (cycle total, availability) only stays for notices and errors.
        val visibleNote = if (gauged) listOfNotNull(notice, source.attention).joinToString(" · ")
            else if (aside.isNotBlank() && note == aside) "" else note
        val body = if (inlineStatus || inlineBalances) emptyList() else metrics
        val valueSize = if (tiny) 13f * tinyScale else if (stacked) 20f else 14f
        val labelHeight = if (stacked) lineHeight(context, 10f) else 0
        val baseHeight = when {
            tiny -> lineHeight(context, 9f * tinyScale)
            span -> lineHeight(context, 12f) + padding * 2
            else -> lineHeight(context, 12f) + lineHeight(context, 9f) + padding * 2
        }
        val firstMetricHeight = if (gauged) MIN_RING + lineHeight(context, 9f) + 4 else
            body.firstOrNull()?.let { labelHeight + lineHeight(context, valueSize) + if (!tiny && it.percent != null) 9 else 0 } ?: 0
        val metadataBudget = (height - baseHeight - firstMetricHeight).coerceAtLeast(0)
        val showNote = !tiny && visibleNote.isNotEmpty() && metadataBudget >= lineHeight(context, 9f)
        if (!showNote && !status && source.attention != null && !inlineBalances) metrics = listOf(WidgetMetric("", source.attention))
        val noteText = visibleNote.replace('\n', ' ')
        val noteLines = if (!showNote) 0 else if ((span || height >= 120) && textWidthDp(context, noteText, 9f) > textWidth &&
            metadataBudget >= lineHeight(context, 9f) * 2) 2 else 1
        val showResets = !tiny && !grid && !span && metadataBudget - noteLines * lineHeight(context, 9f) >= lineHeight(context, 9f)
        val titleAndFoot = baseHeight + noteLines * lineHeight(context, 9f)
        fun metricHeight(metric: WidgetMetric) = (if (metric.label.isNotEmpty()) labelHeight else 0) + lineHeight(context, valueSize) +
            (if (!tiny && metric.percent != null) 9 else 0) + (if (showResets && metric.reset != null) lineHeight(context, 9f) else 0)
        val displayBody = if (!showNote && !status && source.attention != null && !inlineBalances) metrics else body
        val gaugeActive = gauged && displayBody === body
        val gaugeItems = when {
            !gaugeActive -> emptyList()
            splitParts != null -> listOf(source.metrics[0])
            else -> displayBody.filter { it.percent != null }
        }
        val captionLines = if (splitParts != null) 2 else 1
        val gaugeBudget = height - baseHeight - noteLines * lineHeight(context, 9f) - 8
        fun ringFor(count: Int) = minOf(MAX_RING, textWidth - 4, gaugeBudget / count - captionLines * lineHeight(context, 9f) - 4)
        val gaugeCount = (minOf(gaugeItems.size, 4) downTo 1).firstOrNull { ringFor(it) >= MIN_RING } ?: minOf(gaugeItems.size, 1)
        val ring = if (gaugeCount > 0) ringFor(gaugeCount).coerceAtLeast(28) else 0
        var used = titleAndFoot
        val shown = if (gaugeCount > 0) gaugeItems.take(gaugeCount) else if (displayBody.isEmpty()) emptyList() else displayBody.take(if (tiny) 1 else if (grid || span) 2 else 4).takeWhile { metric ->
            val fits = used + metricHeight(metric) <= height
            if (fits) used += metricHeight(metric)
            fits
        }.ifEmpty { displayBody.take(1) }
        val accent = if (status) currentStatus?.let(WidgetCalculations::indicatorColor) ?: colors.onSurfaceVariant
            else if (source.id == "cursor" || source.id == "deepseek") colors.primary else colors.tertiary
        val spaced = gaugeCount == 0 && !tiny && !status && !span && shown.size > 1
        if (gaugeCount > 0) {
            val gap = R.layout.widget_source_gap
            views.addView(R.id.widget_source_metrics, RemoteViews(context.packageName, gap))
            shown.forEach { metric ->
                views.addView(R.id.widget_source_metrics, gaugeRow(context, metric, ring, textWidth, accent, colors, splitParts, source.metrics))
                views.addView(R.id.widget_source_metrics, RemoteViews(context.packageName, gap))
            }
        }
        shown.takeIf { gaugeCount == 0 }.orEmpty().forEachIndexed { index, metric ->
            val row = RemoteViews(context.packageName, R.layout.widget_source_metric)
            val label = metric.label.takeUnless { tiny && source.id == "cursor" }.orEmpty()
            val text = if (stacked) metric.value else listOf(label, metric.value).filter(String::isNotEmpty).joinToString(" ")
            if (stacked && label.isNotEmpty()) {
                row.setViewVisibility(R.id.widget_metric_label, View.VISIBLE)
                fit(context, row, R.id.widget_metric_label, label, textWidth, 10f)
                row.setTextColor(R.id.widget_metric_label, colors.onSurfaceVariant)
                row.setContentDescription(R.id.widget_metric, "$label ${metric.value}")
            }
            fit(context, row, R.id.widget_metric_value, text, textWidth, valueSize)
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
            if (spaced && index < shown.lastIndex) views.addView(R.id.widget_source_metrics, RemoteViews(context.packageName, R.layout.widget_source_gap))
        }
        if (spaced) views.addView(R.id.widget_source_metrics, RemoteViews(context.packageName, R.layout.widget_source_gap))
        val spare = (height - titleAndFoot - shown.sumOf(::metricHeight)).coerceAtLeast(0)
        val components = if (status && !tiny) WidgetStatusPresentation.components(source.status,
            (spare / lineHeight(context, 10f)).coerceIn(0, if (grid && !span) 2 else 8)) else emptyList()
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
        val extra = if (status) (source.status?.components?.size ?: 0) - components.size
            else if (inlineBalances || gaugeCount > 0 && splitParts != null) 0 else metrics.size - shown.size
        if (!span || tiny) fit(context, views, R.id.widget_source_name, source.title, textWidth, nameSize)
        if (tiny && extra > 0) {
            fit(context, views, R.id.widget_source_name, "${source.title} +$extra", textWidth, 9f * tinyScale)
            views.setContentDescription(R.id.widget_source_name, source.title + " · " + strings.getString(R.string.widget_more_items, extra))
        }
        views.setViewVisibility(R.id.widget_source_note, if (showNote) View.VISIBLE else View.GONE)
        views.setInt(R.id.widget_source_note, "setMaxLines", noteLines.coerceAtLeast(1))
        if (noteLines <= 1) fit(context, views, R.id.widget_source_note, noteText, textWidth, 9f, truncate = true)
        else {
            views.setTextViewText(R.id.widget_source_note, noteText)
            views.setContentDescription(R.id.widget_source_note, noteText)
            views.setTextViewTextSize(R.id.widget_source_note, TypedValue.COMPLEX_UNIT_SP, 9f)
        }
        val stamp = if (status) source.status?.fetchedAt else source.fetchedAt
        val clock = AssistantPresentation.updatedClock(stamp, zone) ?: "—"
        val cached = if (status) source.status?.fromCache != false else source.cached
        views.setViewVisibility(R.id.widget_source_clock, if (tiny) View.GONE else View.VISIBLE)
        val time = strings.getString(if (cached) R.string.widget_status_cached_clock else R.string.widget_updated_time, clock)
        val clockLabel = time + if (extra > 0) " · +$extra" else ""
        val clockWidth = if (span) minOf(textWidthDp(context, clockLabel, 9f), (textWidth * 0.46f).toInt()).coerceAtLeast(32) else textWidth
        fit(context, views, R.id.widget_source_clock, clockLabel, clockWidth, 9f)
        views.setContentDescription(R.id.widget_source_clock, time + if (extra > 0) " · " + strings.getString(R.string.widget_more_items, extra) else "")
        if (span) {
            val asideWidth = if (aside.isBlank()) 0 else minOf(textWidthDp(context, aside, 14f), (textWidth * 0.5f).toInt()) + 8
            val nameWidth = (textWidth - clockWidth - asideWidth).coerceAtLeast(28)
            fit(context, views, R.id.widget_source_name, source.title, nameWidth, nameSize)
            if (aside.isBlank()) views.setViewVisibility(R.id.widget_source_aside, View.GONE)
            else {
                views.setViewVisibility(R.id.widget_source_aside, View.VISIBLE)
                fit(context, views, R.id.widget_source_aside, aside, (textWidth - clockWidth - 28).coerceAtLeast(28), 14f)
                views.setTextColor(R.id.widget_source_aside, accent)
            }
        }
        return views
    }

    private const val MIN_RING = 36
    private const val MAX_RING = 64

    /** A ring with the value centred inside and a one-line caption (or a two-line legend for split balances) below. */
    private fun gaugeRow(context: Context, metric: WidgetMetric, ring: Int, textWidth: Int, accent: Int,
        colors: WidgetThemeColors, split: List<Double>?, all: List<WidgetMetric>): RemoteViews {
        val row = RemoteViews(context.packageName, R.layout.widget_source_gauge)
        val dark = (colors.surface ushr 16 and 255) < 128
        val stroke = (ring * 0.11f).coerceIn(4f, 7f)
        val inner = (ring - 2 * stroke - 6).toInt().coerceAtLeast(12)
        val valueSize = (ring * 0.24f).coerceIn(9f, 14f)
        val track = WidgetAppearance.blend(colors.surface, accent, 0.10f)
        if (split == null) {
            val percent = metric.percent ?: 0.0
            val fill = WidgetAppearance.accent(metric.percent, accent, colors.onSurfaceVariant, dark)
            row.setImageViewBitmap(R.id.widget_gauge_ring, WidgetVisuals.donutBitmap(context, ring,
                kotlin.math.round(percent).toInt(), fill, track, stroke))
            fit(context, row, R.id.widget_gauge_value, metric.value, inner, valueSize)
            row.setTextColor(R.id.widget_gauge_value, colors.onSurface)
            val caption = listOfNotNull(metric.label.takeIf(String::isNotEmpty), metric.caption).joinToString(" · ")
            fit(context, row, R.id.widget_gauge_caption, caption, textWidth, 9f)
            row.setTextColor(R.id.widget_gauge_caption, colors.onSurfaceVariant)
            row.setContentDescription(R.id.widget_gauge, listOf(metric.label, metric.value, metric.caption.orEmpty())
                .filter(String::isNotEmpty).joinToString(" "))
            return row
        }
        val roleColors = listOf(colors.tertiary, colors.primary)
        row.setImageViewBitmap(R.id.widget_gauge_ring, WidgetVisuals.splitDonutBitmap(context, ring,
            split.map { it.toFloat() }, roleColors, track, stroke))
        val unit = metric.label
        val center = android.text.SpannableStringBuilder().apply {
            if (unit.isNotEmpty()) {
                append(unit).append('\n')
                setSpan(android.text.style.RelativeSizeSpan(0.62f), 0, unit.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            append(metric.value)
        }
        fit(context, row, R.id.widget_gauge_value, metric.value, inner, valueSize)
        row.setTextViewText(R.id.widget_gauge_value, center)
        row.setTextColor(R.id.widget_gauge_value, colors.onSurface)
        val parts = all.drop(1)
        val lines = parts.map { listOf(it.label, it.value).filter(String::isNotEmpty).joinToString(" ") }
        val legend = android.text.SpannableStringBuilder()
        lines.forEachIndexed { index, line ->
            if (index > 0) legend.append('\n')
            val start = legend.length
            legend.append("●").append(' ').append(line)
            legend.setSpan(android.text.style.ForegroundColorSpan(roleColors[index.coerceAtMost(1)]), start, start + 1,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val widest = lines.maxOfOrNull { textWidthDp(context, "● $it", 9f) } ?: 0
        val legendSize = if (widest > textWidth) 9f * textWidth / widest else 9f
        row.setTextViewText(R.id.widget_gauge_caption, legend)
        row.setTextViewTextSize(R.id.widget_gauge_caption, TypedValue.COMPLEX_UNIT_SP, legendSize)
        row.setTextColor(R.id.widget_gauge_caption, colors.onSurfaceVariant)
        row.setContentDescription(R.id.widget_gauge, listOf(unit, metric.value, lines.joinToString(" ")).filter(String::isNotEmpty).joinToString(" "))
        return row
    }

    private val lineHeightCache = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val measurePaint = object : ThreadLocal<Paint>() {
        override fun initialValue(): Paint = Paint()
    }

    private fun textWidthDp(context: Context, text: String, sizeSp: Float): Int {
        val metrics = context.resources.displayMetrics
        val paint = measurePaint.get()!!.apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, metrics)
        }
        return kotlin.math.ceil(paint.measureText(text) / metrics.density.toDouble()).toInt().coerceAtLeast(0)
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
        val available = (widthDp * metrics.density - 2f).coerceAtLeast(1f)
        val full = paint.measureText(text)
        val display = if (truncate && full > available) text.take(paint.breakText(text, true, (available - paint.measureText("…")).coerceAtLeast(0f), null)) + "…" else text
        val measured = paint.measureText(display).coerceAtLeast(1f)
        val size = minOf(wanted, wanted * available / measured)
        views.setTextViewText(id, display)
        views.setContentDescription(id, text)
        views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_PX, size)
    }
}
