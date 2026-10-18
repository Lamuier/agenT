package com.lamuier.agenT.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lamuier.agenT.R
import com.lamuier.agenT.codex.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.floor

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ResetHeatmapCard(data: ResetHeatmapData, selectedDate: LocalDate?, onSelect: (LocalDate) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val monthFormatter = remember(locale) { DateTimeFormatter.ofPattern("MMM", locale) }
    val cellPath = remember { Path() }
    var choosingDate by rememberSaveable { mutableStateOf(false) }
    val datePicker = key(data.from, data.to) { rememberDatePickerState(initialSelectedDateMillis = (selectedDate ?: data.to)
        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(), selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val date = java.time.Instant.ofEpochMilli(utcTimeMillis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                return date >= data.from && date <= data.to
            }
            override fun isSelectableYear(year: Int) = year in data.from.year..data.to.year
        }) }
    if (choosingDate) DatePickerDialog(onDismissRequest = { choosingDate = false },
        confirmButton = { TextButton(enabled = datePicker.selectedDateMillis != null, onClick = {
            val date = java.time.Instant.ofEpochMilli(datePicker.selectedDateMillis!!).atZone(java.time.ZoneOffset.UTC).toLocalDate()
            if (data.days.any { it.date == date }) { onSelect(date); choosingDate = false }
        }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = { choosingDate = false }) { Text(stringResource(R.string.action_cancel)) } }) {
            DatePicker(state = datePicker)
        }
    val palette = resetChartColors()
    val colors = mapOf(ResetHeatmapKind.Regular to palette.regular, ResetHeatmapKind.Banked to palette.banked)
    val empty = palette.empty
    val outline = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
    val selectedColor = MaterialTheme.colorScheme.primary
    val gridScroll = rememberScrollState()
    var positionedRange by rememberSaveable { mutableStateOf<String?>(null) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val cell = 22.dp
    val gap = 4.dp
    val step = cell + gap
    val header = 24.dp
    val gridWidth = step * data.weeks.size + 20.dp
    val gridHeight = header + step * 7 - gap
    val stepPx = with(density) { step.toPx() }
    val cellPx = with(density) { cell.toPx() }
    val headerPx = with(density) { header.toPx() }
    val rangeKey = "${data.from}/${data.to}"
    LaunchedEffect(rangeKey, viewportWidth) {
        if (viewportWidth > 0 && positionedRange != rangeKey) {
            gridScroll.scrollTo((with(density) { gridWidth.roundToPx() } - viewportWidth).coerceAtLeast(0))
            positionedRange = rangeKey
        }
    }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HeatmapLegend(colors.getValue(ResetHeatmapKind.Regular), stringResource(R.string.reset_dash_regular))
                HeatmapLegend(colors.getValue(ResetHeatmapKind.Banked), stringResource(R.string.reset_dash_banked))
                HeatmapLegend(empty, stringResource(R.string.reset_heatmap_no_record))
            }
            OutlinedButton(onClick = { choosingDate = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.heatmap_select_date))
            }
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.width(40.dp).padding(top = header), verticalArrangement = Arrangement.spacedBy(gap)) {
                    (0..6).forEach { row -> Box(Modifier.height(cell), contentAlignment = Alignment.CenterStart) {
                        val label = when (row) { 1 -> R.string.reset_heatmap_mon; 3 -> R.string.reset_heatmap_wed; 5 -> R.string.reset_heatmap_fri; else -> null }
                        label?.let { Text(stringResource(it), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                    } }
                }
                Box(Modifier.weight(1f).onSizeChanged { viewportWidth = it.width }.horizontalScroll(gridScroll)) {
                    Box(Modifier.width(gridWidth).height(gridHeight)) {
                        Canvas(Modifier.fillMaxSize().pointerInput(data, stepPx, cellPx, headerPx) {
                            detectTapGestures { point ->
                                val x = point.x; val y = point.y - headerPx
                                if (x >= 0 && y >= 0 && x % stepPx < cellPx && y % stepPx < cellPx)
                                    CodexResetHeatmap.dayAt(data, floor(x / stepPx).toInt(), floor(y / stepPx).toInt())
                                        ?.let { onSelect(it.date) }
                            }
                        }) {
                            val radius = 4.dp.toPx()
                            val cornerRadius = CornerRadius(radius, radius)
                            data.weeks.forEachIndexed { column, week -> week.forEachIndexed { row, day ->
                                if (!day.inRange) return@forEachIndexed
                                val topLeft = Offset(column * stepPx, headerPx + row * stepPx)
                                val size = Size(cellPx, cellPx)
                                cellPath.reset()
                                cellPath.addRoundRect(RoundRect(topLeft.x, topLeft.y,
                                    topLeft.x + size.width, topLeft.y + size.height, cornerRadius))
                                clipPath(cellPath) {
                                    val types = day.kinds
                                    if (types.isEmpty()) drawRect(empty, topLeft, size)
                                    else types.forEachIndexed { index, type ->
                                        val width = cellPx / types.size
                                        drawRect(colors.getValue(type), Offset(topLeft.x + index * width, topLeft.y), Size(width, cellPx))
                                    }
                                }
                                if (day.entries.isNotEmpty()) drawRoundRect(outline, topLeft, size, cornerRadius, style = Stroke(1.dp.toPx()))
                                if (day.date == selectedDate) drawRoundRect(selectedColor, topLeft, size, cornerRadius, style = Stroke(2.dp.toPx()))
                            } }
                        }
                        var previousMonth: java.time.YearMonth? = null
                        data.weeks.forEachIndexed { column, week ->
                            val firstDay = week.firstOrNull { it.inRange } ?: return@forEachIndexed
                            val monthDay = week.firstOrNull { it.inRange && it.date.dayOfMonth == 1 } ?: firstDay
                            val month = java.time.YearMonth.from(monthDay.date)
                            if (month != previousMonth) {
                                Text(monthDay.date.format(monthFormatter),
                                    Modifier.offset(x = step * column), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                previousMonth = month
                            }
                            week.forEachIndexed { row, day -> if (day.inRange) {
                                val description = resources.getString(R.string.reset_heatmap_day_accessibility,
                                    day.date.toString(), day.entries.size)
                                // Virtual cells provide TalkBack actions; pointer hit testing stays exact on the dense grid.
                                Box(Modifier.offset(x = step * column, y = header + step * row).size(cell).semantics {
                                    contentDescription = description
                                    selected = day.date == selectedDate
                                    onClick { onSelect(day.date); true }
                                })
                            } }
                        }
                    }
                }
            }
            Text(stringResource(R.string.reset_dash_heatmap_hint), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun HeatmapLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(11.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
