package com.lamuier.agenT.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lamuier.agenT.R
import com.lamuier.agenT.codex.*
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle

@Immutable
internal data class ResetDailyCount(val date: LocalDate, val regular: Int, val banked: Int)
@Immutable
internal data class ResetDashboardData(val regular: Int, val banked: Int, val latest: Long?, val days: List<ResetDailyCount>) {
    val total get() = regular + banked
    val recordedDays get() = days.count { it.regular + it.banked > 0 }
}

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
    val days = data.days
    val locale = LocalConfiguration.current.locales[0]
    val leading = days.first().date.dayOfWeek.value % 7
    val slots = buildList<ResetDailyCount?> {
        repeat(leading) { add(null) }
        addAll(days)
    }
    Column(Modifier.fillMaxWidth().testTag("reset-frequency-chart"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            repeat(7) { index ->
                Text(DayOfWeek.SUNDAY.plus(index.toLong()).getDisplayName(TextStyle.NARROW_STANDALONE, locale),
                    Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        slots.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { day ->
                    if (day == null) Spacer(Modifier.weight(1f))
                    else ResetFrequencyDay(day, selected == day.date, colors, Modifier.weight(1f)) { onSelect(day.date) }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    days.firstOrNull { it.date == selected }?.let { Text(stringResource(R.string.reset_dash_day_a11y, it.date.toString(), it.regular, it.banked),
        style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun ResetFrequencyDay(day: ResetDailyCount, selected: Boolean, colors: ResetChartColors, modifier: Modifier, onSelect: () -> Unit) {
    val description = stringResource(R.string.reset_dash_day_a11y, day.date.toString(), day.regular, day.banked)
    val shape = RoundedCornerShape(8.dp)
    val outline = if (selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, shape) else Modifier
    Column(modifier.testTag("reset-day-" + day.date).defaultMinSize(minHeight = 48.dp).then(outline).clip(shape)
        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
        .clickable(onClick = onSelect).semantics { contentDescription = description; this.selected = selected }
        .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(day.date.dayOfMonth.toString(), Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        FrequencyCount(day.regular, colors.regular)
        FrequencyCount(day.banked, colors.banked)
    }
}

@Composable
private fun FrequencyCount(count: Int, color: Color) {
    Text(count.toString(), Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelSmall,
        color = color.copy(alpha = if (count == 0) 0.38f else 1f), maxLines = 1, textAlign = TextAlign.Center,
        overflow = TextOverflow.Ellipsis)
}
