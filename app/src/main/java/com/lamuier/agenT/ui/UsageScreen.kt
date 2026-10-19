package com.lamuier.agenT.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.lamuier.agenT.providers.*
import androidx.compose.foundation.horizontalScroll
import com.lamuier.agenT.R
import com.lamuier.agenT.codex.*
import com.lamuier.agenT.model.*
import com.lamuier.agenT.ui.theme.LocalDisplayZone
import com.lamuier.agenT.usage.*
import com.lamuier.agenT.util.*
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

internal val LocalUsageNow = staticCompositionLocalOf<Long?> { null }

/** Only configured sources are listed; cached results do not make a deleted connection visible. */
@Composable
internal fun UsageScreen(
    state: AppUiState, codex: CodexQuotaUiState,
    onLoadHistory: (String, Long, Long, String?) -> Unit,
    onRefresh: () -> Unit, onRefreshCodex: () -> Unit,
    onManageCursor: () -> Unit, onManageCodex: () -> Unit,
    active: Boolean = true,
    onHistoryDaysChange: (String, Int) -> Unit = { _, _ -> },
    onOpenFailed: (String) -> Unit = {},
    providers: ProvidersUiState = ProvidersUiState(false),
    onManageProvider: (UsageProvider) -> Unit = {}, onRefreshProvider: (UsageProvider) -> Unit = {},
) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("usage-page-v1", 0) }
    var provider by rememberSaveable { mutableStateOf(preferences.getString("provider", null)) }
    var explaining by rememberSaveable { mutableStateOf(false) }
    val visibleSources = configuredSourceIds(state.accounts.isNotEmpty(), codex.credential != null, providers)
    val selected = provider?.takeIf { it in visibleSources } ?: visibleSources.firstOrNull()
    LaunchedEffect(visibleSources, state.loadingAccounts, codex.booting, providers.booting) {
        if (!state.loadingAccounts && !codex.booting && !providers.booting && provider != selected) {
            provider = selected
            preferences.edit().apply { if (selected == null) remove("provider") else putString("provider", selected) }.apply()
        }
    }
    val destination by com.lamuier.agenT.widget.WidgetNavigation.destination.collectAsState()
    LaunchedEffect(destination, active, visibleSources, state.loadingAccounts, codex.booting, providers.booting) {
        val target = destination
        if (target?.tab == DashboardTab.Usage && active && !state.loadingAccounts && !codex.booting && !providers.booting) {
            provider = target.source.takeIf { it in visibleSources } ?: visibleSources.firstOrNull()
            preferences.edit().apply { if (provider == null) remove("provider") else putString("provider", provider) }.apply()
            com.lamuier.agenT.widget.WidgetNavigation.consume(target)
        }
    }
    val now = rememberUsageTime(active)
    val feedback = LocalAppFeedback.current
    val cursorFilters = rememberSaveable(state.selectedAccountId, saver = UsagePageFilters.Saver) { UsagePageFilters() }
    LaunchedEffect(active, selected, cursorFilters.days) {
        if (active) {
            onHistoryDaysChange("cursor", if (selected == "cursor") cursorFilters.days else 1)
            onHistoryDaysChange("codex", 1)
        }
    }
    val statistics = if (selected == "cursor" && state.usage != null) usageStatistics(state, now, onLoadHistory, cursorFilters, active) else null
    val summaryScroll = rememberLazyListState()
    val cursorScroll = rememberLazyListState(); val codexScroll = rememberLazyListState()
    val deepSeekScroll = rememberLazyListState(); val kimiScroll = rememberLazyListState()
    val detailScroll = when (selected) { "deepseek" -> deepSeekScroll; "kimi_code" -> kimiScroll; "codex" -> codexScroll; else -> cursorScroll }
    val select: (String) -> Unit = { next ->
        if (next != selected) { provider = next; preferences.edit().putString("provider", next).apply(); feedback.selection() }
    }
    val summary: LazyListScope.() -> Unit = {
        item("usage-summary") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.u_at_a_glance), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { explaining = true }, modifier = Modifier.testTag("usage-explanation")) { Text(stringResource(R.string.u_explanation)) }
            }
            val fontScale = LocalDensity.current.fontScale
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 320.dp && fontScale <= 1.3f) {
                    val cardWidth = if (visibleSources.size <= 1) maxWidth else (maxWidth - 12.dp) / 2
                    val sourceScroll = rememberScrollState()
                    val density = LocalDensity.current
                    LaunchedEffect(selected, cardWidth, visibleSources) {
                        val index = visibleSources.indexOf(selected)
                        sourceScroll.scrollTo(with(density) { ((cardWidth + 12.dp) * (index - 1).coerceAtLeast(0)).roundToPx() })
                    }
                    if (visibleSources.isEmpty()) ConfigureSourcesCard(onManageCursor)
                    Row(Modifier.horizontalScroll(sourceScroll), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if ("cursor" in visibleSources) CursorSummary(state, now, selected == "cursor", Modifier.width(cardWidth)) { select("cursor") }
                        if ("codex" in visibleSources) CodexSummary(codex, now, selected == "codex", Modifier.width(cardWidth)) { select("codex") }
                        UsageProvider.entries.filter { it.id in visibleSources }.forEach { source -> OfficialProviderSummary(source, providers[source], selected == source.id,
                            Modifier.width(cardWidth)) { select(source.id) } }
                    }
                } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (visibleSources.isEmpty()) ConfigureSourcesCard(onManageCursor)
                    if ("cursor" in visibleSources) CursorSummary(state, now, selected == "cursor") { select("cursor") }
                    if ("codex" in visibleSources) CodexSummary(codex, now, selected == "codex") { select("codex") }
                    UsageProvider.entries.filter { it.id in visibleSources }.forEach { source -> OfficialProviderSummary(source, providers[source], selected == source.id) { select(source.id) } }
                }
            }
        }
    }
    val details: LazyListScope.(Boolean) -> Unit = {
        if (selected == "cursor") cursorDetails(state, now, onLoadHistory, onManageCursor, onRefresh, cursorFilters, statistics)
        else if (selected == "codex") codexDetails(codex, now, onManageCodex, onRefreshCodex)
        else UsageProvider.entries.firstOrNull { it.id == selected }?.let { source ->
            item("official-provider-${source.id}") { OfficialProviderDetails(source, providers[source],
                { onManageProvider(source) }, { onRefreshProvider(source) }) }
        }
    }
    AdaptivePaneLayout(allowPair = visibleSources.isNotEmpty(), primaryFraction = 0.34f, maxContentWidthDp = 1440f,
        single = { AdaptiveLazyTabContent(state = detailScroll, modifier = Modifier.testTag("usage-detail-list")) { compact -> summary(); details(compact) } },
        primary = { AdaptiveLazyTabContent(state = summaryScroll, modifier = Modifier.testTag("usage-summary-list")) { summary() } },
        secondary = { AdaptiveLazyTabContent(state = detailScroll, modifier = Modifier.testTag("usage-detail-list"), content = details) })
    if (explaining) FoldSafeAlertDialog(onDismissRequest = { explaining = false },
        title = { Text(stringResource(R.string.u_explanation)) },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(R.string.u_dashboard_chart_help, R.string.u_tokens_note, R.string.u_financial_note, R.string.u_pace_note, R.string.u_codex_scope,
                R.string.u_lookback_note, R.string.u_cursor_trend_note, R.string.u_estimate_condition)
                .forEach { Text(stringResource(it)) }
        } },
        confirmButton = { TextButton(onClick = { explaining = false }) { Text(stringResource(R.string.action_close)) } })
}

@Composable
private fun UsageCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
internal fun SummaryCard(title: String, selected: Boolean, modifier: Modifier, onSelect: () -> Unit, tag: String = "usage-summary-" + title.lowercase(Locale.ROOT), content: @Composable ColumnScope.() -> Unit) {
    Surface(onClick = onSelect, modifier = modifier.fillMaxWidth().testTag(tag)
        .semantics { this.selected = selected; role = Role.Tab },
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (selected) Text("✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}

@Composable
private fun CursorSummary(state: AppUiState, now: Long, selected: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    val usage = state.usage
    val selectedAccount = state.accounts.firstOrNull { it.id == state.selectedAccountId } ?: state.accounts.firstOrNull()
    val tokenExpired = selectedAccount?.tokenExpired == true
    SummaryCard("Cursor", selected, modifier, onSelect) {
        if (usage == null) Text(stringResource(if (state.accounts.isEmpty()) R.string.connection_not_configured else if (state.loadingUsage) R.string.label_history_loading else R.string.label_data_unavailable), style = MaterialTheme.typography.bodySmall)
        else {
            UsageQuotaDial(stringResource(R.string.u_own_short), usage.usage.autoPercentUsed, tag = "usage-dial-own")
            UsageQuotaDial(stringResource(R.string.u_other_short), usage.usage.apiPercentUsed, tag = "usage-dial-other")
            UsageCalculations.billingProgress(usage.billingCycle.start, usage.billingCycle.end, now, LocalDisplayZone.current)?.let {
                Text(stringResource(R.string.u_reset_short, duration(it.remainingMillis)), style = MaterialTheme.typography.labelSmall)
            }
            Updated(usage.fetchedAt, usage.fromCache || state.error != null, usage.partialData)
        }
        if (tokenExpired) Text(stringResource(R.string.connection_token_expired), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun CodexSummary(state: CodexQuotaUiState, now: Long, selected: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    SummaryCard("Codex", selected, modifier, onSelect) {
        val usage = state.usage
        if (usage == null) Text(stringResource(if (state.credential == null) R.string.connection_not_configured else if (state.refreshing || state.booting) R.string.label_history_loading else R.string.label_data_unavailable), style = MaterialTheme.typography.bodySmall)
        else {
            UsageQuotaDial(stringResource(R.string.quota_five_hour_short), usage.primary?.usedPercent, tag = "usage-dial-primary")
            UsageQuotaDial(stringResource(R.string.quota_weekly_short), usage.secondary?.usedPercent, tag = "usage-dial-weekly")
            listOfNotNull(usage.primary?.resetAtEpochSeconds, usage.secondary?.resetAtEpochSeconds).minOrNull()?.let {
                Text(if (it * 1000 <= now) stringResource(R.string.u_reset_pending) else stringResource(R.string.u_reset_short, duration(it * 1000 - now)),
                    style = MaterialTheme.typography.labelSmall)
            }
            Updated(usage.fetchedAt, usage.fromCache || state.error != null)
        }
        if (state.credential?.let { it.invalid || it.expired } == true) Text(stringResource(R.string.codex_token_needs_update), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    }
}

private fun LazyListScope.cursorDetails(state: AppUiState, now: Long, load: (String, Long, Long, String?) -> Unit, manage: () -> Unit, refresh: () -> Unit, filters: UsagePageFilters, statistics: UsageStatistics?) {
    val usage = state.usage
    val selectedAccount = state.accounts.firstOrNull { it.id == state.selectedAccountId } ?: state.accounts.firstOrNull()
    val tokenExpired = selectedAccount?.tokenExpired == true
    if (usage == null) {
        item("cursor-state") { ServiceMessage(state.loadingUsage, stringResource(if (state.accounts.isEmpty()) R.string.dashboard_add_account_usage_body else R.string.label_data_unavailable),
            if (state.accounts.isEmpty() || tokenExpired) manage else refresh, manage = state.accounts.isEmpty() || tokenExpired) }
        return
    }
    if (tokenExpired) item("cursor-connection") {
        ServiceMessage(false, stringResource(R.string.dashboard_token_expired_usage_body), manage, manage = true)
    }
    item("usage-statistics") { statistics?.let { CursorStatistics(state, load, filters, it) } }
    item("usage-trend") { Trend(state.usageHistory, now, filters) }
    item("cursor-plan") { CursorPlan(usage, state.usageHistory, now, state.error != null || state.usageHistoryError || usage.fromCache || tokenExpired) }
    item("usage-financial") { CursorFinancials(usage) }
}

@Composable
private fun CursorPlan(usage: AgenTOverview, history: List<UsageObservation>, now: Long, stale: Boolean) {
    val progress = UsageCalculations.billingProgress(usage.billingCycle.start, usage.billingCycle.end, now, LocalDisplayZone.current)
    UsageCard(stringResource(R.string.u_budget_pace)) {
        Text(listOfNotNull(usage.plan.name, usage.plan.price).joinToString(" · "), style = MaterialTheme.typography.labelLarge)
        UsagePaceLegend(progress?.percent?.toDouble())
        listOf("own" to (R.string.label_cursor_models to usage.usage.autoPercentUsed), "other" to (R.string.label_other_models to usage.usage.apiPercentUsed)).forEach { (metric, row) ->
            Metric(stringResource(row.first), stringResource(R.string.u_used, percent(row.second)))
            UsagePaceBar(row.second, progress?.percent?.toDouble(), stringResource(R.string.u_pace_a11y, percent(row.second), percent(progress?.percent?.toDouble())),
                Modifier.testTag("usage-pace-" + metric))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (progress != null && row.second?.let { it.isFinite() && it >= 0 } == true) Text(
                    stringResource(R.string.u_delta_short, signed(requireNotNull(row.second) - progress.percent)), style = MaterialTheme.typography.labelMedium)
                Estimate(history, metric, now, true, stale, row.second, UsageHistoryWindows.parseLocalDateTimeMs(usage.billingCycle.end))
            }
        }
    }
}

private fun LazyListScope.codexDetails(state: CodexQuotaUiState, now: Long, manage: () -> Unit, refresh: () -> Unit) {
    val usage = state.usage
    if (usage == null) {
        item("codex-state") { ServiceMessage(state.booting || state.refreshing, stringResource(if (state.credential == null) R.string.codex_empty_description else R.string.label_data_unavailable),
            if (state.credential == null || state.credential.invalid || state.credential.expired) manage else refresh, manage = state.credential == null || state.credential.invalid || state.credential.expired) }
        return
    }
    if (state.credential?.let { it.invalid || it.expired } == true) item("codex-connection") {
        ServiceMessage(false, stringResource(R.string.codex_token_needs_update), manage, manage = true)
    }
    val stale = state.error != null || state.usageHistoryError || usage.fromCache || state.credential?.let { it.invalid || it.expired } == true
    item("window-primary") {
        UsageCard("Codex · " + (AssistantPresentation.plan(usage.planType) ?: stringResource(R.string.codex_plan_unknown))) {
            val windows = listOf("primary" to (stringResource(R.string.quota_five_hour_short) to usage.primary),
                "weekly" to (stringResource(R.string.quota_weekly_short) to usage.secondary))
            windows.forEach { (_, entry) -> Metric(entry.first, stringResource(R.string.u_used, percent(entry.second?.usedPercent))) }
            val resetLabel = stringResource(R.string.u_reset_time)
            val countdownLabel = stringResource(R.string.u_until_reset_label)
            UsageKpiGrid(windows.map { (metric, entry) ->
                val reset = entry.second?.resetAtEpochSeconds?.times(1000)
                UsageKpi(entry.first + " · " + resetLabel, reset?.let { compactStamp(it) } ?: "—",
                    "usage-window-reset-" + metric, reset?.let { date(it) } ?: "—")
            } + windows.map { (metric, entry) ->
                val reset = entry.second?.resetAtEpochSeconds?.times(1000)
                UsageKpi(entry.first + " · " + countdownLabel, reset?.let { if (it <= now) stringResource(R.string.u_reset_pending) else duration(it - now) } ?: "—",
                    "usage-window-countdown-" + metric)
            })
            windows.forEach { (metric, entry) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(entry.first, style = MaterialTheme.typography.labelMedium)
                Estimate(state.usageHistory, metric, now, false, stale, entry.second?.usedPercent, entry.second?.resetAtEpochSeconds?.times(1000))
            } }
            usage.resetCreditsAvailable?.let { Metric(stringResource(R.string.u_reset_balance), it.toString()) }
        }
    }
    usage.additional.forEachIndexed { i, window -> item("window-extra" + i) {
        CodexWindow(stringResource(R.string.codex_window_additional, i + 1), window, state.usageHistory, "extra" + i, now, stale)
    } }
}

@Composable
private fun CodexWindow(title: String, window: CodexQuotaWindow, history: List<UsageObservation>, metric: String, now: Long, stale: Boolean) {
    UsageCard(title) {
        Text(stringResource(R.string.u_used, percent(window.usedPercent)), style = MaterialTheme.typography.labelMedium)
        val reset = window.resetAtEpochSeconds?.times(1000)
        UsageKpiGrid(listOf(
            UsageKpi(stringResource(R.string.u_reset_time), reset?.let { compactStamp(it) } ?: "—", "usage-window-reset-" + metric, reset?.let { date(it) } ?: "—"),
            UsageKpi(stringResource(R.string.u_until_reset_label), reset?.let { if (it <= now) stringResource(R.string.u_reset_pending) else duration(it - now) } ?: "—", "usage-window-countdown-" + metric)))
        Estimate(history, metric, now, false, stale, window.usedPercent, reset)
    }
}

@Composable
private fun QuotaMeter(title: String, used: Double?) {
    UsageQuotaDial(title, used)
}

@Composable
private fun Estimate(history: List<UsageObservation>, metric: String, now: Long, cursor: Boolean, stale: Boolean, current: Double?, reset: Long?) {
    val last = history.lastOrNull()
    val matches = current != null && current.isFinite() && last?.values?.get(metric) == current && reset != null &&
        last.resetsAtMillis[metric]?.let { kotlin.math.abs(it - reset) <= if (cursor) 0L else 120_000L } == true
    val estimate = remember(history, metric, now, cursor, stale, matches) {
        if (stale || !matches) null else UsageAnalysis.estimate(history, metric, now, cursor)
    }
    val label = when {
        stale -> stringResource(R.string.u_wait_update)
        current != null && current.isFinite() && current >= 100 -> stringResource(R.string.u_exhausted)
        estimate == null -> stringResource(R.string.u_wait_samples)
        else -> stringResource(R.string.u_estimate_compact, duration((estimate.hoursRemaining * 3_600_000).toLong()), decimal(estimate.ratePerHour))
    }
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun usageStatistics(state: AppUiState, now: Long, load: (String, Long, Long, String?) -> Unit, filters: UsagePageFilters, active: Boolean): UsageStatistics {
    val usage = requireNotNull(state.usage)
    val zone = LocalDisplayZone.current
    val rangeMode = filters.rangeMode
    val monthOffset = filters.monthOffset
    val cycleOffset = filters.cycleOffset
    val start = UsageHistoryWindows.parseLocalDateTimeMs(usage.billingCycle.start)
    val end = UsageHistoryWindows.parseLocalDateTimeMs(usage.billingCycle.end)
    val month = YearMonth.from(Instant.ofEpochMilli(now).atZone(zone)).plusMonths(monthOffset.toLong())
    val queryNow = DisplayTime.parseStoredLocal(usage.fetchedAt)?.toEpochMilli() ?: now / 300_000 * 300_000
    val range = when (rangeMode) {
        0 -> if (start != null && end != null && end > start) EpochRange(start, minOf(queryNow, end - 1).coerceAtLeast(start)) else null
        1 -> EpochRange(queryNow - 7 * UsageAnalysis.DAY, queryNow)
        2 -> EpochRange(queryNow - 30 * UsageAnalysis.DAY, queryNow)
        3 -> UsageHistoryWindows.calendarMonth(month, queryNow, zone)
        else -> if (start != null && end != null) UsageHistoryWindows.billingCycleOffset(start, end, cycleOffset) else null
    }
    // Round moving-range endpoints to five minutes, so local countdown ticks do not issue new requests.
    val stableRange = range?.let { if (rangeMode in 1..2) it.copy(startMs = it.startMs / 300_000 * 300_000, endMs = it.endMs / 300_000 * 300_000) else it }
    val key = stableRange?.let { "analysis:${rangeMode}:${it.startMs}:${if (rangeMode == 0) 0 else it.endMs}" }
    val comparisonRange = stableRange?.takeIf { it.endMs > it.startMs }?.let { val length = it.endMs - it.startMs + 1; EpochRange(it.startMs - length, it.startMs - 1) }
    val comparisonKey = comparisonRange?.let { "compare:${key}:${it.endMs - it.startMs}" }
    val currentTokens = if (rangeMode == 0) usage.tokenUsage else key?.let { state.extraHistory[it]?.tokenUsage }
    val comparison = comparisonKey?.let { state.extraHistory[it]?.tokenUsage }
    val compareExpanded = filters.compareExpanded
    val hasCurrent = key != null && state.extraHistory[key] != null
    val hasComparison = comparisonKey != null && state.extraHistory[comparisonKey] != null
    LaunchedEffect(key, active, state.selectedAccountId, hasCurrent) {
        if (active && rangeMode != 0 && key != null && !hasCurrent && state.historyErrorKey != key && key !in state.historyErrors)
            load(key, stableRange.startMs, stableRange.endMs, null)
    }
    // Comparison requests start on explicit expansion, rather than fetching invisible pager pages.
    LaunchedEffect(compareExpanded, comparisonKey, active, state.selectedAccountId, hasComparison) {
        if (active && compareExpanded && comparisonKey != null && !hasComparison && state.historyErrorKey != comparisonKey && comparisonKey !in state.historyErrors)
            load(comparisonKey, comparisonRange.startMs, comparisonRange.endMs, null)
    }
    return UsageStatistics(stableRange, key, currentTokens, comparisonRange, comparisonKey, comparison)
}

private data class UsageStatistics(val range: EpochRange?, val key: String?, val tokens: TokenUsageBreakdown?, val previousRange: EpochRange?, val previousKey: String?, val previous: TokenUsageBreakdown?)

@Composable
private fun CursorStatistics(state: AppUiState, load: (String, Long, Long, String?) -> Unit, filters: UsagePageFilters, statistics: UsageStatistics) {
    val range = statistics.range; val key = statistics.key; val tokens = statistics.tokens
    val previousRange = statistics.previousRange; val previousKey = statistics.previousKey; val previous = statistics.previous
    var selecting by remember { mutableStateOf(false) }
    val titles = listOf(R.string.u_current_cycle, R.string.u_7days, R.string.u_30days, R.string.label_history_calendar_month, R.string.u_cycle_lookback)
    UsageCard(stringResource(R.string.u_statistics)) {
        Box {
            TextButton(onClick = { selecting = true }, modifier = Modifier.testTag("usage-range-selector")) {
                Text(stringResource(titles[filters.rangeMode]) + " ▾")
            }
            DropdownMenu(expanded = selecting, onDismissRequest = { selecting = false }) {
                titles.forEachIndexed { i, title -> DropdownMenuItem(text = { Text(stringResource(title)) }, modifier = Modifier.testTag("usage-range-option-" + i),
                    onClick = { filters.rangeMode = i; selecting = false }) }
            }
        }
        if (filters.rangeMode >= 3) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { if (filters.rangeMode == 3) filters.monthOffset-- else filters.cycleOffset-- }, enabled = if (filters.rangeMode == 3) filters.monthOffset > -12 else filters.cycleOffset > -12) { Text(stringResource(R.string.u_previous)) }
            TextButton(onClick = { if (filters.rangeMode == 3) filters.monthOffset++ else filters.cycleOffset++ }, enabled = if (filters.rangeMode == 3) filters.monthOffset < 0 else filters.cycleOffset < -1) { Text(stringResource(R.string.u_next)) }
        }
        range?.let { val full = date(it.startMs) + " → " + date(it.endMs)
            Text(compactStamp(it.startMs) + " — " + compactStamp(it.endMs), modifier = Modifier.semantics { contentDescription = full }, style = MaterialTheme.typography.labelSmall) }
        if (tokens == null) {
            Text(stringResource(if (key != null && (key in state.loadingHistoryKeys || state.loadingHistoryKey == key)) R.string.label_history_loading else R.string.label_data_unavailable))
            if (key != null && (key in state.historyErrors || state.historyErrorKey == key) && range != null)
                TextButton({ load(key, range.startMs, range.endMs, null) }) { Text(stringResource(R.string.action_reload)) }
        } else {
            TokenTotals(tokens)
            ModelRanking(tokens, filters)
        }
        TextButton(onClick = { filters.compareExpanded = !filters.compareExpanded }) { Text(stringResource(if (filters.compareExpanded) R.string.u_hide_comparison else R.string.u_compare)) }
        if (filters.compareExpanded) {
            previousRange?.let { Text(compactStamp(it.startMs) + " — " + compactStamp(it.endMs), style = MaterialTheme.typography.labelSmall) }
            if (tokens != null && previous != null) {
                listOf("cost" to (R.string.u_api_cost to (tokens.totalCostDollars to previous.totalCostDollars)),
                    "input" to (R.string.u_input to (tokens.totalInputTokens.toDouble() to previous.totalInputTokens.toDouble())),
                    "output" to (R.string.u_output to (tokens.totalOutputTokens.toDouble() to previous.totalOutputTokens.toDouble()))).forEach { (metric, row) ->
                    val current = row.second.first.takeIf { metric in tokens.availableMetrics }
                    val prior = row.second.second.takeIf { metric in previous.availableMetrics }
                    val max = maxOf(current ?: 0.0, prior ?: 0.0)
                    Text(stringResource(row.first), style = MaterialTheme.typography.labelLarge)
                    val currentLabel = stringResource(R.string.u_compare_current); val previousLabel = stringResource(R.string.u_compare_previous)
                    listOf(currentLabel to current, previousLabel to prior).forEachIndexed { index, entry ->
                        Metric(entry.first, entry.second?.let { if (metric == "cost") money(it) else usageMagnitude(it) } ?: "—")
                        UsageBar(entry.second, max, comparison = index == 1)
                    }
                    UsageAnalysis.change(current, prior)?.let { change ->
                        Text(if (change.percent != null) signed(change.percent) + "%" else if (metric == "cost") signed(change.absolute) + " USD" else signed(change.absolute),
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            } else {
                Text(stringResource(if (previousKey != null && (previousKey in state.loadingHistoryKeys || previousKey == state.loadingHistoryKey)) R.string.label_history_loading else R.string.u_compare_unknown), style = MaterialTheme.typography.bodySmall)
                if (previousKey != null && previousRange != null && (previousKey in state.historyErrors || state.historyErrorKey == previousKey))
                    TextButton({ load(previousKey, previousRange.startMs, previousRange.endMs, null) }) { Text(stringResource(R.string.action_reload)) }
            }
        }
    }
}

@Composable
private fun TokenTotals(tokens: TokenUsageBreakdown) {
    Metric(stringResource(R.string.u_api_cost), if ("cost" in tokens.availableMetrics) money(tokens.totalCostDollars) else "—", emphasize = true)
    UsageKpiGrid(listOf(
        Triple("input", R.string.u_input, tokens.totalInputTokens), Triple("output", R.string.u_output, tokens.totalOutputTokens),
        Triple("read", R.string.u_cache_read, tokens.totalCacheReadTokens), Triple("write", R.string.u_cache_write, tokens.totalCacheWriteTokens)
    ).map { (key, label, value) -> UsageKpi(stringResource(label), if (key in tokens.availableMetrics) UsageCalculations.formatTokens(value) else "—",
        "usage-token-" + key, if (key in tokens.availableMetrics) value.toString() else "—") })
}

@Composable
private fun ModelRanking(tokens: TokenUsageBreakdown, filters: UsagePageFilters) {
    val sortTokens = filters.sortTokens
    AdaptiveFilterRow {
        FilterChip(!filters.sortTokens, { filters.sortTokens = false }, label = { Text(stringResource(R.string.u_sort_cost)) })
        FilterChip(filters.sortTokens, { filters.sortTokens = true }, label = { Text(stringResource(R.string.u_sort_tokens)) })
    }
    fun amount(model: ModelTokenUsage): Double? = if (sortTokens)
        usageCombinedTokens(model)?.toDouble()
        else model.costDollars.takeIf { "cost" in model.availableMetrics && it.isFinite() && it >= 0 }
    val models = remember(tokens.models, sortTokens) {
        val order = if (sortTokens) compareByDescending<ModelTokenUsage> { usageCombinedTokens(it) }
            else compareByDescending { amount(it) }
        tokens.models.sortedWith(order.thenBy { it.modelIntent }.thenBy { it.tier ?: 0 })
    }
    val maximum = remember(models, sortTokens) { models.mapNotNull(::amount).maxOrNull() ?: 0.0 }
    if (!tokens.modelsAvailable) Text(stringResource(R.string.u_partial_short), style = MaterialTheme.typography.labelSmall)
    if (models.isEmpty() && tokens.modelsAvailable) Text(stringResource(R.string.label_token_empty))
    models.take(if (filters.allModels) models.size else 5).forEachIndexed { index, model ->
        key(model.modelIntent, model.tier, index) {
            val modelKey = model.modelIntent + ":" + model.tier
            val expanded = modelKey in filters.expandedModels
            val value = amount(model)
            val exactDescription = model.modelIntent + ": " + (value?.let { if (filters.sortTokens) usageCombinedTokens(model).toString() else money(it) } ?: "—")
            Column(Modifier.fillMaxWidth().testTag("usage-model-" + index), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Metric(model.modelIntent, value?.let { if (filters.sortTokens) usageMagnitude(it) else money(it) } ?: "—")
                UsageBar(value, maximum, Modifier.semantics { contentDescription = exactDescription })
                val perMillion = usageCostPerMillion(model)
                val perMillionDescription = stringResource(R.string.u_per_million_description)
                Text(stringResource(R.string.u_per_million, perMillion?.let(::formatCostPerMillion) ?: "—"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = perMillionDescription })
                TextButton({ filters.expandedModels = if (expanded) filters.expandedModels - modelKey else filters.expandedModels + modelKey }) {
                    Text(stringResource(if (expanded) R.string.u_collapse else R.string.u_exact_details))
                }
                if (expanded) {
                    UsageKpiGrid(listOf("input" to (R.string.u_input to model.inputTokens), "output" to (R.string.u_output to model.outputTokens),
                        "read" to (R.string.u_cache_read to model.cacheReadTokens), "write" to (R.string.u_cache_write to model.cacheWriteTokens)).map { (metric, row) ->
                        UsageKpi(stringResource(row.first), if (metric in model.availableMetrics) row.second.toString() else "—") })
                    Metric(stringResource(R.string.u_api_cost), if ("cost" in model.availableMetrics) money(model.costDollars) else "—")
                    if ("cost" in model.availableMetrics && "cost" in tokens.availableMetrics && tokens.totalCostDollars > 0 && model.costDollars in 0.0..tokens.totalCostDollars)
                        Text(stringResource(R.string.u_cost_share, percent(model.costDollars / tokens.totalCostDollars * 100)), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    if (models.size > 5) TextButton({ filters.allModels = !filters.allModels }) { Text(stringResource(if (filters.allModels) R.string.u_top_five else R.string.u_all_models, models.size)) }
}

@Composable
private fun CursorFinancials(usage: AgenTOverview) {
    val quota = UsageCalculations.quotaBreakdown(usage)
    UsageCard(stringResource(R.string.label_quota_composition)) {
        Metric(stringResource(R.string.label_plan_quota), if (quota.limitDollars > 0) money(quota.limitDollars) else "—")
        Metric(stringResource(R.string.label_included_usage), if ("included" in usage.usage.availableMetrics) money(quota.includedSpendDollars) else "—")
        Metric(stringResource(R.string.label_bonus), if ("bonus" in usage.usage.availableMetrics) money(quota.bonusSpendDollars) else "—")
        Metric(stringResource(R.string.label_remaining_quota), if ("remaining" in usage.usage.availableMetrics) money(usage.usage.remainingDollars) else "—")
        Metric(stringResource(R.string.label_total_spend), if ("total" in usage.usage.availableMetrics) money(quota.totalSpendDollars) else "—")
        usage.onDemand?.let {
            HorizontalDivider(); Text(stringResource(R.string.u_on_demand), style = MaterialTheme.typography.titleSmall)
            Metric(stringResource(R.string.u_individual_remaining), if ("individualRemaining" in it.availableMetrics) money(it.individualRemainingDollars) else "—")
            if (it.pooledLimitDollars > 0) Metric(stringResource(R.string.u_pool_remaining), if ("pooledRemaining" in it.availableMetrics) money(it.pooledRemainingDollars) else "—")
            Metric(stringResource(R.string.label_total_spend), if ("total" in it.availableMetrics) money(it.totalSpendDollars) else "—")
        }
        usage.grokBot?.let {
            HorizontalDivider(); QuotaMeter("Grok Bot", it.percentUsed)
            it.resetsAt?.let { reset -> DisplayTime.parseStoredLocal(reset)?.toEpochMilli()?.let { time -> Text(date(time)) } }
        }
    }
}

@Composable
private fun Trend(history: List<UsageObservation>, now: Long, filters: UsagePageFilters) {
    val options = listOf("cost" to R.string.u_api_cost, "input" to R.string.u_input, "output" to R.string.u_output, "read" to R.string.u_cache_read, "write" to R.string.u_cache_write)
    val points = remember(history, filters.days, now / 300_000) { history.filter { it.atMillis in (now - filters.days * UsageAnalysis.DAY)..now }.sortedBy { it.atMillis } }
    val known = remember(points, filters.metric) { points.filter { it.values[filters.metric]?.let { value -> value.isFinite() && value >= 0 } == true } }
    val chosen = known.firstOrNull { it.atMillis == filters.selectedAt } ?: known.lastOrNull()
    val chosenIndex = if (chosen == null) -1 else known.indexOf(chosen)
    val zone = LocalDisplayZone.current
    val stampNow = LocalUsageNow.current ?: System.currentTimeMillis()
    UsageCard(stringResource(R.string.u_local_trend), Modifier.testTag("usage-trend-panel")) {
        AdaptiveFilterRow { listOf(7, 30, 90).forEach { d -> FilterChip(filters.days == d, { filters.days = d }, label = { Text(stringResource(R.string.u_days, d)) }) } }
        AdaptiveFilterRow { options.forEach { (id, label) -> FilterChip(filters.metric == id, { filters.metric = id; filters.selectedAt = null }, label = { Text(stringResource(label)) }) } }
        if (known.isEmpty()) Text(stringResource(R.string.u_wait_samples), style = MaterialTheme.typography.labelMedium)
        else {
            fun axis(value: Double) = if (filters.metric == "cost") moneyCompact(value) else usageMagnitude(value)
            UsageTrendChart(points, filters.metric, false, chosen?.atMillis,
                stringResource(R.string.u_chart_a11y, known.size, date(known.first().atMillis), date(known.last().atMillis)),
                ::axis, { formatUsageStamp(it, zone, stampNow) }, { filters.selectedAt = it })
            val sample = requireNotNull(chosen)
            val rawValue = sample.values.getValue(filters.metric)
            Metric(stringResource(R.string.u_sample_value), axis(rawValue))
            Text(compactStamp(sample.atMillis), style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.semantics { contentDescription = rawValue.toString() + " · " + sample.atMillis })
            if (known.size == 1) Text(stringResource(R.string.u_wait_samples), style = MaterialTheme.typography.labelMedium)
            else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { filters.selectedAt = known[chosenIndex - 1].atMillis }, enabled = chosenIndex > 0) { Text(stringResource(R.string.u_sample_previous)) }
                TextButton(onClick = { filters.selectedAt = known[chosenIndex + 1].atMillis }, enabled = chosenIndex < known.lastIndex) { Text(stringResource(R.string.u_sample_next)) }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, emphasize: Boolean = false) {
    if (LocalDensity.current.fontScale > 1.3f) Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = if (emphasize) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = if (emphasize) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Updated(raw: String, cached: Boolean, partial: Boolean = false) {
    val time = DisplayTime.parseStoredLocal(raw)?.toEpochMilli()
    val text = time?.let { compactStamp(it) } ?: "—"
    val full = time?.let { date(it) } ?: "—"
    val description = stringResource(if (cached) R.string.u_cached_at else R.string.u_updated_at, full)
    Text(if (cached) stringResource(R.string.u_cached_short, text) else text, style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { contentDescription = description })
    if (partial) Text(stringResource(R.string.u_partial_short), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
}

@Composable
private fun ServiceMessage(loading: Boolean, message: String, action: () -> Unit, manage: Boolean = false) {
    UsageCard(stringResource(R.string.u_service_data)) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(message)
        if (!loading) TextButton(action) { Text(stringResource(if (manage) R.string.action_settings else R.string.action_reload)) }
    }
}

@Composable private fun compactStamp(millis: Long): String =
    formatUsageStamp(millis, LocalDisplayZone.current, LocalUsageNow.current ?: System.currentTimeMillis())

private fun formatUsageStamp(millis: Long, zone: java.time.ZoneId, now: Long): String {
    val instant = Instant.ofEpochMilli(millis).atZone(zone)
    val current = Instant.ofEpochMilli(now).atZone(zone)
    return instant.format(DateTimeFormatter.ofPattern(if (instant.year == current.year) "MM/dd HH:mm" else "yyyy/MM/dd HH:mm", Locale.getDefault()))
}
private fun moneyCompact(value: Double): String = when {
    value >= 1_000_000_000 -> String.format(Locale.US, "$%.1fB", value / 1_000_000_000)
    value >= 1_000_000 -> String.format(Locale.US, "$%.1fM", value / 1_000_000)
    value >= 10_000 -> String.format(Locale.US, "$%.1fK", value / 1_000)
    else -> money(value)
}

@Composable private fun date(millis: Long): String = DisplayTime.formatDateTime(Instant.ofEpochMilli(millis), LocalDisplayZone.current, withYear = true, includeTime = true)
@Composable private fun duration(millis: Long): String = UsageCalculations.formatRemaining(millis, resources = LocalResources.current)
private fun money(value: Double): String = String.format(Locale.US, "$%.2f", value)
private fun decimal(value: Double): String = String.format(Locale.US, "%.2f", value)
private fun signed(value: Double): String = String.format(Locale.US, "%+.2f", value)
private fun percent(value: Double?): String = AssistantPresentation.percent(value)
@Composable
private fun rememberUsageTime(active: Boolean): Long {
    val fixed = LocalUsageNow.current
    if (fixed != null) return fixed
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, active) { if (active) owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { now = System.currentTimeMillis(); delay(30_000) } } }
    return now
}
