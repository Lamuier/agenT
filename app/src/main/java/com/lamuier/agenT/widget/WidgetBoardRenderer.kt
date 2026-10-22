package com.lamuier.agenT.widget

import android.app.PendingIntent
import android.content.Context
import android.content.res.Resources
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import com.lamuier.agenT.R
import com.lamuier.agenT.data.DashboardPreferences
import com.lamuier.agenT.model.ComponentStatus
import com.lamuier.agenT.model.StatusIncident
import com.lamuier.agenT.model.StatusIndicator
import com.lamuier.agenT.model.StatusWidgetStyle
import com.lamuier.agenT.util.AppLocale
import com.lamuier.agenT.util.AssistantPresentation
import com.lamuier.agenT.util.DisplayTime
import com.lamuier.agenT.util.DisplayTimeZones
import com.lamuier.agenT.util.StatusPresentation
import com.lamuier.agenT.util.UsageCalculations
import com.lamuier.agenT.widget.WidgetSourceRenderer.fit
import com.lamuier.agenT.widget.WidgetSourceRenderer.lineHeight
import com.lamuier.agenT.widget.WidgetSourceRenderer.textWidthDp
import java.time.ZoneId

/**
 * Full-board layouts for the 4×3 widgets: every configured source on one page, with a single focal headline.
 * Each entry returns null when the host is too small, so callers fall back to the paged card layout.
 */
internal object WidgetBoardRenderer {
    private const val MIN_WIDTH = 250
    private const val MIN_HEIGHT = 200
    private const val MAX_SOURCES = 4
    private const val CHROME = 42
    private const val NAME_WIDTH = 76
    private const val LABEL_WIDTH = 46
    private const val VALUE_WIDTH = 52

    private sealed interface Line {
        data class Meter(val metric: WidgetMetric, val percent: Double) : Line
        data class Amount(val label: String, val value: String) : Line
        data class Note(val text: String, val alert: Boolean = false) : Line
    }

    fun fits(width: Int, height: Int, font: Float) = width >= MIN_WIDTH && height >= MIN_HEIGHT && font <= 1.3f

    fun usage(context: Context, sources: List<WidgetSource>, width: Int, height: Int, open: PendingIntent? = null,
        refresh: PendingIntent? = null, notice: String? = null, openSource: (String) -> PendingIntent? = { open }): RemoteViews? {
        if (sources.size !in 2..MAX_SOURCES || !fits(width, height, context.resources.configuration.fontScale)) return null
        val strings = AppLocale.wrap(context)
        val colors = WidgetThemeColors.resolve(context)
        val dark = (colors.surface ushr 16 and 255) < 128
        val rows = sources.map { it to lines(strings, it) }
        val meterHeight = lineHeight(context, 13f) + 2
        val noteHeight = lineHeight(context, 10f) + 2
        val rowsHeight = rows.sumOf { (_, lines) -> lines.sumOf { if (it is Line.Note) noteHeight else meterHeight } } + rows.size + 1
        val footHeight = lineHeight(context, 9f) + 2
        val busiest = rows.flatMap { (source, lines) -> lines.filterIsInstance<Line.Meter>().map { source to it } }.maxByOrNull { it.second.percent }
        val title = lineHeight(context, 18f) + 2
        val heroOptions = if (busiest == null) listOf(0) else listOf(
            lineHeight(context, 10f) + title + lineHeight(context, 11f) + 5,
            title + lineHeight(context, 11f) + 5,
            title,
            0,
        )
        val budget = height - CHROME - rowsHeight - footHeight
        val heroLevel = heroOptions.indexOfFirst { it == 0 || it + 13 <= budget }
        if (heroOptions[heroLevel] == 0 && budget < 0) return null
        val showHero = heroOptions[heroLevel] > 0
        val views = chrome(context, strings, colors, strings.getString(R.string.widget_board_usage_title, strings.getString(R.string.app_name)),
            open, refresh, R.string.widget_refresh_description)
        val textWidth = width - 24
        if (showHero && busiest != null) {
            val (source, meter) = busiest
            val metric = meter.metric
            val color = WidgetAppearance.accent(meter.percent, colors.primary, colors.onSurfaceVariant, dark)
            val hero = RemoteViews(context.packageName, R.layout.widget_board_hero)
            val caption = heroLevel == 0
            val sub = heroLevel <= 1
            hero.setViewVisibility(R.id.widget_board_hero_caption, if (caption) View.VISIBLE else View.GONE)
            hero.setViewVisibility(R.id.widget_board_hero_sub, if (sub) View.VISIBLE else View.GONE)
            if (caption) {
                fit(context, hero, R.id.widget_board_hero_caption, strings.getString(R.string.widget_board_highest), textWidth, 10f)
                hero.setTextColor(R.id.widget_board_hero_caption, colors.onSurfaceVariant)
            }
            val headline = if (meter.percent >= 100) strings.getString(R.string.widget_board_used_up, source.title, metric.label)
                else strings.getString(R.string.widget_board_used, source.title, metric.label, metric.value)
            fit(context, hero, R.id.widget_board_hero_title, headline, textWidth, 18f)
            hero.setTextColor(R.id.widget_board_hero_title, color)
            if (sub) {
                val other = source.metrics.firstOrNull { it !== metric && it.percent != null }
                val detail = listOfNotNull(metric.reset, other?.let { strings.getString(R.string.widget_board_other_window, it.label, it.value) })
                    .joinToString(" · ")
                fit(context, hero, R.id.widget_board_hero_sub, detail, textWidth, 11f, truncate = true)
                hero.setTextColor(R.id.widget_board_hero_sub, colors.onSurfaceVariant)
            }
            hero.setOnClickPendingIntent(R.id.widget_board_hero, openSource(source.id))
            views.addView(R.id.widget_source_rows, hero)
            views.addView(R.id.widget_source_rows, divider(context, colors))
        }
        val lineWidth = textWidth - NAME_WIDTH
        rows.forEach { (source, lines) ->
            val row = RemoteViews(context.packageName, R.layout.widget_board_row)
            fit(context, row, R.id.widget_board_row_name, source.title, NAME_WIDTH - 4, 12f)
            row.setTextColor(R.id.widget_board_row_name, colors.onSurface)
            lines.forEach { line -> row.addView(R.id.widget_board_row_lines, line(context, line, lineWidth, colors, dark)) }
            row.setContentDescription(R.id.widget_board_row, source.title + " · " + lines.joinToString(" · ") { describe(it) })
            row.setOnClickPendingIntent(R.id.widget_board_row, openSource(source.id))
            views.addView(R.id.widget_source_rows, RemoteViews(context.packageName, R.layout.widget_source_gap))
            views.addView(R.id.widget_source_rows, row)
        }
        views.addView(R.id.widget_source_rows, RemoteViews(context.packageName, R.layout.widget_source_gap))
        views.addView(R.id.widget_source_rows, footer(context, strings, colors, sources.map { it.fetchedAt to it.cached }, textWidth, notice))
        return views
    }

    fun status(context: Context, sources: List<WidgetSource>, width: Int, height: Int,
        style: StatusWidgetStyle = DashboardPreferences.get(context).readStatusWidgetStyle(), open: PendingIntent? = null,
        refresh: PendingIntent? = null, notice: String? = null, openSource: (String) -> PendingIntent? = { open }): RemoteViews? {
        if (sources.size !in 1..MAX_SOURCES || !fits(width, height, context.resources.configuration.fontScale)) return null
        val strings = AppLocale.wrap(context)
        val colors = WidgetThemeColors.resolve(context)
        val zone = DisplayTimeZones.resolve(DashboardPreferences.get(context).readTimeZoneId())
        val services = sources.map { service(strings.resources, it, zone) }
        val views = chrome(context, strings, colors, strings.getString(R.string.widget_board_status_title, strings.getString(R.string.app_name)),
            open, refresh, R.string.widget_status_refresh_description)
        val textWidth = width - 24
        when (style) {
            StatusWidgetStyle.Summary -> summary(context, strings, colors, views, services, textWidth, openSource)
            StatusWidgetStyle.Signals -> signals(context, strings, colors, views, services, textWidth, openSource)
            StatusWidgetStyle.Alerts -> alerts(context, strings, colors, views, services, textWidth, height, openSource)
        }
        views.addView(R.id.widget_source_rows, footer(context, strings, colors,
            sources.map { it.status?.fetchedAt to (it.status?.fromCache != false) }, textWidth, notice))
        return views
    }

    private fun chrome(context: Context, strings: Context, colors: WidgetThemeColors, title: String, open: PendingIntent?,
        refresh: PendingIntent?, refreshLabel: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_sources)
        views.setInt(R.id.widget_surface, "setColorFilter", WidgetAppearance.blend(colors.surface, colors.primary, 0.025f))
        views.setInt(R.id.widget_outline, "setColorFilter", colors.outline)
        views.setOnClickPendingIntent(R.id.widget_root, open)
        views.setTextViewText(R.id.widget_multi_title, title)
        views.setTextColor(R.id.widget_multi_title, colors.onSurfaceVariant)
        views.setViewVisibility(R.id.widget_next_page, View.GONE)
        views.setViewVisibility(R.id.widget_multi_empty, View.GONE)
        views.setViewVisibility(R.id.widget_refresh, if (refresh != null) View.VISIBLE else View.GONE)
        views.setImageViewBitmap(R.id.widget_refresh, WidgetVisuals.refreshIconBitmap(context, colors.onSurfaceVariant))
        views.setOnClickPendingIntent(R.id.widget_refresh, refresh)
        views.setContentDescription(R.id.widget_refresh, strings.getString(refreshLabel))
        views.setViewVisibility(R.id.widget_source_rows, View.VISIBLE)
        views.removeAllViews(R.id.widget_source_rows)
        return views
    }

    private fun lines(strings: Context, source: WidgetSource): List<Line> {
        val metrics = source.metrics
        val body = when {
            source.split != null && metrics.size == 3 -> listOf(Line.Amount(metrics[0].label, metrics[0].value),
                Line.Note(metrics.drop(1).joinToString(" · ") { "${it.label} ${it.value}" }))
            else -> metrics.map { metric -> metric.percent?.let { Line.Meter(metric, it) } ?: Line.Amount(metric.label, metric.value) }
        }
        val attention = source.attention?.let { Line.Note(it, alert = true) }
        return when {
            body.isNotEmpty() -> body + listOfNotNull(attention)
            attention != null -> listOf(attention)
            else -> listOf(Line.Note(source.note.ifBlank { strings.getString(R.string.label_data_unavailable) }))
        }
    }

    private fun describe(line: Line): String = when (line) {
        is Line.Meter -> "${line.metric.label} ${line.metric.value}" + (line.metric.reset?.let { " · $it" } ?: "")
        is Line.Amount -> listOf(line.label, line.value).filter(String::isNotEmpty).joinToString(" ")
        is Line.Note -> line.text
    }

    private fun line(context: Context, line: Line, width: Int, colors: WidgetThemeColors, dark: Boolean): RemoteViews {
        if (line is Line.Note) {
            val note = RemoteViews(context.packageName, R.layout.widget_board_text)
            fit(context, note, R.id.widget_board_text, line.text, width, 10f, truncate = true)
            note.setTextColor(R.id.widget_board_text, if (line.alert) WidgetAppearance.accent(100.0, colors.primary, colors.onSurfaceVariant, dark)
                else colors.onSurfaceVariant)
            note.setInt(R.id.widget_board_text, "setGravity", if (line.alert) Gravity.START else Gravity.END)
            note.setInt(R.id.widget_board_text, "setMaxLines", 1)
            return note
        }
        val row = RemoteViews(context.packageName, R.layout.widget_board_line)
        row.setTextColor(R.id.widget_board_line_label, colors.onSurfaceVariant)
        when (line) {
            is Line.Meter -> {
                val color = WidgetAppearance.accent(line.percent, colors.primary, colors.onSurfaceVariant, dark)
                fit(context, row, R.id.widget_board_line_label, line.metric.label, LABEL_WIDTH - 4, 10f)
                row.setImageViewBitmap(R.id.widget_board_line_bar, WidgetVisuals.quotaBarBitmap(line.percent, color,
                    WidgetAppearance.blend(colors.surface, colors.primary, 0.10f)))
                fit(context, row, R.id.widget_board_line_value, line.metric.value, VALUE_WIDTH, 13f)
                row.setTextColor(R.id.widget_board_line_value, color)
            }
            is Line.Amount -> {
                fit(context, row, R.id.widget_board_line_label, line.label, LABEL_WIDTH - 4, 10f)
                row.setViewVisibility(R.id.widget_board_line_bar, View.INVISIBLE)
                val valueWidth = maxOf(VALUE_WIDTH, minOf(textWidthDp(context, line.value, 13f) + 2, width - LABEL_WIDTH))
                fit(context, row, R.id.widget_board_line_value, line.value, valueWidth, 13f)
                row.setTextColor(R.id.widget_board_line_value, colors.onSurface)
            }
            is Line.Note -> Unit
        }
        return row
    }

    private fun divider(context: Context, colors: WidgetThemeColors) = RemoteViews(context.packageName, R.layout.widget_board_divider).apply {
        setInt(R.id.widget_board_divider, "setBackgroundColor", WidgetAppearance.blend(colors.surface, colors.onSurface, 0.12f))
    }

    private fun footer(context: Context, strings: Context, colors: WidgetThemeColors, stamps: List<Pair<String?, Boolean>>, width: Int, notice: String?): RemoteViews {
        val zone = DisplayTimeZones.resolve(DashboardPreferences.get(context).readTimeZoneId())
        val latest = stamps.mapNotNull { it.first }.maxOrNull()
        val clock = AssistantPresentation.updatedClock(latest, zone) ?: "—"
        val text = listOfNotNull(notice?.takeIf(String::isNotBlank),
            strings.getString(if (stamps.any { it.second }) R.string.widget_status_cached_clock else R.string.widget_updated_time, clock)).joinToString(" · ")
        return RemoteViews(context.packageName, R.layout.widget_board_text).apply {
            fit(context, this, R.id.widget_board_text, text, width, 9f, truncate = true)
            setTextColor(R.id.widget_board_text, colors.onSurfaceVariant)
            setInt(R.id.widget_board_text, "setMaxLines", 1)
        }
    }

    /* ---------------- Service status ---------------- */

    private data class Service(
        val source: WidgetSource,
        val indicator: StatusIndicator,
        val color: Int,
        val word: String,
        val total: Int,
        val up: Int,
        val incident: StatusIncident?,
        val broken: List<String>,
        val since: String?,
        val lasting: String?,
        val detail: String,
    ) {
        val ok get() = indicator == StatusIndicator.None
    }

    private val severity = listOf(StatusIndicator.None, StatusIndicator.Unknown, StatusIndicator.Maintenance,
        StatusIndicator.Minor, StatusIndicator.Major, StatusIndicator.Critical)

    private fun service(resources: Resources, source: WidgetSource, zone: ZoneId): Service {
        val indicator = WidgetSourcePolicy.indicator(listOf(source)) ?: StatusIndicator.Unknown
        val status = source.status
        val components = status?.components.orEmpty()
        val incident = if (indicator == StatusIndicator.None) null
            else status?.activeIncidents?.firstOrNull() ?: status?.scheduledMaintenances?.firstOrNull()
        val started = incident?.let { DisplayTime.parseInstant(it.createdAt ?: it.scheduledFor) }
        val broken = components.filter { it.status != ComponentStatus.Operational }
            .map { WidgetStatusPresentation.componentName(source.title, it.name) }
        val detail = incident?.name ?: broken.take(2).joinToString(" · ").ifEmpty {
            source.statusLine.ifBlank { resources.getString(R.string.widget_status_waiting) }
        }
        return Service(source, indicator, WidgetCalculations.indicatorColor(indicator),
            if (indicator == StatusIndicator.None) resources.getString(R.string.status_component_operational)
                else StatusPresentation.compactIndicatorLabel(indicator, resources), components.size,
            components.count { it.status == ComponentStatus.Operational }, incident, broken,
            started?.let { WidgetSourcePolicy.shortReset(it.epochSecond, zone) },
            started?.let { UsageCalculations.formatRemaining((System.currentTimeMillis() - it.toEpochMilli()).coerceAtLeast(60_000), true, resources) },
            detail)
    }

    private fun worst(services: List<Service>) = services.maxByOrNull { severity.indexOf(it.indicator) }!!

    private fun headline(strings: Context, services: List<Service>): String {
        val issues = services.filterNot { it.ok }
        return when (issues.size) {
            0 -> strings.getString(R.string.widget_board_all_ok)
            1 -> "${issues[0].source.title} ${issues[0].word}"
            else -> strings.resources.getQuantityString(R.plurals.widget_board_many_issues, issues.size, issues.size)
        }
    }

    private fun issueLine(strings: Context, service: Service) = listOfNotNull(service.detail,
        service.since?.let { strings.getString(R.string.widget_board_since, it) }).joinToString(" · ")

    private fun badge(context: Context, service: Service, size: Int) =
        WidgetVisuals.statusBadgeBitmap(context, size, service.color, WidgetCalculations.statusBadgeGlyph(service.indicator))

    private fun summary(context: Context, strings: Context, colors: WidgetThemeColors, views: RemoteViews, services: List<Service>,
        width: Int, openSource: (String) -> PendingIntent?) {
        val worst = worst(services)
        val hero = RemoteViews(context.packageName, R.layout.widget_board_status_hero)
        hero.setImageViewBitmap(R.id.widget_board_status_badge, badge(context, worst, 30))
        fit(context, hero, R.id.widget_board_status_title, headline(strings, services), width - 44, 17f)
        hero.setTextColor(R.id.widget_board_status_title, if (worst.ok) colors.onSurface else worst.color)
        val sub = if (worst.ok) listOf(strings.resources.getQuantityString(R.plurals.widget_board_source_count, services.size, services.size),
            services.sumOf { it.total }.let { strings.resources.getQuantityString(R.plurals.widget_board_tile_ok, it, it) }).joinToString(" · ")
            else issueLine(strings, worst)
        fit(context, hero, R.id.widget_board_status_sub, sub, width - 44, 11f, truncate = true)
        hero.setTextColor(R.id.widget_board_status_sub, colors.onSurfaceVariant)
        hero.setOnClickPendingIntent(R.id.widget_board_status_hero, openSource(worst.source.id))
        views.addView(R.id.widget_source_rows, hero)
        views.addView(R.id.widget_source_rows, divider(context, colors))
        services.forEach { service ->
            val row = RemoteViews(context.packageName, R.layout.widget_board_status_row)
            row.setImageViewBitmap(R.id.widget_board_status_dot, WidgetVisuals.dotBitmap(context, 8, service.color))
            fit(context, row, R.id.widget_board_status_name, service.source.title, width / 2, 13f)
            row.setTextColor(R.id.widget_board_status_name, colors.onSurface)
            if (service.total > 0) {
                fit(context, row, R.id.widget_board_status_count, strings.getString(R.string.widget_board_components, service.up, service.total), 64, 10f)
                row.setTextColor(R.id.widget_board_status_count, colors.onSurfaceVariant)
            } else row.setViewVisibility(R.id.widget_board_status_count, View.GONE)
            fit(context, row, R.id.widget_board_status_word, service.word, 72, 12f)
            row.setTextColor(R.id.widget_board_status_word, if (service.ok) colors.onSurfaceVariant else service.color)
            row.setContentDescription(R.id.widget_board_status_row, "${service.source.title} · ${service.word}" +
                if (service.ok) "" else " · ${issueLine(strings, service)}")
            row.setOnClickPendingIntent(R.id.widget_board_status_row, openSource(service.source.id))
            views.addView(R.id.widget_source_rows, RemoteViews(context.packageName, R.layout.widget_source_gap))
            views.addView(R.id.widget_source_rows, row)
        }
        views.addView(R.id.widget_source_rows, RemoteViews(context.packageName, R.layout.widget_source_gap))
    }

    private fun tileTint(colors: WidgetThemeColors, service: Service) = if (service.ok)
        WidgetAppearance.blend(colors.surface, colors.onSurface, 0.05f) else WidgetAppearance.blend(colors.surface, service.color, 0.16f)

    private fun signals(context: Context, strings: Context, colors: WidgetThemeColors, views: RemoteViews, services: List<Service>,
        width: Int, openSource: (String) -> PendingIntent?) {
        val ordered = services.sortedByDescending { severity.indexOf(it.indicator) }
        val grid = when (ordered.size) {
            3 -> listOf(ordered.take(1), ordered.drop(1))
            4 -> ordered.chunked(2)
            else -> listOf(ordered)
        }
        grid.forEach { cells ->
            val row = RemoteViews(context.packageName, R.layout.widget_board_tile_row)
            val tileWidth = width / cells.size - 26
            cells.forEach { service ->
                val tile = RemoteViews(context.packageName, R.layout.widget_board_tile)
                tile.setInt(R.id.widget_board_tile_bg, "setColorFilter", tileTint(colors, service))
                tile.setImageViewBitmap(R.id.widget_board_tile_badge, badge(context, service, 20))
                fit(context, tile, R.id.widget_board_tile_name, service.source.title, tileWidth - 26, 13f)
                tile.setTextColor(R.id.widget_board_tile_name, colors.onSurface)
                fit(context, tile, R.id.widget_board_tile_word, service.word, tileWidth, 15f)
                tile.setTextColor(R.id.widget_board_tile_word, if (service.ok) colors.onSurface else service.color)
                val detail = if (!service.ok) issueLine(strings, service)
                    else if (service.total > 0) strings.resources.getQuantityString(R.plurals.widget_board_tile_ok, service.total, service.total)
                    else service.source.statusLine
                tile.setTextViewText(R.id.widget_board_tile_detail, detail)
                tile.setTextViewTextSize(R.id.widget_board_tile_detail, TypedValue.COMPLEX_UNIT_SP, 10f)
                tile.setTextColor(R.id.widget_board_tile_detail, colors.onSurfaceVariant)
                tile.setContentDescription(R.id.widget_board_tile, "${service.source.title} · ${service.word} · $detail")
                tile.setOnClickPendingIntent(R.id.widget_board_tile, openSource(service.source.id))
                row.addView(R.id.widget_board_tile_row, tile)
            }
            views.addView(R.id.widget_source_rows, row)
        }
    }

    private fun alerts(context: Context, strings: Context, colors: WidgetThemeColors, views: RemoteViews, services: List<Service>,
        width: Int, height: Int, openSource: (String) -> PendingIntent?) {
        val healthy = WidgetCalculations.indicatorColor(StatusIndicator.None)
        val issues = services.filterNot { it.ok }.sortedByDescending { severity.indexOf(it.indicator) }
        val good = services.filter { it.ok }
        if (issues.isEmpty()) {
            val center = RemoteViews(context.packageName, R.layout.widget_board_center)
            center.setImageViewBitmap(R.id.widget_board_center_badge, badge(context, services.first(), 44))
            fit(context, center, R.id.widget_board_center_title, strings.getString(R.string.widget_board_all_ok), width, 18f)
            center.setTextColor(R.id.widget_board_center_title, colors.onSurface)
            center.setTextViewText(R.id.widget_board_center_names, dotted(good.map { it.source.title }, healthy))
            center.setTextColor(R.id.widget_board_center_names, colors.onSurfaceVariant)
            center.setContentDescription(R.id.widget_board_center, strings.getString(R.string.widget_board_all_ok) + " · " +
                good.joinToString(" · ") { it.source.title })
            views.addView(R.id.widget_source_rows, center)
            return
        }
        val cardHeight = 26 + lineHeight(context, 14f) + lineHeight(context, 12f) * 2 + lineHeight(context, 10f) + 9
        val restHeight = lineHeight(context, 11f) + 8
        val room = ((height - CHROME - lineHeight(context, 9f) - restHeight) / cardHeight).coerceAtLeast(1)
        val shown = issues.take(room)
        shown.forEach { service ->
            val card = RemoteViews(context.packageName, R.layout.widget_board_issue)
            card.setInt(R.id.widget_board_issue_bg, "setColorFilter", tileTint(colors, service))
            card.setImageViewBitmap(R.id.widget_board_issue_badge, badge(context, service, 18))
            fit(context, card, R.id.widget_board_issue_name, service.source.title, width / 3, 14f)
            card.setTextColor(R.id.widget_board_issue_name, colors.onSurface)
            fit(context, card, R.id.widget_board_issue_word, service.word, width / 4, 12f)
            card.setTextColor(R.id.widget_board_issue_word, service.color)
            val lasting = service.lasting?.let { strings.getString(R.string.widget_board_lasting, it) }.orEmpty()
            fit(context, card, R.id.widget_board_issue_lasting, lasting, width / 3, 10f, truncate = true)
            card.setTextColor(R.id.widget_board_issue_lasting, colors.onSurfaceVariant)
            card.setTextViewText(R.id.widget_board_issue_title, issueLine(strings, service))
            card.setTextColor(R.id.widget_board_issue_title, colors.onSurface)
            val parts = service.broken.take(3).joinToString(" · ").takeIf { service.incident != null }.orEmpty()
            if (parts.isEmpty()) card.setViewVisibility(R.id.widget_board_issue_parts, View.GONE)
            else {
                fit(context, card, R.id.widget_board_issue_parts, parts, width - 20, 10f, truncate = true)
                card.setTextColor(R.id.widget_board_issue_parts, colors.onSurfaceVariant)
            }
            card.setContentDescription(R.id.widget_board_issue, listOf(service.source.title, service.word, issueLine(strings, service), lasting, parts)
                .filter(String::isNotBlank).joinToString(" · "))
            card.setOnClickPendingIntent(R.id.widget_board_issue, openSource(service.source.id))
            views.addView(R.id.widget_source_rows, card)
        }
        val hidden = issues.size - shown.size
        val rest = SpannableStringBuilder()
        if (hidden > 0) rest.append(strings.resources.getQuantityString(R.plurals.widget_board_more_issues, hidden, hidden))
        if (good.isNotEmpty()) {
            if (rest.isNotEmpty()) rest.append(" · ")
            rest.append(dotted(listOf(strings.getString(R.string.widget_board_rest_ok)), healthy))
            rest.append(" · ").append(good.joinToString(" · ") { it.source.title })
        }
        if (rest.isNotEmpty()) {
            val line = RemoteViews(context.packageName, R.layout.widget_board_text)
            line.setTextViewText(R.id.widget_board_text, rest)
            line.setTextViewTextSize(R.id.widget_board_text, TypedValue.COMPLEX_UNIT_SP,
                minOf(11f, 11f * (width - 6) / textWidthDp(context, rest.toString(), 11f).coerceAtLeast(1)))
            line.setTextColor(R.id.widget_board_text, colors.onSurfaceVariant)
            line.setInt(R.id.widget_board_text, "setMaxLines", 1)
            line.setContentDescription(R.id.widget_board_text, rest.toString())
            val inset = (8 * context.resources.displayMetrics.density).toInt()
            line.setViewPadding(R.id.widget_board_text, inset / 2, inset, 0, 0)
            views.addView(R.id.widget_source_rows, line)
        }
        views.addView(R.id.widget_source_rows, RemoteViews(context.packageName, R.layout.widget_source_gap))
    }

    private fun dotted(names: List<String>, color: Int): CharSequence = SpannableStringBuilder().apply {
        names.forEachIndexed { index, name ->
            if (index > 0) append("   ")
            val start = length
            append("● ")
            setSpan(ForegroundColorSpan(color), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(name)
        }
    }
}
