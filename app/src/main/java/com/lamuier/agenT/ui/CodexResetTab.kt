package com.lamuier.agenT.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lamuier.agenT.R
import com.lamuier.agenT.codex.*
import com.lamuier.agenT.ui.theme.LocalDisplayZone
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

internal val LocalResetNow = staticCompositionLocalOf<Instant?> { null }

@Composable
internal fun CodexResetTab(state: CodexQuotaUiState, onSubscriptionDay: (Int) -> Unit,
    onRefreshPublicResets: (Boolean) -> Unit = {}, active: Boolean = true) {
    val zone = LocalDisplayZone.current
    val context = LocalContext.current
    val fixedNow = LocalResetNow.current
    var period by rememberSaveable { mutableStateOf(CodexResetPeriod.Week) }
    var clock by remember { mutableStateOf(Instant.now()) }
    val now = fixedNow ?: clock
    var configuring by rememberSaveable { mutableStateOf(false) }
    var explaining by rememberSaveable { mutableStateOf(false) }
    var heatmapMonths by rememberSaveable { mutableIntStateOf(6) }
    var heatmapEnd by rememberSaveable(zone.id) { mutableStateOf(now.atZone(zone).toLocalDate()) }
    var selectedDay by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var expandedIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    val feedback = LocalAppFeedback.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(active) { if (active) onRefreshPublicResets(false) }
    LaunchedEffect(fixedNow, active, lifecycleOwner) {
        if (fixedNow == null && active) lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) { clock = Instant.now(); delay(60_000) }
        }
    }
    val anchor = state.resetLedger?.subscriptionDay
    val range = CodexResetPeriods.range(period, now, zone, anchor)
    val snapshot = state.publicResets
    val periodEntries = remember(snapshot?.events, range) { CodexResetTimeline.entries(snapshot?.events.orEmpty(), range) }
    val metrics = remember(snapshot?.events, range, now.atZone(zone).toLocalDate(), zone) {
        if (snapshot != null && range != null) resetDashboardData(snapshot.events, range, now, zone) else null
    }
    val allEntries = remember(snapshot?.events) { CodexResetTimeline.entries(snapshot?.events.orEmpty(), null, allPublicHistory = true) }
    val chartFrom = heatmapEnd.minusMonths(heatmapMonths.toLong()).plusDays(1)
    val chartRange = CodexResetRange(chartFrom.atStartOfDay(zone).toEpochSecond(), heatmapEnd.plusDays(1).atStartOfDay(zone).toEpochSecond())
    val chartEntries = remember(snapshot?.events, chartRange) { CodexResetTimeline.entries(snapshot?.events.orEmpty(), chartRange) }
    val chart = remember(chartEntries, chartFrom, heatmapEnd, zone) { CodexResetHeatmap.build(chartEntries, chartFrom, heatmapEnd, zone) }
    val dayEntries = remember(allEntries, selectedDay, zone) {
        selectedDay?.let { day -> allEntries.filter { Instant.ofEpochSecond(it.at).atZone(zone).toLocalDate() == day } }.orEmpty()
    }
    val visibleEntries = if (selectedDay == null) periodEntries else dayEntries
    AdaptiveLazyServiceContent(primary = {
        item(key = "summary") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.reset_dash_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { explaining = true }, modifier = Modifier.testTag("reset-explanation")) { Text(stringResource(R.string.u_explanation)) }
                IconButton(onClick = { onRefreshPublicResets(true) }, enabled = !state.refreshingPublicResets, modifier = Modifier.testTag("reset-refresh")) {
                    if (state.refreshingPublicResets) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Refresh, stringResource(R.string.reset_public_refresh))
                }
            }
        }
        item(key = "period") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ResetPeriodSelector(period) { if (period != it) { period = it; selectedDay = null; feedback.selection() } }
                range?.let { Text(resetStamp(it.start, zone, true) + " — " + resetStamp(it.end - 1, zone, true),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (period == CodexResetPeriod.Subscription) {
                    TextButton(onClick = { configuring = true }, enabled = state.credential != null && !state.resetSaving) {
                        Text(anchor?.let { stringResource(R.string.reset_dash_anchor, it) } ?: stringResource(R.string.reset_set_anchor))
                    }
                    if (state.credential == null) Text(stringResource(R.string.reset_connect_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (range == null) Text(stringResource(R.string.reset_anchor_missing), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item(key = "official-stats") {
            ResetDashboardCard(stringResource(R.string.reset_dash_composition)) {
                ResetComposition(metrics)
                UsageKpiGrid(listOf(
                    UsageKpi(stringResource(R.string.reset_dash_recorded_days), metrics?.recordedDays?.toString() ?: "—", "reset-recorded-days"),
                    UsageKpi(stringResource(R.string.reset_dash_latest), metrics?.latest?.let { resetStamp(it, zone) } ?: "—", "reset-latest",
                        metrics?.latest?.let { Instant.ofEpochSecond(it).atZone(zone).toString() } ?: "—")))
            }
        }
        item(key = "public-status") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.reset_dash_public), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (snapshot != null) {
                    Text((if (snapshot.fromCache || state.publicResetsError != null) stringResource(R.string.overview_cached) + " · " else "") +
                        resetStamp(snapshot.fetchedAt / 1000, zone), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (snapshot.truncated) Text(stringResource(R.string.reset_dash_partial), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                } else Text(stringResource(if (state.refreshingPublicResets) R.string.action_refreshing else R.string.label_data_unavailable), style = MaterialTheme.typography.labelSmall)
            }
        }
        item(key = "frequency") {
            ResetDashboardCard(stringResource(R.string.reset_dash_frequency)) {
                ResetTypeLegend()
                ResetFrequencyChart(metrics, selectedDay) { selectedDay = it; feedback.selection() }
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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { heatmapEnd = heatmapEnd.minusMonths(heatmapMonths.toLong()); selectedDay = null }) { Text(stringResource(R.string.reset_heatmap_earlier)) }
                    Text(chartFrom.format(HEATMAP_RANGE_FORMATTER) + " — " + heatmapEnd.format(HEATMAP_RANGE_FORMATTER),
                        Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    TextButton(onClick = { heatmapEnd = minOf(heatmapEnd.plusMonths(heatmapMonths.toLong()), today); selectedDay = null }, enabled = heatmapEnd < today) {
                        Text(stringResource(R.string.reset_heatmap_later)) }
                }
                if (snapshot != null) ResetHeatmapCard(chart, selectedDay) { selectedDay = it; feedback.selection() }
                else Text(stringResource(R.string.label_data_unavailable), style = MaterialTheme.typography.labelSmall)
            }
        }
        item(key = "records-heading") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(selectedDay?.let { stringResource(R.string.reset_heatmap_selected, it.toString(), dayEntries.size) }
                    ?: stringResource(R.string.reset_period_records), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                if (selectedDay != null) TextButton(onClick = { selectedDay = null }) { Text(stringResource(R.string.reset_show_period)) }
            }
            if (snapshot != null && visibleEntries.isEmpty() && (range != null || selectedDay != null)) Text(
                stringResource(if (selectedDay == null) R.string.reset_period_empty else R.string.reset_heatmap_empty_day), style = MaterialTheme.typography.labelSmall)
        }
        items(visibleEntries, key = { "public:" + it.public.id }) { entry ->
            PublicResetCard(entry.public, zone, entry.public.id in expandedIds) {
                expandedIds = if (entry.public.id in expandedIds) expandedIds - entry.public.id else expandedIds + entry.public.id
            }
        }
    }, primaryModifier = Modifier.testTag("reset-primary-list"), secondaryModifier = Modifier.testTag("reset-detail-list"))
    if (configuring) ResetAnchorDialog(anchor, onDismiss = { configuring = false }, onSave = { onSubscriptionDay(it); configuring = false })
    if (explaining) FoldSafeAlertDialog(onDismissRequest = { explaining = false }, title = { Text(stringResource(R.string.u_explanation)) },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(R.string.reset_dash_chart_help, R.string.reset_api_count_hint, R.string.reset_public_boundary, R.string.reset_public_truncated,
                R.string.reset_heatmap_boundary, R.string.reset_data_boundary).forEach { Text(stringResource(it)) }
            TextButton(onClick = { com.lamuier.agenT.util.CursorCustomTabs.open(context, CodexPublicResetLinks.CREDIT_URL) }) {
                Text(stringResource(R.string.reset_public_credit)) }
        } }, confirmButton = { TextButton(onClick = { explaining = false }) { Text(stringResource(R.string.action_close)) } })
}

@Composable internal fun ResetDashboardCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable private fun PublicResetCard(event: CodexPublicReset, zone: ZoneId, expanded: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val colors = resetChartColors()
    var linkFailed by remember { mutableStateOf(false) }
    val title = stringResource(if (event.type == "banked") R.string.reset_dash_banked else R.string.reset_dash_regular)
    val source = stringResource(if (event.sourceType == "observed") R.string.reset_dash_observed else R.string.reset_dash_announcement)
    val action = stringResource(if (expanded) R.string.reset_dash_collapse else R.string.reset_dash_expand)
    Surface(onClick = onToggle, modifier = Modifier.fillMaxWidth().testTag("reset-record-" + event.id).semantics { stateDescription = action },
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    ResetTypeLabel(title, if (event.type == "banked") colors.banked else colors.regular)
                    Text(resetStamp(event.at, zone) + " · " + source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, action)
            }
            if (expanded) {
                Text(event.text, style = MaterialTheme.typography.bodySmall)
                Text(Instant.ofEpochSecond(event.at).atZone(zone).toString(), style = MaterialTheme.typography.labelSmall)
                Text(stringResource(if (event.sourceType == "observed") R.string.reset_public_observed else R.string.reset_public_announcement),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { linkFailed = !com.lamuier.agenT.util.CursorCustomTabs.open(context, CodexPublicResetLinks.CREDIT_URL) }) { Text(stringResource(R.string.reset_public_credit)) }
                    event.url?.let { url -> TextButton(onClick = { linkFailed = !com.lamuier.agenT.util.CursorCustomTabs.open(context, url) }) { Text(stringResource(R.string.reset_public_open_source)) } }
                }
                if (linkFailed) Text(stringResource(R.string.reset_public_link_error), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable private fun ResetPeriodSelector(period: CodexResetPeriod, onSelect: (CodexResetPeriod) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("reset-period-selector")) { Text(stringResource(periodLabel(period)) + " ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CodexResetPeriod.entries.forEach { item -> DropdownMenuItem(text = { Text(stringResource(periodLabel(item))) },
                modifier = Modifier.testTag("reset-period-" + item.name), onClick = { expanded = false; onSelect(item) }) }
        }
    }
}

@Composable private fun ResetAnchorDialog(initial: Int?, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var day by rememberSaveable(initial) { mutableStateOf(initial?.toString().orEmpty()) }
    FoldSafeAlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.reset_set_anchor)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.reset_anchor_help), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(day, { day = it }, label = { Text(stringResource(R.string.reset_anchor_day)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        } },
        confirmButton = { TextButton(onClick = { onSave(day.toInt()) }, enabled = day.toIntOrNull()?.let { it in 1..31 } == true) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}
private val HEATMAP_RANGE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")
private val RESET_DATE_ONLY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")
private val RESET_DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm")
private fun resetStamp(epoch: Long, zone: ZoneId, dateOnly: Boolean = false): String =
    Instant.ofEpochSecond(epoch).atZone(zone).format(if (dateOnly) RESET_DATE_ONLY_FORMATTER else RESET_DATE_TIME_FORMATTER)
private fun periodLabel(period: CodexResetPeriod) = when (period) {
    CodexResetPeriod.Week -> R.string.reset_period_week
    CodexResetPeriod.Month -> R.string.reset_period_month
    CodexResetPeriod.Subscription -> R.string.reset_period_subscription
}
