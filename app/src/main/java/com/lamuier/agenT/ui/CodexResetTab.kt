package com.lamuier.agenT.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lamuier.agenT.R
import com.lamuier.agenT.codex.*
import com.lamuier.agenT.ui.theme.LocalDisplayZone
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable
internal fun CodexResetTab(state: CodexQuotaUiState, onSubscriptionDay: (Int) -> Unit,
    onRefreshPublicResets: (Boolean) -> Unit = {}) {
    val zone = LocalDisplayZone.current
    var period by rememberSaveable { mutableStateOf(CodexResetPeriod.Week) }
    var now by remember { mutableStateOf(Instant.now()) }
    var configuring by remember { mutableStateOf(false) }
    var heatmapMonths by rememberSaveable { mutableIntStateOf(6) }
    var heatmapEnd by rememberSaveable(zone.id) { mutableStateOf(LocalDate.now(zone)) }
    var selectedDay by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    val context = LocalContext.current
    val feedback = LocalAppFeedback.current
    LaunchedEffect(Unit) { onRefreshPublicResets(false) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = Instant.now() } }
    val anchor = state.resetLedger?.subscriptionDay
    val range = CodexResetPeriods.range(period, now, zone, anchor)
    val snapshot = state.publicResets
    val periodEntries = CodexResetTimeline.entries(snapshot?.events.orEmpty(), range)
    val chartFrom = heatmapEnd.minusMonths(heatmapMonths.toLong()).plusDays(1)
    val chartRange = CodexResetRange(chartFrom.atStartOfDay(zone).toEpochSecond(), heatmapEnd.plusDays(1).atStartOfDay(zone).toEpochSecond())
    val chartEntries = CodexResetTimeline.entries(snapshot?.events.orEmpty(), chartRange)
    val chart = remember(chartEntries, chartFrom, heatmapEnd, zone) { CodexResetHeatmap.build(chartEntries, chartFrom, heatmapEnd, zone) }
    val dayEntries = selectedDay?.let { day -> chart.days.firstOrNull { it.date == day }?.entries }.orEmpty()
    val visibleEntries = if (selectedDay == null) periodEntries else dayEntries
    AdaptiveLazyServiceContent(primary = {
        item(key = "summary") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.reset_page_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.reset_page_subtitle), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "period") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ResetPeriodSelector(period) { if (period != it) { period = it; selectedDay = null; feedback.selection() } }
                range?.let { Text("${resetStamp(it.start, zone, dateOnly = true)} — ${resetStamp(it.end - 1, zone, dateOnly = true)}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (period == CodexResetPeriod.Subscription) {
                    TextButton(onClick = { configuring = true }, enabled = state.credential != null && !state.resetSaving) {
                        Text(anchor?.let { stringResource(R.string.reset_anchor_value, it) } ?: stringResource(R.string.reset_set_anchor))
                    }
                    if (range == null) Text(stringResource(R.string.reset_anchor_missing), style = MaterialTheme.typography.bodySmall)
                    state.resetError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item(key = "official-stats") {
            ResetStat(Modifier.fillMaxWidth(), R.string.reset_official_regular_count,
                if (snapshot != null && range != null) periodEntries.count { it.public.type == "regular" }.toString() else "—",
                stringResource(R.string.reset_regular_count_hint))
        }
        item(key = "credit-grant-stats") {
            ResetStat(Modifier.fillMaxWidth(), R.string.reset_official_banked_count,
                if (snapshot != null && range != null) periodEntries.count { it.public.type == "banked" }.toString() else "—",
                stringResource(R.string.reset_banked_count_hint))
        }
        item(key = "public-status") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.reset_public_boundary), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                AdaptiveFilterRow(spacing = 4.dp) {
                    TextButton(onClick = { com.lamuier.agenT.util.CursorCustomTabs.open(context, CodexPublicResetLinks.CREDIT_URL) }) {
                        Text(stringResource(R.string.reset_public_credit))
                    }
                    TextButton(onClick = { onRefreshPublicResets(true) }, enabled = !state.refreshingPublicResets) {
                        Text(stringResource(if (state.refreshingPublicResets) R.string.action_refreshing else R.string.reset_public_refresh))
                    }
                }
                state.publicResetsError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                snapshot?.let {
                    Text(stringResource(R.string.reset_public_updated, resetStamp(it.fetchedAt / 1000, zone)) +
                        if (it.fromCache) " · ${stringResource(R.string.overview_cached)}" else "",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (it.truncated) Text(stringResource(R.string.reset_public_truncated), style = MaterialTheme.typography.bodySmall)
                }
                if (snapshot == null) Text(stringResource(if (state.refreshingPublicResets) R.string.action_refreshing else R.string.reset_public_load_error),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }, secondary = {
        item(key = "timeline") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.reset_heatmap_title), style = MaterialTheme.typography.titleMedium)
                val today = now.atZone(zone).toLocalDate()
                AdaptiveFilterRow(spacing = 6.dp) {
                    listOf(3, 6, 12).forEach { months -> FilterChip(selected = heatmapMonths == months,
                        onClick = { if (heatmapMonths != months) { heatmapMonths = months; selectedDay = null; feedback.selection() } },
                        label = { Text(stringResource(R.string.reset_heatmap_months, months)) }) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick = { heatmapEnd = heatmapEnd.minusMonths(heatmapMonths.toLong()); selectedDay = null }) {
                        Text(stringResource(R.string.reset_heatmap_earlier))
                    }
                    Text("${chartFrom.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))} — ${heatmapEnd.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))}",
                        Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    TextButton(onClick = { heatmapEnd = minOf(heatmapEnd.plusMonths(heatmapMonths.toLong()), today); selectedDay = null },
                        enabled = heatmapEnd < today) { Text(stringResource(R.string.reset_heatmap_later)) }
                }
                ResetHeatmapCard(chart, selectedDay, onSelect = { selectedDay = it })
                Text(stringResource(R.string.reset_heatmap_boundary), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "records-heading") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(selectedDay?.let { stringResource(R.string.reset_heatmap_selected, it.toString(), dayEntries.size) }
                    ?: stringResource(R.string.reset_period_records), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                if (selectedDay != null) TextButton(onClick = { selectedDay = null }) { Text(stringResource(R.string.reset_show_period)) }
                if (snapshot != null && visibleEntries.isEmpty() && (range != null || selectedDay != null)) {
                    Text(stringResource(if (selectedDay == null) R.string.reset_period_empty else R.string.reset_heatmap_empty_day), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(visibleEntries, key = { "public:${it.public.id}" }) { entry -> PublicResetCard(entry.public, zone) }
        item(key = "boundary") {
            Text(stringResource(R.string.reset_data_boundary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    })
    if (configuring) ResetAnchorDialog(anchor, onDismiss = { configuring = false }, onSave = { onSubscriptionDay(it); configuring = false })
}

@Composable private fun PublicResetCard(event: CodexPublicReset, zone: ZoneId) {
    val context = LocalContext.current
    var linkFailed by remember { mutableStateOf(false) }
    fun open(url: String) { linkFailed = !com.lamuier.agenT.util.CursorCustomTabs.open(context, url) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(if (event.type == "banked") R.string.reset_public_banked else R.string.reset_public_regular),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.reset_public_event_time, resetStamp(event.at, zone)), style = MaterialTheme.typography.labelSmall)
            Text(stringResource(if (event.sourceType == "observed") R.string.reset_public_observed else R.string.reset_public_announcement),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(event.text, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { open(CodexPublicResetLinks.CREDIT_URL) }) { Text(stringResource(R.string.reset_public_credit)) }
                event.url?.let { url -> TextButton(onClick = { open(url) }) { Text(stringResource(R.string.reset_public_open_source)) } }
            }
            if (linkFailed) Text(stringResource(R.string.reset_public_link_error), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ResetPeriodSelector(period: CodexResetPeriod, onSelect: (CodexResetPeriod) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        @Composable fun Chip(item: CodexResetPeriod, modifier: Modifier = Modifier) {
            FilterChip(selected = period == item, onClick = { onSelect(item) },
                label = { Text(stringResource(periodLabel(item))) }, modifier = modifier)
        }
        if (maxWidth < 320.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CodexResetPeriod.entries.forEach { Chip(it) }
            }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CodexResetPeriod.entries.forEach { Chip(it, Modifier.weight(1f)) }
        }
    }
}

@Composable private fun ResetStat(modifier: Modifier, title: Int, value: String, detail: String) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.labelMedium)
            Text(value, fontSize = if (value.toIntOrNull() == null && value != "—") 18.sp else 26.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun ResetAnchorDialog(initial: Int?, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var day by remember { mutableStateOf(initial?.toString().orEmpty()) }
    FoldSafeAlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.reset_set_anchor)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.reset_anchor_help), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(day, { day = it }, label = { Text(stringResource(R.string.reset_anchor_day)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        } },
        confirmButton = { TextButton(onClick = { onSave(day.toInt()) }, enabled = day.toIntOrNull()?.let { it in 1..31 } == true) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}
private fun resetStamp(epoch: Long, zone: ZoneId, dateOnly: Boolean = false): String =
    Instant.ofEpochSecond(epoch).atZone(zone).format(DateTimeFormatter.ofPattern(if (dateOnly) "yyyy/MM/dd" else "MM/dd HH:mm"))
private fun periodLabel(period: CodexResetPeriod) = when (period) {
    CodexResetPeriod.Week -> R.string.reset_period_week
    CodexResetPeriod.Month -> R.string.reset_period_month
    CodexResetPeriod.Subscription -> R.string.reset_period_subscription
}
