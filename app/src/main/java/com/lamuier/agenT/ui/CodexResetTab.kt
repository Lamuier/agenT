package com.lamuier.agenT.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lamuier.agenT.R
import com.lamuier.agenT.codex.*
import com.lamuier.agenT.ui.theme.LocalDisplayZone
import com.lamuier.agenT.util.AssistantPresentation
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.UUID
import kotlinx.coroutines.delay

@Composable
internal fun CodexResetTab(state: CodexQuotaUiState, onManageTokens: () -> Unit,
    onRegister: (CodexResetEvent, () -> Unit) -> Unit, onDelete: (String) -> Unit,
    onSubscriptionDay: (Int) -> Unit, onRefresh: () -> Unit,
    onRefreshPublicResets: (Boolean) -> Unit = {}) {
    val zone = LocalDisplayZone.current
    var period by remember { mutableStateOf(CodexResetPeriod.Week) }
    var filter by remember { mutableStateOf<CodexResetKind?>(null) }
    var now by remember { mutableStateOf(Instant.now()) }
    var recording by remember { mutableStateOf(false) }
    var classifying by remember { mutableStateOf<CodexResetEvent?>(null) }
    var deleting by remember { mutableStateOf<CodexResetEvent?>(null) }
    var configuring by remember { mutableStateOf(false) }
    var timelineSource by remember { mutableStateOf(CodexResetTimelineSource.All) }
    var heatmapMonths by remember { mutableIntStateOf(6) }
    var heatmapEnd by remember(zone) { mutableStateOf(LocalDate.now(zone)) }
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { onRefreshPublicResets(false) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = Instant.now() } }
    val ledger = state.resetLedger ?: CodexResetLedger()
    val range = CodexResetPeriods.range(period, now, zone, ledger.subscriptionDay)
    val events = CodexResetPeriods.events(ledger, range)
    fun sum(vararg kinds: CodexResetKind) = events.filter { it.kind in kinds }.sumOf { it.count }
    val recorded = range != null && ledger.startedAt > 0 && state.resetError == null
    val invalid = state.credential?.let { it.invalid || it.expired } == true
    val usage = state.usage.takeUnless { invalid }
    AdaptiveTabContent {
        Text(stringResource(R.string.reset_page_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.reset_page_subtitle), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.credential == null) {
            Text(stringResource(R.string.reset_connect_hint))
            Button(onClick = onManageTokens) { Text(stringResource(R.string.token_management_title)) }
        }
        state.resetError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CodexResetPeriod.entries.forEach { item ->
                FilterChip(selected = period == item, onClick = { period = item },
                    label = { Text(stringResource(periodLabel(item))) }, modifier = Modifier.weight(1f))
            }
        }
        range?.let { Text("${resetStamp(it.start, zone, dateOnly = true)} — ${resetStamp(it.end - 1, zone, dateOnly = true)}",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (period == CodexResetPeriod.Subscription) {
            TextButton(onClick = { configuring = true }, enabled = state.credential != null && !state.resetSaving) {
                Text(ledger.subscriptionDay?.let { stringResource(R.string.reset_anchor_value, it) }
                    ?: stringResource(R.string.reset_set_anchor))
            }
            if (range == null) Text(stringResource(R.string.reset_anchor_missing), style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ResetStat(Modifier.weight(1f).fillMaxHeight(), R.string.reset_personal, if (recorded) sum(CodexResetKind.ManualUse).toString() else "—", stringResource(R.string.reset_recorded_hint))
            ResetStat(Modifier.weight(1f).fillMaxHeight(), R.string.reset_official_extra, stringResource(R.string.reset_unknown),
                if (recorded) stringResource(R.string.reset_extra_registered_count, sum(CodexResetKind.OfficialExtra)) else stringResource(R.string.reset_extra_hint))
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ResetStat(Modifier.weight(1f).fillMaxHeight(), R.string.reset_automatic, if (recorded) sum(CodexResetKind.AutomaticPrimary, CodexResetKind.AutomaticWeekly).toString() else "—", stringResource(R.string.reset_automatic_hint))
            ResetStat(Modifier.weight(1f).fillMaxHeight(), R.string.reset_unclassified, if (recorded) sum(CodexResetKind.WindowChanged).toString() else "—", stringResource(R.string.reset_unclassified_hint))
        }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.reset_available), style = MaterialTheme.typography.titleSmall)
                Text(usage?.resetCreditsAvailable?.toString() ?: stringResource(R.string.reset_unknown), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.reset_official_balance_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.usage?.fromCache == true) Text(stringResource(R.string.overview_cached), style = MaterialTheme.typography.labelSmall)
                if (invalid) Text(stringResource(R.string.connection_token_expired), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRefresh, enabled = !state.refreshing && state.credential != null) { Text(stringResource(R.string.action_refresh)) }
            }
        }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.reset_next_windows), style = MaterialTheme.typography.titleMedium)
                ResetWindow(stringResource(R.string.quota_five_hour_short), usage?.primary, now, zone)
                ResetWindow(stringResource(R.string.quota_weekly_short), usage?.secondary, now, zone)
            }
        }
        Text(stringResource(R.string.reset_credit_details), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        val credits = usage?.resetCredits?.details
        if (credits == null) Text(stringResource(R.string.reset_details_unknown), style = MaterialTheme.typography.bodySmall)
        else {
            Text(stringResource(R.string.reset_detail_boundary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (credits.isEmpty()) Text(stringResource(R.string.reset_details_empty), style = MaterialTheme.typography.bodySmall)
            credits.take(30).forEach { credit ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(credit.title ?: stringResource(R.string.reset_credit_title), fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.reset_credit_status, credit.status.ifBlank { stringResource(R.string.reset_unknown) }), style = MaterialTheme.typography.bodySmall)
                        credit.grantedAt?.let { Text(stringResource(R.string.reset_granted_at, resetStamp(it, zone)), style = MaterialTheme.typography.bodySmall) }
                        Text(credit.expiresAt?.let { stringResource(R.string.reset_expires_at, resetStamp(it, zone)) }
                            ?: stringResource(R.string.reset_expiry_unknown), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.reset_heatmap_title), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { classifying = null; recording = true }, enabled = state.credential != null && !state.resetSaving) { Text(stringResource(R.string.reset_add_record)) }
        }
        Text(stringResource(R.string.reset_public_boundary), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { com.lamuier.agenT.util.CursorCustomTabs.open(context, CodexPublicResetLinks.CREDIT_URL) }) {
                Text(stringResource(R.string.reset_public_credit))
            }
            TextButton(onClick = { onRefreshPublicResets(true) }, enabled = !state.refreshingPublicResets) {
                Text(stringResource(if (state.refreshingPublicResets) R.string.action_refreshing else R.string.reset_public_refresh))
            }
        }
        state.publicResetsError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        state.publicResets?.let { snapshot ->
            Text(stringResource(R.string.reset_public_updated, resetStamp(snapshot.fetchedAt / 1000, zone)) +
                if (snapshot.fromCache) " · ${stringResource(R.string.overview_cached)}" else "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (snapshot.truncated) Text(stringResource(R.string.reset_public_truncated), style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CodexResetTimelineSource.entries.forEach { item ->
                FilterChip(selected = timelineSource == item,
                    onClick = { timelineSource = item; filter = null; selectedDay = null }, label = {
                        Text(stringResource(when (item) {
                            CodexResetTimelineSource.All -> R.string.reset_source_all
                            CodexResetTimelineSource.Local -> R.string.reset_source_local
                            CodexResetTimelineSource.Public -> R.string.reset_source_public
                        }))
                    })
            }
        }
        if (timelineSource != CodexResetTimelineSource.Public) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(null, CodexResetKind.ManualUse, CodexResetKind.OfficialExtra, CodexResetKind.AutomaticPrimary, CodexResetKind.AutomaticWeekly, CodexResetKind.WindowChanged).forEach { item ->
                FilterChip(selected = filter == item, onClick = { filter = item; selectedDay = null },
                    label = { Text(stringResource(item?.let(::resetKindLabel) ?: R.string.reset_filter_all)) })
            }
        }
        }
        val today = now.atZone(zone).toLocalDate()
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(3, 6, 12).forEach { months -> FilterChip(selected = heatmapMonths == months,
                onClick = { heatmapMonths = months; selectedDay = null },
                label = { Text(stringResource(R.string.reset_heatmap_months, months)) }) }
        }
        val chartFrom = heatmapEnd.minusMonths(heatmapMonths.toLong()).plusDays(1)
        val chartRange = CodexResetRange(chartFrom.atStartOfDay(zone).toEpochSecond(), heatmapEnd.plusDays(1).atStartOfDay(zone).toEpochSecond())
        val chartEntries = CodexResetTimeline.entries(ledger.events, state.publicResets?.events.orEmpty(), chartRange, timelineSource, filter)
        val chart = remember(chartEntries, chartFrom, heatmapEnd, zone) { CodexResetHeatmap.build(chartEntries, chartFrom, heatmapEnd, zone) }
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
        if (timelineSource != CodexResetTimelineSource.Local && state.publicResets == null) Text(
            stringResource(if (state.refreshingPublicResets) R.string.action_refreshing else R.string.reset_public_load_error),
            style = MaterialTheme.typography.bodySmall)
        val dayEntries = selectedDay?.let { day -> chart.days.firstOrNull { it.date == day }?.entries }.orEmpty()
        selectedDay?.let { day -> Text(stringResource(R.string.reset_heatmap_selected, day.toString(), dayEntries.size),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium) }
        if (selectedDay != null && dayEntries.isEmpty()) Text(stringResource(R.string.reset_heatmap_empty_day), style = MaterialTheme.typography.bodySmall)
        dayEntries.forEach { entry ->
            if (entry.public != null) PublicResetCard(entry.public, zone)
            else {
            val event = entry.local!!
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(resetKindLabel(event.kind)), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.reset_event_meta, resetStamp(event.at, zone), stringResource(resetSourceLabel(event.source)), event.count),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (event.before != null && event.after != null) Text(
                        if (event.kind == CodexResetKind.BalanceChanged) "${event.before.toInt()} → ${event.after.toInt()}"
                        else "${AssistantPresentation.percent(event.before)} → ${AssistantPresentation.percent(event.after)}", style = MaterialTheme.typography.bodySmall)
                    if (event.note.isNotBlank()) Text(event.note, style = MaterialTheme.typography.bodySmall)
                    Row {
                        if (event.kind == CodexResetKind.WindowChanged) TextButton(onClick = { classifying = event; recording = true }, enabled = !state.resetSaving) { Text(stringResource(R.string.reset_classify)) }
                        if (event.source != CodexResetSource.Official) TextButton(onClick = { deleting = event }, enabled = !state.resetSaving) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            }
        }
        HorizontalDivider()
        Text(stringResource(R.string.reset_data_boundary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ledger.startedAt > 0) Text(stringResource(R.string.reset_tracking_since, resetStamp(ledger.startedAt, zone)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ledger.truncated) Text(stringResource(R.string.reset_history_truncated), style = MaterialTheme.typography.bodySmall)
    }
    if (recording) ResetRecordDialog(classifying, zone, state.resetSaving, state.resetError,
        onDismiss = { recording = false; classifying = null },
        onSave = { event -> onRegister(event) { recording = false; classifying = null } })
    deleting?.let { event -> AlertDialog(onDismissRequest = { if (!state.resetSaving) deleting = null },
        title = { Text(stringResource(R.string.reset_delete_title)) }, text = { Text(stringResource(R.string.reset_delete_body)) },
        confirmButton = { TextButton(onClick = { onDelete(event.id); deleting = null }, enabled = !state.resetSaving) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } }) }
    if (configuring) ResetAnchorDialog(ledger.subscriptionDay, onDismiss = { configuring = false }, onSave = { onSubscriptionDay(it); configuring = false })
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

@Composable private fun ResetStat(modifier: Modifier, title: Int, value: String, detail: String) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.labelMedium)
            Text(value, fontSize = if (value.toIntOrNull() == null && value != "—") 18.sp else 26.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun ResetWindow(label: String, window: CodexQuotaWindow?, now: Instant, zone: ZoneId) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, fontWeight = FontWeight.Medium)
        val epoch = window?.resetAtEpochSeconds
        Text(epoch?.let { resetStamp(it, zone) } ?: stringResource(R.string.codex_reset_unknown), style = MaterialTheme.typography.bodySmall)
        val remaining = epoch?.minus(now.epochSecond)
        if (remaining != null) Text(if (remaining <= 0) stringResource(R.string.reset_due)
            else stringResource(R.string.reset_countdown, remaining / 86400, remaining % 86400 / 3600, remaining % 3600 / 60),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun ResetRecordDialog(initial: CodexResetEvent?, zone: ZoneId, busy: Boolean, remoteError: String?,
    onDismiss: () -> Unit, onSave: (CodexResetEvent) -> Unit) {
    val context = LocalContext.current
    var kind by remember { mutableStateOf(CodexResetKind.ManualUse) }
    var count by remember { mutableStateOf(initial?.count?.toString() ?: "1") }
    var time by remember { mutableStateOf(resetStamp(initial?.at ?: Instant.now().epochSecond, zone, input = true)) }
    var note by remember { mutableStateOf(initial?.note.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (initial == null) R.string.reset_add_record else R.string.reset_classify)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.reset_registration_hint), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(CodexResetKind.ManualUse, CodexResetKind.OfficialExtra, CodexResetKind.AutomaticPrimary, CodexResetKind.AutomaticWeekly).forEach { item ->
                    FilterChip(selected = kind == item, onClick = { kind = item }, enabled = !busy, label = { Text(stringResource(resetKindLabel(item))) })
                }
            }
            OutlinedTextField(value = count, onValueChange = { count = it; error = null }, enabled = !busy, label = { Text(stringResource(R.string.reset_count_label)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(value = time, onValueChange = { time = it; error = null }, enabled = !busy, label = { Text(stringResource(R.string.reset_time_label)) }, singleLine = true)
            OutlinedTextField(value = note, onValueChange = { note = it.take(256) }, enabled = !busy, label = { Text(stringResource(R.string.reset_note_label)) }, maxLines = 3)
            (error ?: remoteError)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val amount = count.toIntOrNull()
            val at = runCatching { LocalDateTime.parse(time.trim(), INPUT_FORMAT).atZone(zone).toEpochSecond() }.getOrNull()
            if (amount == null || amount !in 1..999 || at == null || at <= 0 || at > Instant.now().epochSecond + 120) {
                error = context.getString(R.string.reset_invalid_record)
            } else onSave(CodexResetEvent(initial?.id ?: UUID.randomUUID().toString(), kind, CodexResetSource.Registered, at, amount, note.trim(), initial?.before, initial?.after))
        }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } })
}
@Composable private fun ResetAnchorDialog(initial: Int?, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var day by remember { mutableStateOf(initial?.toString().orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.reset_set_anchor)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.reset_anchor_help), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(day, { day = it }, label = { Text(stringResource(R.string.reset_anchor_day)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        } },
        confirmButton = { TextButton(onClick = { onSave(day.toInt()) }, enabled = day.toIntOrNull()?.let { it in 1..31 } == true) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}
private val INPUT_FORMAT = DateTimeFormatter.ofPattern("uuuu/MM/dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
private fun resetStamp(epoch: Long, zone: ZoneId, dateOnly: Boolean = false, input: Boolean = false): String =
    Instant.ofEpochSecond(epoch).atZone(zone).format(if (input) INPUT_FORMAT else DateTimeFormatter.ofPattern(if (dateOnly) "yyyy/MM/dd" else "MM/dd HH:mm"))
private fun periodLabel(period: CodexResetPeriod) = when (period) {
    CodexResetPeriod.Week -> R.string.reset_period_week
    CodexResetPeriod.Month -> R.string.reset_period_month
    CodexResetPeriod.Subscription -> R.string.reset_period_subscription
}
private fun resetSourceLabel(source: CodexResetSource) = when (source) {
    CodexResetSource.Official -> R.string.reset_source_official
    CodexResetSource.Observed -> R.string.reset_source_observed
    CodexResetSource.Registered -> R.string.reset_source_registered
}
private fun resetKindLabel(kind: CodexResetKind) = when (kind) {
    CodexResetKind.ManualUse -> R.string.reset_personal
    CodexResetKind.OfficialExtra -> R.string.reset_official_extra
    CodexResetKind.AutomaticPrimary -> R.string.reset_kind_primary
    CodexResetKind.AutomaticWeekly -> R.string.reset_kind_weekly
    CodexResetKind.WindowChanged -> R.string.reset_unclassified
    CodexResetKind.BalanceChanged -> R.string.reset_balance_change
    CodexResetKind.CreditGranted -> R.string.reset_credit_granted
    CodexResetKind.CreditExpired -> R.string.reset_credit_expired
}
