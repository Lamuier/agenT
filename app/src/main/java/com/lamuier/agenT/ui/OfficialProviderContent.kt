package com.lamuier.agenT.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lamuier.agenT.R
import com.lamuier.agenT.providers.*
import com.lamuier.agenT.ui.theme.LocalPulseChartColors
import com.lamuier.agenT.util.CursorCustomTabs
import java.math.BigDecimal

@Composable
internal fun OfficialProviderSummary(provider: UsageProvider, state: ProviderState, selected: Boolean,
    modifier: Modifier = Modifier, onSelect: () -> Unit) {
    SummaryCard(provider.title, selected, modifier, onSelect, tag = "usage-summary-${provider.id}") {
        if (state.usage == null) Text(stringResource(if (state.credential == null) R.string.connection_not_configured
            else if (state.loading) R.string.label_history_loading else R.string.label_data_unavailable), style = MaterialTheme.typography.bodySmall)
        else {
            ProviderMetrics(state.usage, compact = true)
            OverviewFreshness(state.usage.fetchedAt, state.usage.fromCache || state.error != null)
        }
    }
}

@Composable
internal fun OfficialProviderOverview(provider: UsageProvider, state: ProviderState, modifier: Modifier = Modifier,
    onManage: () -> Unit) {
    AssistantOverviewCard(provider.title, null, state.serviceStatus, modifier) {
        if (state.usage != null) {
            if (provider == UsageProvider.DeepSeek) DeepSeekOverviewMetrics(state.usage)
            else ProviderMetrics(state.usage, compact = true)
            OverviewFreshness(state.usage.fetchedAt, state.usage.fromCache || state.error != null)
        } else Text(stringResource(if (state.credential == null) R.string.connection_not_configured
            else if (state.loading) R.string.label_history_loading else R.string.label_data_unavailable))
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onManage) { Text(stringResource(R.string.token_management_title)) }
    }
}

@Composable
internal fun OfficialProviderDetails(provider: UsageProvider, state: ProviderState, onManage: () -> Unit, onRefresh: () -> Unit) {
    val context = LocalContext.current
    Surface(Modifier.fillMaxWidth().testTag("usage-provider-detail-${provider.id}"), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(provider.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(when (provider) {
                UsageProvider.DeepSeek -> R.string.provider_deepseek_scope
                UsageProvider.KimiCode -> R.string.provider_kimi_scope
            }),
                style = MaterialTheme.typography.bodySmall)
            if (provider == UsageProvider.KimiCode && state.credential != null) Text(stringResource(
                if (state.credential.region == KimiRegion.China) R.string.provider_region_cn else R.string.provider_region_global),
                style = MaterialTheme.typography.labelLarge)
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val usage = state.usage
            if (usage == null) Text(stringResource(if (state.credential == null) R.string.connection_not_configured else R.string.label_data_unavailable))
            else {
                ProviderMetrics(usage, compact = false)
                OverviewFreshness(usage.fetchedAt, usage.fromCache || state.error != null)
            }
            TextButton(onClick = onManage, enabled = !state.saving) { Text(stringResource(R.string.token_management_title)) }
            OutlinedButton(onClick = onRefresh, enabled = state.credential != null && !state.loading && !state.saving) {
                Text(stringResource(R.string.action_reload))
            }
            TextButton(onClick = { CursorCustomTabs.open(context, provider.consoleUrl) }) { Text(stringResource(R.string.provider_official_console)) }
        }
    }
}

@Composable
private fun ProviderMetrics(usage: ProviderUsage, compact: Boolean) {
    if (usage.provider == UsageProvider.DeepSeek) {
        usage.balances.forEach { balance ->
            Text("${balance.currency} ${money(balance.total)}", style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium)
            if (!compact) {
                Text(stringResource(R.string.provider_balance_granted, money(balance.granted)))
                Text(stringResource(R.string.provider_balance_topped_up, money(balance.toppedUp)))
            }
        }
        Text(stringResource(when (usage.available) { true -> R.string.provider_balance_available
            false -> R.string.provider_balance_unavailable; null -> R.string.label_data_unavailable }), style = MaterialTheme.typography.bodySmall)
    } else {
        val windows = if (compact) usage.windows.take(2) else usage.windows
        windows.forEach { window ->
            val label = window.label ?: when (window.id) {
                "limit_5h" -> stringResource(R.string.quota_five_hour_short)
                "limit_7d" -> stringResource(R.string.quota_weekly_short)
                "limit_month_total" -> stringResource(R.string.provider_month_total)
                "limit_month_code" -> stringResource(R.string.provider_month_code)
                else -> window.durationMinutes?.let { stringResource(R.string.provider_window_minutes, it) }
                    ?: stringResource(R.string.provider_plan_quota)
            }
            if (window.inconsistent) Text(label, style = MaterialTheme.typography.labelLarge)
            if (window.inconsistent) Text(stringResource(R.string.provider_inconsistent), color = MaterialTheme.colorScheme.error)
            else if (window.disabled) Text("$label · ${stringResource(R.string.label_data_unavailable)}")
            else UsageQuotaDial(label, window.usedRatio?.multiply(BigDecimal(100))?.toDouble(), tag = "usage-dial-${window.id}")
            if (!compact) window.resetAt?.let { Text(stringResource(R.string.provider_resets_at, formatReset(it)), style = MaterialTheme.typography.bodySmall) }
        }
        if (compact && usage.windows.size > windows.size) Text(stringResource(R.string.widget_more_items, usage.windows.size - windows.size),
            style = MaterialTheme.typography.bodySmall)
        if (!compact) usage.booster?.let { wallet ->
            HorizontalDivider()
            Text(stringResource(R.string.provider_booster), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.provider_booster_remaining, "${wallet.currency.orEmpty()} ${money(wallet.remaining)}".trim()))
            wallet.total?.let { Text(stringResource(R.string.provider_booster_total, "${wallet.currency.orEmpty()} ${money(it)}".trim())) }
            wallet.monthlyUsed?.let { Text(stringResource(R.string.provider_month_used, "${wallet.currency.orEmpty()} ${money(it)}")) }
            if (wallet.limitEnabled == true) wallet.monthlyLimit?.let { Text(stringResource(R.string.provider_month_limit, "${wallet.currency.orEmpty()} ${money(it)}")) }
        }
    }
}
@Composable
private fun DeepSeekOverviewMetrics(usage: ProviderUsage) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.provider_balance_caption), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (usage.balances.isEmpty()) {
            Text("—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        } else {
            val fontScale = LocalDensity.current.fontScale
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val sideBySide = usage.balances.size > 1 && maxWidth >= 280.dp && fontScale <= 1.3f
                if (sideBySide) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                        usage.balances.forEach { DeepSeekBalance(it, Modifier.weight(1f)) }
                    }
                } else Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    usage.balances.forEach { DeepSeekBalance(it, Modifier.fillMaxWidth()) }
                }
            }
        }
        val statusColor = when (usage.available) {
            true -> LocalPulseChartColors.current.healthy
            false -> LocalPulseChartColors.current.critical
            null -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor))
            Text(stringResource(when (usage.available) {
                true -> R.string.provider_balance_available
                false -> R.string.provider_balance_unavailable
                null -> R.string.label_data_unavailable
            }), style = MaterialTheme.typography.labelMedium, color = statusColor)
        }
    }
}

@Composable
private fun DeepSeekBalance(balance: MoneyBalance, modifier: Modifier) {
    val hero = if (LocalDensity.current.fontScale > 1.3f) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displaySmall
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(balance.currency, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(money(balance.total), style = hero, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        listOf(R.string.provider_balance_granted_label to balance.granted, R.string.provider_balance_topped_label to balance.toppedUp)
            .forEach { (label, amount) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(money(amount), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
    }
}

private fun money(value: BigDecimal?): String = value?.stripTrailingZeros()?.toPlainString() ?: "—"
@Composable private fun formatReset(value: String): String {
    val zone = com.lamuier.agenT.ui.theme.LocalDisplayZone.current
    return runCatching { java.time.Instant.parse(value).atZone(zone)
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")) }.getOrDefault(value)
}
