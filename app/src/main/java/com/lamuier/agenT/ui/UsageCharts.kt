package com.lamuier.agenT.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import com.lamuier.agenT.usage.UsageAnalysis
import com.lamuier.agenT.usage.UsageObservation
import com.lamuier.agenT.util.AssistantPresentation
import com.lamuier.agenT.util.UsageCalculations
import com.lamuier.agenT.model.ModelTokenUsage
import java.math.BigInteger
import java.util.Locale

internal fun usageCombinedTokens(model: ModelTokenUsage): BigInteger? =
    if ("input" in model.availableMetrics && "output" in model.availableMetrics && model.inputTokens >= 0 && model.outputTokens >= 0)
        BigInteger.valueOf(model.inputTokens).add(BigInteger.valueOf(model.outputTokens)) else null

internal fun usageMagnitude(value: Double): String = when {
    !value.isFinite() || value < 0 -> "—"
    value >= 1_000_000_000_000 -> String.format(Locale.US, "%.2e", value)
    value >= 1_000_000_000 -> String.format(Locale.US, "%.2fB", value / 1_000_000_000)
    value >= 1_000_000 -> String.format(Locale.US, "%.2fM", value / 1_000_000)
    value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000)
    else -> String.format(Locale.US, "%.0f", value)
}

internal fun usageChartRatio(value: Double?, maximum: Double): Float? =
    value?.takeIf { it.isFinite() && it >= 0 }?.let {
        if (!maximum.isFinite() || maximum < 0) null
        else if (maximum == 0.0) 0f else (it / maximum).coerceIn(0.0, 1.0).toFloat()
    }

internal fun usageTrendSegments(points: List<UsageObservation>, metric: String): List<List<UsageObservation>> {
    val result = mutableListOf<MutableList<UsageObservation>>()
    var previous: UsageObservation? = null
    points.sortedBy { it.atMillis }.forEach { point ->
        val value = point.values[metric]
        if (value == null || !value.isFinite() || value < 0) { previous = null; return@forEach }
        if (previous == null || !UsageAnalysis.connected(previous!!, point, metric)) result.add(mutableListOf(point))
        else result.last() += point
        previous = point
    }
    return result
}

/** Keep extrema and segment endpoints in each horizontal pixel bucket. Never interpolate missing samples. */
internal fun usageTrendVertices(segment: List<UsageObservation>, metric: String, first: Long, last: Long, columns: Int): List<UsageObservation> {
    if (segment.size <= columns.coerceAtLeast(1) * 4) return segment
    val span = (last.toDouble() - first.toDouble()).coerceAtLeast(1.0)
    return segment.groupBy { (((it.atMillis.toDouble() - first) / span) * columns.coerceAtLeast(1)).toInt() }
        .values.flatMap { rows -> listOf(rows.first(), rows.minBy { it.values.getValue(metric) },
            rows.maxBy { it.values.getValue(metric) }, rows.last()).distinctBy { it.atMillis }.sortedBy { it.atMillis } }
}

/** Input is the already ordered, valid chart samples; retain exact sample selection. */
internal fun nearestUsageSample(points: List<UsageObservation>, target: Double): UsageObservation {
    require(points.isNotEmpty())
    if (target <= points.first().atMillis) return points.first()
    if (target >= points.last().atMillis) return points.last()
    var low = 0; var high = points.lastIndex
    while (low < high) {
        val middle = (low + high) ushr 1
        if (points[middle].atMillis < target) low = middle + 1 else high = middle
    }
    if (low == 0) return points.first()
    val left = points[low - 1]; val right = points[low]
    return if (kotlin.math.abs(left.atMillis - target) <= kotlin.math.abs(right.atMillis - target)) left else right
}

@Composable
internal fun UsageQuotaDial(title: String, used: Double?, modifier: Modifier = Modifier, tag: String = "") {
    val valid = used?.takeIf { it.isFinite() && it >= 0 }
    val remaining = valid?.let { (100 - it).coerceAtLeast(0.0) }
    val description = stringResource(R.string.u_summary_quota, title, AssistantPresentation.percent(remaining), AssistantPresentation.percent(valid))
    val color = when { valid == null -> MaterialTheme.colorScheme.outline; valid >= 90 -> MaterialTheme.colorScheme.error
        valid >= 80 -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.primary }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.size(36.dp)) {
            val inset = 3.dp.toPx(); val diameter = size.minDimension - inset * 2
            drawCircle(track, diameter / 2, style = Stroke(4.dp.toPx()))
            if (remaining != null && remaining > 0) drawArc(color, -90f, (remaining.coerceAtMost(100.0) * 3.6).toFloat(),
                false, Offset(inset, inset), Size(diameter, diameter), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.u_short_remaining, AssistantPresentation.percent(remaining)),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = if (valid != null && valid >= 80) color else MaterialTheme.colorScheme.onSurface)
            if (valid != null && valid > 100) Text(stringResource(R.string.u_used, AssistantPresentation.percent(valid)),
                style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

internal data class UsageKpi(val label: String, val value: String, val tag: String = "", val exact: String = value)

@Composable
internal fun UsageKpiGrid(items: List<UsageKpi>) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 248.dp && fontScale <= 1.3f) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item -> Surface(Modifier.weight(1f).testTag(item.tag),
                        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.padding(10.dp).semantics(mergeDescendants = true) { contentDescription = item.label + ": " + item.exact },
                            verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(item.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(item.value, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        }
                    } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
internal fun UsageBar(value: Double?, maximum: Double, modifier: Modifier = Modifier, comparison: Boolean = false) {
    val ratio = usageChartRatio(value, maximum)
    val color = if (comparison) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.fillMaxWidth().height(8.dp).semantics {
        if (ratio != null) progressBarRangeInfo = ProgressBarRangeInfo(ratio, 0f..1f)
    }) {
        drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        if (ratio != null && ratio > 0) drawRoundRect(color, size = Size(size.width * ratio, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
    }
}

private fun DrawScope.drawCycleMarker(center: Offset, radius: Float, color: Color, background: Color) {
    val diamond = Path().apply {
        moveTo(center.x, center.y - radius)
        lineTo(center.x + radius, center.y)
        lineTo(center.x, center.y + radius)
        lineTo(center.x - radius, center.y)
        close()
    }
    drawPath(diamond, background)
    drawPath(diamond, color, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
}

@Composable
internal fun UsagePaceLegend(elapsed: Double?) {
    val primary = MaterialTheme.colorScheme.primary
    val marker = MaterialTheme.colorScheme.tertiary
    val background = MaterialTheme.colorScheme.surfaceContainerLow
    @Composable fun Entry(cycle: Boolean, text: String, modifier: Modifier) {
        Row(modifier.semantics(mergeDescendants = true) { contentDescription = text },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(Modifier.size(14.dp)) {
                if (cycle) drawCycleMarker(center, 5.dp.toPx(), marker, background)
                else drawRoundRect(primary, topLeft = Offset(0f, center.y - 2.5.dp.toPx()),
                    size = Size(size.width, 5.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5.dp.toPx()))
            }
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val usedLabel = stringResource(R.string.u_pace_used_legend)
    val cycleLabel = stringResource(R.string.u_cycle_progress_short, AssistantPresentation.percent(elapsed))
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("usage-pace-legend")) {
        if (maxWidth >= 240.dp && LocalDensity.current.fontScale <= 1.3f) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Entry(false, usedLabel, Modifier.weight(1f))
                Entry(true, cycleLabel, Modifier.weight(1.7f))
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Entry(false, usedLabel, Modifier.fillMaxWidth())
            Entry(true, cycleLabel, Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun UsagePaceBar(used: Double?, elapsed: Double?, description: String, modifier: Modifier = Modifier) {
    val fill = usageChartRatio(used, 100.0)
    val marker = usageChartRatio(elapsed, 100.0)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val color = MaterialTheme.colorScheme.primary
    val markerColor = MaterialTheme.colorScheme.tertiary
    val markerBackground = MaterialTheme.colorScheme.surfaceContainerLow
    Canvas(modifier.fillMaxWidth().height(20.dp).semantics { contentDescription = description }) {
        val inset = 7.dp.toPx()
        val width = (size.width - inset * 2).coerceAtLeast(0f)
        val height = 8.dp.toPx(); val top = (size.height - height) / 2
        drawRoundRect(track, topLeft = Offset(inset, top), size = Size(width, height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(height / 2))
        if (fill != null && fill > 0) drawRoundRect(color, topLeft = Offset(inset, top), size = Size(width * fill, height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(height / 2))
        marker?.let { drawCycleMarker(Offset(inset + width * it, size.height / 2), 6.dp.toPx(), markerColor, markerBackground) }
    }
}

@Composable
internal fun UsageTrendChart(points: List<UsageObservation>, metric: String, percentage: Boolean,
    selectedAt: Long?, description: String, axisLabel: (Double) -> String, timeLabel: (Long) -> String,
    onSelect: (Long) -> Unit) {
    val segments = remember(points, metric) { usageTrendSegments(points, metric) }
    val known = remember(segments) { segments.flatten() }
    if (known.size < 2) return
    val first = known.first().atMillis; val last = known.last().atMillis
    val span = (last.toDouble() - first.toDouble()).coerceAtLeast(1.0)
    val maximum = remember(known, metric, percentage) { known.maxOf { it.values.getValue(metric) }.coerceAtLeast(if (percentage) 100.0 else 1.0) }
    val color = MaterialTheme.colorScheme.primary; val grid = MaterialTheme.colorScheme.outlineVariant
    val selectedPoint = rememberUpdatedState(remember(known, selectedAt) {
        selectedAt?.let { at -> known.binarySearchBy(at) { it.atMillis }.takeIf { it >= 0 }?.let(known::get) }
    })
    val chartDrawing = remember(segments, metric, first, last, maximum, color, grid) {
        Modifier.drawWithCache {
            val inset = 6.dp.toPx()
            fun position(p: UsageObservation) = Offset(inset + ((p.atMillis.toDouble() - first) / span * (size.width - inset * 2)).toFloat(),
                inset + ((1 - p.values.getValue(metric) / maximum) * (size.height - inset * 2)).toFloat())
            val vertices = segments.map { segment -> usageTrendVertices(segment, metric, first, last, size.width.toInt()).map(::position) }
            val paths = vertices.map { coordinates -> Path().apply {
                coordinates.forEachIndexed { index, p -> if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            } }
            val lineStyle = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val gridYs = listOf(inset, size.height / 2, size.height - inset)
            onDrawBehind {
                gridYs.forEach { y -> drawLine(grid, Offset(inset, y), Offset(size.width - inset, y), 1.dp.toPx()) }
                paths.forEach { drawPath(it, color, style = lineStyle) }
                vertices.forEach { segment -> segment.forEach { drawCircle(color, 2.dp.toPx(), it) } }
                selectedPoint.value?.let { selected ->
                    val p = position(selected)
                    drawLine(color.copy(alpha = 0.35f), Offset(p.x, inset), Offset(p.x, size.height - inset), 1.dp.toPx())
                    drawCircle(color, 4.dp.toPx(), p)
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().testTag("usage-trend-chart")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.widthIn(min = 52.dp).height(150.dp), verticalArrangement = Arrangement.SpaceBetween) {
                listOf(maximum, maximum / 2, 0.0).forEach { Text(axisLabel(it), style = MaterialTheme.typography.labelSmall) }
            }
            Column(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(150.dp).semantics { contentDescription = description }
                .pointerInput(known, first, last) {
                    detectTapGestures { tap ->
                        val inset = 6.dp.toPx()
                        val target = first.toDouble() + ((tap.x - inset) / (size.width - inset * 2).coerceAtLeast(1f)).coerceIn(0f, 1f) * span
                        onSelect(nearestUsageSample(known, target).atMillis)
                    }
                }.then(chartDrawing)) {}
            Row(Modifier.fillMaxWidth()) {
                Text(timeLabel(first), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                Text(timeLabel(last), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End)
            }
            }
        }
    }
}
