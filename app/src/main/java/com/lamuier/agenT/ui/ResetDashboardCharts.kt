package com.lamuier.agenT.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lamuier.agenT.R
import com.lamuier.agenT.codex.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Immutable
internal data class ResetDailyCount(val date: LocalDate, val regular: Int, val banked: Int)
@Immutable
internal data class ResetDashboardData(val regular: Int, val banked: Int, val latest: Long?, val days: List<ResetDailyCount>) {
    val total get() = regular + banked
    val recordedDays get() = days.count { it.regular + it.banked > 0 }
}

private val CHART_AXIS_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd")

/** Counts supplied public records, never credits, account redemptions or inferred quota-window resets. */
internal fun resetDashboardData(events: List<CodexPublicReset>, range: CodexResetRange, now: Instant, zone: ZoneId): ResetDashboardData {
    val entries = CodexResetTimeline.entries(events, range)
    val from = Instant.ofEpochSecond(range.start).atZone(zone).toLocalDate()
    val end = Instant.ofEpochSecond(range.end - 1).atZone(zone).toLocalDate()
    val latest = entries.maxOfOrNull { it.at }
    val lastSupplied = latest?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() } ?: from
    val to = minOf(end, maxOf(from, now.atZone(zone).toLocalDate(), lastSupplied))
    val days = CodexResetHeatmap.build(entries, from, to, zone).days.map { day ->
        ResetDailyCount(day.date, day.entries.count { it.public.type == "regular" }, day.entries.count { it.public.type == "banked" })
    }
    return ResetDashboardData(entries.count { it.public.type == "regular" }, entries.count { it.public.type == "banked" }, latest, days)
}

@Immutable
internal data class ResetChartColors(val regular: Color, val banked: Color, val empty: Color)
@Composable internal fun resetChartColors() = ResetChartColors(MaterialTheme.colorScheme.primary,
    MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.surfaceContainerHighest)

@Composable
internal fun ResetTypeLegend() {
    val colors = resetChartColors()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ResetTypeLabel(stringResource(R.string.reset_dash_regular), colors.regular)
        ResetTypeLabel(stringResource(R.string.reset_dash_banked), colors.banked)
    }
}

@Composable internal fun ResetTypeLabel(text: String, color: Color, modifier: Modifier = Modifier, fillLabel: Boolean = false) {
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(8.dp)) { drawCircle(color) }
        Text(text, if (fillLabel) Modifier.weight(1f) else Modifier, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ResetComposition(data: ResetDashboardData?) {
    val colors = resetChartColors()
    val total = data?.total?.toString() ?: "—"
    val description = stringResource(R.string.reset_dash_composition_a11y, data?.regular?.toString() ?: "—", data?.banked?.toString() ?: "—")
    val largeText = LocalDensity.current.fontScale > 1.3f
    @Composable fun Dial() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(if (largeText) 128.dp else 104.dp).semantics(mergeDescendants = true) { contentDescription = description }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val inset = 6.dp.toPx(); val diameter = size.minDimension - inset * 2
                drawCircle(colors.empty, diameter / 2, style = Stroke(9.dp.toPx()))
                if (data != null && data.total > 0) {
                    val angle = 360f * data.regular / data.total
                    val gap = if (data.regular > 0 && data.banked > 0) minOf(2f, minOf(angle, 360f - angle) / 3) else 0f
                    if (data.regular > 0) drawArc(colors.regular, -90f, angle - gap,
                        false, Offset(inset, inset), Size(diameter, diameter), style = Stroke(9.dp.toPx(), cap = StrokeCap.Butt))
                    if (data.banked > 0) drawArc(colors.banked, -90f + angle, 360f - angle - gap,
                        false, Offset(inset, inset), Size(diameter, diameter), style = Stroke(9.dp.toPx(), cap = StrokeCap.Butt))
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(total, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                if (!largeText) Text(stringResource(R.string.reset_dash_records), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (largeText) Text(stringResource(R.string.reset_dash_records), style = MaterialTheme.typography.labelSmall)
        }
    }
    @Composable fun Counts(modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(Triple(R.string.reset_dash_regular, colors.regular, data?.regular), Triple(R.string.reset_dash_banked, colors.banked, data?.banked)).forEachIndexed { index, row ->
                Column(Modifier.testTag(if (index == 0) "reset-count-regular" else "reset-count-banked")) {
                    ResetTypeLabel(stringResource(row.first), row.second, Modifier.fillMaxWidth(), fillLabel = true)
                    Text(row.third?.toString() ?: "—", Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 248.dp && LocalDensity.current.fontScale <= 1.3f) Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)) { Dial(); Counts(Modifier.weight(1f)) }
        else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Dial(); Counts(Modifier.fillMaxWidth()) }
    }
}

@Composable
internal fun ResetFrequencyChart(data: ResetDashboardData?, selected: LocalDate?, onSelect: (LocalDate) -> Unit) {
    if (data == null || data.total == 0) {
        Text(stringResource(if (data == null) R.string.label_data_unavailable else R.string.reset_period_empty), style = MaterialTheme.typography.bodySmall)
        return
    }
    val colors = resetChartColors()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val selection = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    val days = data.days
    val maximum = days.maxOf { maxOf(it.regular, it.banked) }.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.height(120.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(maximum.toString(), style = MaterialTheme.typography.labelSmall)
            Text("0", style = MaterialTheme.typography.labelSmall)
        }
        Column(Modifier.weight(1f)) {
            BoxWithConstraints(Modifier.fillMaxWidth().height(120.dp).testTag("reset-frequency-chart")) {
                val step = maxWidth / days.size
                Canvas(Modifier.fillMaxSize().pointerInput(days) { detectTapGestures { tap ->
                    val index = (tap.x / (size.width.toFloat() / days.size)).toInt().coerceIn(0, days.lastIndex)
                    onSelect(days[index].date)
                } }) {
                    val slot = size.width / days.size
                    val floor = size.height - 3.dp.toPx(); val height = floor - 3.dp.toPx()
                    drawLine(grid, Offset(0f, floor), Offset(size.width, floor), 1.dp.toPx())
                    drawLine(grid, Offset(0f, floor - height), Offset(size.width, floor - height), 1.dp.toPx())
                    days.forEachIndexed { i, day ->
                        if (selected == day.date) drawRect(selection, Offset(slot * i, 0f), Size(slot, size.height))
                        listOf(day.regular to colors.regular, day.banked to colors.banked).forEachIndexed { type, item ->
                            val barHeight = height * item.first / maximum
                            if (barHeight > 0) drawRoundRect(item.second, Offset(slot * i + slot * (0.12f + type * 0.4f), floor - barHeight),
                                Size(slot * 0.32f, barHeight), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                        }
                    }
                }
                days.forEachIndexed { i, day ->
                    val description = stringResource(R.string.reset_dash_day_a11y, day.date.toString(), day.regular, day.banked)
                    Box(Modifier.offset(x = step * i).width(step).fillMaxHeight().testTag("reset-day-" + day.date).semantics {
                        contentDescription = description; this.selected = selected == day.date
                        onClick { onSelect(day.date); true }
                    })
                }
            }
            Row(Modifier.fillMaxWidth()) {
                val formatter = CHART_AXIS_DATE_FORMATTER
                Text(days.first().date.format(formatter), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                if (days.size > 2) Text(days[days.size / 2].date.format(formatter), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                if (days.size > 1) Text(days.last().date.format(formatter), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
            }
        }
    }
    days.firstOrNull { it.date == selected }?.let { Text(stringResource(R.string.reset_dash_day_a11y, it.date.toString(), it.regular, it.banked),
        style = MaterialTheme.typography.labelMedium) }
}
